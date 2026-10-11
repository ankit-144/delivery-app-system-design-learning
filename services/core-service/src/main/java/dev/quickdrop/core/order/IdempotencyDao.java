package dev.quickdrop.core.order;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Scenario 1.3. The first request with a key inserts it; a retry with the same key finds it.
 *
 * <p>If two copies of the request arrive at the same time, the second INSERT waits on the primary
 * key until the first transaction commits (then does nothing) or rolls back (then inserts). The
 * database, not our Java code, decides who goes first.
 */
@Repository
public class IdempotencyDao {

    private final JdbcClient jdbc;

    public IdempotencyDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(long customerId, String key) {
        return jdbc.sql("INSERT INTO idempotency_keys (customer_id, idempotency_key) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(customerId, key)
                .update() == 1;
    }

    public void attachOrder(long customerId, String key, long orderId) {
        jdbc.sql("UPDATE idempotency_keys SET order_id = ? WHERE customer_id = ? AND idempotency_key = ?")
                .params(orderId, customerId, key)
                .update();
    }

    public Optional<Long> orderFor(long customerId, String key) {
        return jdbc.sql("SELECT order_id FROM idempotency_keys WHERE customer_id = ? AND idempotency_key = ?")
                .params(customerId, key)
                .query(Long.class)
                .optional();
    }
}
