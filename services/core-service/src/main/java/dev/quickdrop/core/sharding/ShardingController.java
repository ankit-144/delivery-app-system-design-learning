package dev.quickdrop.core.sharding;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * POST /api/sharded/orders                   {"customerId": 3, "storeId": 1, "totalPaise": 25000}
 * GET  /api/sharded/orders/{id}
 * GET  /api/sharded/customers/{id}/orders    "My orders"
 * GET  /api/sharded/stores/{id}/orders       the store's order screen
 * POST /api/sharded/orders/{id}/move?storeId=5
 * </pre>
 */
@RestController
public class ShardingController {

    private final ShardedOrderService orders;

    public ShardingController(ShardedOrderService orders) {
        this.orders = orders;
    }

    public record NewOrder(long customerId, long storeId, int totalPaise) {
    }

    @PostMapping("/api/sharded/orders")
    public ResponseEntity<ShardedOrderService.ShardedOrder> place(@RequestBody NewOrder order) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orders.place(order.customerId(), order.storeId(), order.totalPaise()));
    }

    @GetMapping("/api/sharded/orders/{id}")
    public ShardedOrderService.Answer<List<ShardedOrderService.ShardedOrder>> get(@PathVariable long id) {
        return orders.findById(id);
    }

    @GetMapping("/api/sharded/customers/{id}/orders")
    public ShardedOrderService.Answer<List<ShardedOrderService.ShardedOrder>> customer(@PathVariable long id,
                                                                                       @RequestParam(defaultValue = "20") int limit) {
        return orders.customerOrders(id, limit);
    }

    @GetMapping("/api/sharded/stores/{id}/orders")
    public ShardedOrderService.Answer<List<ShardedOrderService.ShardedOrder>> store(@PathVariable long id,
                                                                                    @RequestParam(defaultValue = "20") int limit) {
        return orders.storeOrders(id, limit);
    }

    @PostMapping("/api/sharded/orders/{id}/move")
    public Map<String, Object> move(@PathVariable long id, @RequestParam long storeId) {
        return orders.move(id, storeId);
    }

    @ExceptionHandler(ShardedOrderService.CrossShardCrash.class)
    ResponseEntity<Map<String, String>> crashed(ShardedOrderService.CrossShardCrash e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "crashed_mid_move", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> off(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "sharding_unavailable", "message", e.getMessage()));
    }
}
