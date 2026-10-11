package dev.quickdrop.core.notification;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Talks to the external SMS vendor (vendor-mock, reached through Toxiproxy).
 *
 * <p>The read timeout comes from the {@code vendor-timeout-ms} toggle on every request. Its default,
 * 0, means "wait forever" — the most common production mistake there is (scenario 2.7).
 */
@Component
public class VendorClient {

    private final RestClient client;

    public VendorClient(RestClient.Builder builder, ToggleRegistry toggles, @Value("${quickdrop.vendor.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setReadTimeout((int) toggles.getLong(CoreToggles.VENDOR_TIMEOUT_MS));
            }
        };
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public void sendSms(SmsMessage message) {
        client.post()
                .uri("/v1/sms")
                .contentType(MediaType.APPLICATION_JSON)
                .body(message)
                .retrieve()
                .toBodilessEntity();
    }
}
