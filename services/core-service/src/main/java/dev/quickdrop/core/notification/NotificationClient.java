package dev.quickdrop.core.notification;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import dev.quickdrop.common.resilience.Retrier;
import dev.quickdrop.common.toggles.ToggleChangedEvent;
import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls notification-service over HTTP (notification-mode=http) — scenarios 6.1 and 6.2. Three
 * protections, each a toggle:
 * <ul>
 *   <li>{@code notify-timeout-ms}: stop waiting after this long (0 = forever).</li>
 *   <li>{@code notify-circuit-breaker}: after repeated failures, stop calling for 5 s and fail instantly.</li>
 *   <li>{@code notify-bulkhead}: at most N calls in flight; the rest fail instantly instead of queueing
 *       for a thread — so a slow notification-service can only ever tie up N of our request threads.</li>
 * </ul>
 * Instances are picked at random from NOTIFY_URLS (client-side load balancing). Retries (8.3):
 * {@code notify-retries}, {@code notify-retry-backoff}, {@code notify-retry-budget}; each attempt picks an instance.
 */
@Component
public class NotificationClient {

    public static class Rejected extends RuntimeException {
        public Rejected(String reason) {
            super(reason);
        }
    }

    private final List<RestClient> instances;
    private final ToggleRegistry toggles;
    private final CircuitBreaker breaker;
    private final Retrier retrier;
    private volatile Semaphore bulkhead;

    public NotificationClient(RestClient.Builder builder, ToggleRegistry toggles, CircuitBreakerRegistry breakers,
                              MeterRegistry meters, @Value("${quickdrop.notify.urls}") String urls) {
        this.toggles = toggles;
        this.retrier = new Retrier("notification-service", meters);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setReadTimeout((int) toggles.getLong(CoreToggles.NOTIFY_TIMEOUT_MS));
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(2));
        this.instances = Arrays.stream(urls.split(",")).map(String::trim).filter(u -> !u.isEmpty())
                .map(u -> builder.clone().baseUrl(u).requestFactory(factory).build()).toList();
        this.breaker = breakers.circuitBreaker("notification-service", CircuitBreakerConfig.custom()
                .slidingWindowSize(20).minimumNumberOfCalls(10).failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(1)).slowCallRateThreshold(80)
                .waitDurationInOpenState(Duration.ofSeconds(5)).permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true).build());
        this.bulkhead = newBulkhead();
    }

    public void send(SmsMessage message) {
        Semaphore permits = bulkhead;
        boolean limited = toggles.getLong(CoreToggles.NOTIFY_BULKHEAD) > 0;
        if (limited && !permits.tryAcquire()) {
            throw new Rejected("bulkhead full");
        }
        try {
            if (toggles.is(CoreToggles.NOTIFY_CIRCUIT_BREAKER, "on")) {
                try {
                    breaker.executeRunnable(() -> post(message));
                } catch (CallNotPermittedException e) {
                    throw new Rejected("circuit breaker open");
                }
            } else {
                post(message);
            }
        } finally {
            if (limited) {
                permits.release();
            }
        }
    }

    private void post(SmsMessage message) {
        retrier.call(() -> {
                    RestClient client = instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
                    return client.post().uri("/notifications").contentType(MediaType.APPLICATION_JSON).body(message).retrieve().toBodilessEntity();
                },
                e -> e instanceof ResourceAccessException || e instanceof HttpServerErrorException,   // timeouts, refused, 5xx
                (int) toggles.getLong(CoreToggles.NOTIFY_RETRIES),
                toggles.is(CoreToggles.NOTIFY_RETRY_BACKOFF, "exponential-jitter"),
                toggles.is(CoreToggles.NOTIFY_RETRY_BUDGET, "on"));
    }

    @EventListener
    public void onToggle(ToggleChangedEvent event) {
        if (event.toggle().equals("notify-bulkhead")) {
            bulkhead = newBulkhead();
        }
    }

    private Semaphore newBulkhead() {
        return new Semaphore((int) Math.max(1, toggles.getLong(CoreToggles.NOTIFY_BULKHEAD)));
    }
}
