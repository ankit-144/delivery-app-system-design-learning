package dev.quickdrop.core.order;

import java.util.List;
import java.util.Map;

import dev.quickdrop.core.ratelimit.RateLimitService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orders;
    private final OrderHistoryService history;
    private final RateLimitService rateLimits;
    private final JdbcClient jdbc;

    public OrderController(OrderService orders, OrderHistoryService history, RateLimitService rateLimits, JdbcClient jdbc) {
        this.orders = orders;
        this.history = history;
        this.rateLimits = rateLimits;
        this.jdbc = jdbc;
    }

    /** Rate a delivered order, 1-5 stars. A single-row UPDATE on an old order (scenario 4.5). */
    @PostMapping("/api/orders/{id}/rating")
    public ResponseEntity<Map<String, Object>> rate(@PathVariable long id, @RequestBody Map<String, Integer> body) {
        int stars = body.getOrDefault("stars", 5);
        int updated = jdbc.sql("UPDATE orders SET rating = ?, updated_at = now() WHERE id = ? AND status = 'DELIVERED'")
                .params(stars, id)
                .update();
        return updated == 1 ? ResponseEntity.ok(Map.of("orderId", id, "rating", stars)) : ResponseEntity.notFound().build();
    }

    @PostMapping("/api/orders")
    public ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest request,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        rateLimits.check(RateLimitService.Endpoint.ORDERS, request.customerId().toString());
        PlacedOrder placed = orders.placeOrder(request, idempotencyKey);
        return ResponseEntity.status(placed.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", Boolean.toString(placed.replayed()))
                .body(placed.order());
    }

    @GetMapping("/api/orders/{id}")
    public OrderView get(@PathVariable long id) {
        return orders.get(id);
    }

    @GetMapping("/api/customers/{customerId}/orders")
    public List<OrderView> history(@PathVariable long customerId, @RequestParam(defaultValue = "20") int limit) {
        return history.history(customerId, Math.min(limit, 100));
    }
}
