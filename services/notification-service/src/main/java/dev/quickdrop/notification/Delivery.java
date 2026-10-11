package dev.quickdrop.notification;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.quickdrop.common.resilience.Retrier;
import dev.quickdrop.common.toggles.ToggleRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Sends one notification to the SMS vendor and records what happened. Every path ends here: sync HTTP,
 * the in-memory queue, the Postgres queue and the Kafka consumers.
 */
@Component
public class Delivery {

    public enum Result { SENT, RATE_LIMITED, FAILED }

    private final RestClient vendor;
    private final VendorRateLimiter rateLimiter;
    private final MeterRegistry meters;
    private final ToggleRegistry toggles;
    private final Retrier retrier;

    public Delivery(RestClient.Builder builder, VendorRateLimiter rateLimiter, ToggleRegistry toggles, MeterRegistry meters,
                    @Value("${quickdrop.vendor.base-url}") String vendorUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setReadTimeout((int) toggles.getLong(NotificationToggles.VENDOR_TIMEOUT_MS));
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(2));
        this.vendor = builder.baseUrl(vendorUrl).requestFactory(factory).build();
        this.rateLimiter = rateLimiter;
        this.meters = meters;
        this.toggles = toggles;
        this.retrier = new Retrier("sms-vendor", meters);
        for (String type : List.of("ORDER_PLACED", "OTP", "PROMO")) {
            for (Result r : Result.values()) {
                meters.counter("notify.delivered", "type", type, "result", r.name().toLowerCase());
            }
        }
    }

    public Result send(Notification n, String path) {
        rateLimiter.acquire();
        Result result;
        try {
            retrier.call(() -> vendor.post().uri("/v1/sms").contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("messageId", n.messageId(), "type", n.type(), "to", n.to(), "body", n.body()))
                            .retrieve().toBodilessEntity(),
                    e -> e instanceof ResourceAccessException || e instanceof HttpServerErrorException,   // timeouts, refused, 5xx (8.3)
                    (int) toggles.getLong(NotificationToggles.VENDOR_RETRIES),
                    toggles.is(NotificationToggles.VENDOR_RETRY_BACKOFF, "exponential-jitter"),
                    toggles.is(NotificationToggles.VENDOR_RETRY_BUDGET, "on"));
            result = Result.SENT;
        } catch (HttpClientErrorException.TooManyRequests e) {
            result = Result.RATE_LIMITED;
        } catch (RuntimeException e) {
            result = Result.FAILED;
        }
        meters.counter("notify.delivered", "type", n.type(), "result", result.name().toLowerCase()).increment();
        meters.counter("notify.delivered.by.path", "path", path, "result", result.name().toLowerCase()).increment();
        if (result == Result.SENT && n.createdAt() > 0) {
            // From the moment the producer created the message to the moment the vendor accepted it.
            Timer.builder("notify.delivery.delay").tag("type", n.type()).register(meters)
                    .record(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - n.createdAt())));
        }
        return result;
    }
}
