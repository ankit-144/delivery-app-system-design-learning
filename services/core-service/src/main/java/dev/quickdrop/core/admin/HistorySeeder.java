package dev.quickdrop.core.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A year of delivered orders, so the "my orders" query has a realistically big table to search
 * (scenarios 2.3–2.5). About 50 orders per customer. Idempotent: does nothing if enough orders exist.
 */
@Service
public class HistorySeeder {

    private static final Logger log = LoggerFactory.getLogger(HistorySeeder.class);

    private final JdbcClient jdbc;

    public HistorySeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> seed(int target) {
        Map<String, Object> result = new LinkedHashMap<>();
        long existing = jdbc.sql("SELECT count(*) FROM orders").query(Long.class).single();
        result.put("ordersBefore", existing);
        if (existing >= target) {
            result.put("seeded", 0);
            return result;
        }
        long start = System.currentTimeMillis();
        long firstNewId = jdbc.sql("SELECT coalesce(max(id), 0) + 1 FROM orders").query(Long.class).single();

        // Bulk load: skip foreign-key triggers for this transaction only (the data is generated consistently).
        jdbc.sql("SET LOCAL session_replication_role = replica").update();
        int orders = jdbc.sql("""
                        INSERT INTO orders (customer_id, store_id, status, total_paise, rider_id, created_at, updated_at, assigned_at, delivered_at)
                        SELECT c.customer_id, c.store_id, 'DELIVERED', 5000 + (random() * 50000)::int,
                               6 * (1 + (random() * 998)::int) + (c.store_id - 1),
                               c.t, c.t, c.t, c.t + interval '18 minutes'
                        FROM (SELECT g.customer_id,
                                     2 * (g.customer_id % 3) + 1 + (random() < 0.5)::int AS store_id,
                                     now() - interval '1 hour' - random() * interval '365 days' AS t
                              FROM (SELECT 1 + (random() * 29999)::int AS customer_id FROM generate_series(1, :n)) g) c
                        """)
                .param("n", target - existing)
                .update();
        int items = jdbc.sql("""
                        INSERT INTO order_items (order_id, product_id, quantity, price_paise)
                        SELECT o.id, 1 + (random() * 499)::int, 1 + (random() * 2)::int, 1000 + (random() * 49000)::int
                        FROM orders o CROSS JOIN LATERAL generate_series(1, 1 + (o.id % 3)) AS line
                        WHERE o.id >= :first
                        """)
                .param("first", firstNewId)
                .update();
        result.put("seeded", orders);
        result.put("items", items);
        result.put("seconds", (System.currentTimeMillis() - start) / 1000.0);
        log.info("Seeded order history: {}", result);
        return result;
    }

    /** Fresh planner statistics after a bulk load; run outside the seeding transaction. */
    public void analyze() {
        jdbc.sql("ANALYZE orders").update();
        jdbc.sql("ANALYZE order_items").update();
    }
}
