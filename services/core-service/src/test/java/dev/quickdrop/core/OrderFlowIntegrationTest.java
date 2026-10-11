package dev.quickdrop.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The one end-to-end test: a real Postgres (Testcontainers), Flyway migrations and seed data, the
 * full HTTP stack. Places an order with the "fixed" strategies and checks that it got stock, a rider,
 * shows up in the history, and that no invariant is violated.
 *
 * <p>Run with {@code make test} (needs Docker).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "quickdrop.toggles.inventory-reservation=atomic",
        "quickdrop.toggles.rider-assignment=conditional",
        "quickdrop.toggles.order-idempotency=on",
        "quickdrop.toggles.notification-mode=none",
})
class OrderFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

    @Autowired
    TestRestTemplate http;

    @Test
    void placesAnOrderEndToEnd() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "it-order-1");
        Map<String, Object> request = Map.of("customerId", 3, "storeId", 1,
                "items", List.of(Map.of("productId", 11, "quantity", 2), Map.of("productId", 12, "quantity", 1)));

        ResponseEntity<Map<String, Object>> placed = http.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(request, headers), new ParameterizedTypeReference<>() {
                });
        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(placed.getBody()).containsEntry("status", "RIDER_ASSIGNED");
        Object orderId = placed.getBody().get("id");

        // A retry with the same key returns the same order instead of creating a second one.
        ResponseEntity<Map<String, Object>> retried = http.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(request, headers), new ParameterizedTypeReference<>() {
                });
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody()).containsEntry("id", orderId);

        ResponseEntity<List<Map<String, Object>>> history = http.exchange("/api/customers/3/orders", HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {
                });
        assertThat(history.getBody()).extracting(o -> o.get("id")).containsExactly(orderId);

        ResponseEntity<List<Map<String, Object>>> invariants = http.exchange("/admin/invariants", HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {
                });
        assertThat(invariants.getBody()).allSatisfy(i -> assertThat(i.get("violations")).isEqualTo(0));
    }
}
