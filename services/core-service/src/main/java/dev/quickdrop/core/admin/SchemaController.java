package dev.quickdrop.core.admin;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin shortcuts for scenarios that change the schema.
 *
 * <p>In real life the index in scenario 2.4 would ship as a Flyway migration (Phase 4 does exactly
 * that, under load). Here a scenario needs to add and remove it repeatedly, so it gets a switch.
 *
 * <pre>
 * curl -X POST 'localhost:8081/admin/schema/orders-customer-index?action=create'
 * curl -X POST 'localhost:8081/admin/schema/orders-customer-index?action=drop'
 * curl -X POST 'localhost:8081/admin/fixtures/history?orders=1500000'
 * </pre>
 */
@RestController
public class SchemaController {

    private static final String INDEX = "orders_customer_created_idx";

    private final JdbcClient jdbc;
    private final HistorySeeder history;

    public SchemaController(JdbcClient jdbc, HistorySeeder history) {
        this.jdbc = jdbc;
        this.history = history;
    }

    @PostMapping("/admin/schema/orders-customer-index")
    public Map<String, Object> customerIndex(@RequestParam String action) {
        long start = System.currentTimeMillis();
        // CONCURRENTLY: build without blocking writes. Must run outside a transaction (no @Transactional here).
        switch (action) {
            case "create" -> jdbc.sql("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + INDEX + " ON orders (customer_id, created_at DESC)").update();
            case "drop" -> jdbc.sql("DROP INDEX CONCURRENTLY IF EXISTS " + INDEX).update();
            default -> throw new IllegalArgumentException("action must be create or drop");
        }
        return Map.of("index", INDEX, "action", action, "exists", indexExists(), "seconds", (System.currentTimeMillis() - start) / 1000.0);
    }

    @GetMapping("/admin/schema/orders-customer-index")
    public Map<String, Object> customerIndexStatus() {
        return Map.of("index", INDEX, "exists", indexExists());
    }

    @PostMapping("/admin/fixtures/history")
    public Map<String, Object> seedHistory(@RequestParam(defaultValue = "1500000") int orders) {
        Map<String, Object> result = history.seed(orders);
        history.analyze();
        return result;
    }

    private boolean indexExists() {
        return jdbc.sql("SELECT count(*) FROM pg_indexes WHERE indexname = ?").param(INDEX).query(Integer.class).single() > 0;
    }
}
