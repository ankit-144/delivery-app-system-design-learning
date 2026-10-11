package dev.quickdrop.core.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rules that must always hold. Every 5 seconds each rule's SQL is run and the number of violations
 * is exported as {@code quickdrop_invariant_violations{invariant="..."}}. A correctness bug becomes
 * a red number on the Overview dashboard.
 *
 * <p>Only data since the last fixtures reset ({@code scenario_marker}) is checked, so old runs do not
 * pollute the current one. Note that this checker is itself a database workload — monitoring has a cost.
 */
@Component
public class InvariantChecker {

    private static final Logger log = LoggerFactory.getLogger(InvariantChecker.class);

    public record Invariant(String name, String description, String sql) {
    }

    public record Result(String name, String description, long violations, List<Map<String, Object>> samples) {
    }

    static final List<Invariant> INVARIANTS = List.of(
            new Invariant("oversold_units",
                    "Units sold beyond what was ever stocked (scenarios 1.1, 3.1).",
                    """
                    WITH recent AS (
                        SELECT id, store_id, created_at FROM orders
                        WHERE created_at >= :marker AND status <> 'CANCELLED'),
                    sold AS (
                        -- LATERAL: look each recent order's lines up by index. A plain join made the planner scan
                        -- all 2.9M order_items rows every 5 s on every instance (the checker cost half a CPU).
                        SELECT r.store_id, oi.product_id, sum(oi.quantity) AS sold
                        FROM recent r
                                 CROSS JOIN LATERAL (SELECT product_id, quantity FROM order_items WHERE order_id = r.id) oi
                                 JOIN inventory i ON i.store_id = r.store_id AND i.product_id = oi.product_id
                        WHERE i.stock_reset_at >= :marker AND r.created_at >= i.stock_reset_at
                        GROUP BY r.store_id, oi.product_id)
                    SELECT i.store_id, i.product_id, i.total_stocked, i.quantity, s.sold,
                           s.sold - i.total_stocked AS violations
                    FROM inventory i JOIN sold s ON s.store_id = i.store_id AND s.product_id = i.product_id
                    WHERE s.sold > i.total_stocked
                    """),
            new Invariant("stock_mismatch",
                    "Rows where stocked - remaining != sold: the books do not balance (lost updates).",
                    """
                    WITH recent AS (
                        SELECT id, store_id, created_at FROM orders
                        WHERE created_at >= :marker AND status <> 'CANCELLED'),
                    sold AS (
                        -- LATERAL: look each recent order's lines up by index. A plain join made the planner scan
                        -- all 2.9M order_items rows every 5 s on every instance (the checker cost half a CPU).
                        SELECT r.store_id, oi.product_id, sum(oi.quantity) AS sold
                        FROM recent r
                                 CROSS JOIN LATERAL (SELECT product_id, quantity FROM order_items WHERE order_id = r.id) oi
                                 JOIN inventory i ON i.store_id = r.store_id AND i.product_id = oi.product_id
                        WHERE i.stock_reset_at >= :marker AND r.created_at >= i.stock_reset_at
                        GROUP BY r.store_id, oi.product_id)
                    SELECT i.store_id, i.product_id, i.total_stocked, i.quantity, coalesce(s.sold, 0) AS sold, 1 AS violations
                    FROM inventory i LEFT JOIN sold s ON s.store_id = i.store_id AND s.product_id = i.product_id
                    WHERE i.stock_reset_at >= :marker
                      AND i.total_stocked - i.quantity <> coalesce(s.sold, 0)
                    """),
            new Invariant("negative_stock",
                    "Inventory rows below zero.",
                    "SELECT store_id, product_id, quantity, 1 AS violations FROM inventory WHERE quantity < 0"),
            new Invariant("rider_double_booked",
                    "Riders who were assigned overlapping orders since the scenario started (scenarios 1.2, 3.6).",
                    """
                    WITH recent AS (
                        SELECT id, rider_id, assigned_at, coalesce(delivered_at, 'infinity') AS done_at
                        FROM orders
                        WHERE assigned_at >= :marker),
                    ordered AS (
                        -- An assignment overlaps if it starts before the rider's earlier assignments are done.
                        -- (A window function, not a self-join: the self-join grew quadratically per rider.)
                        SELECT rider_id, assigned_at,
                               max(done_at) OVER (PARTITION BY rider_id ORDER BY assigned_at, id
                                                  ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS earlier_done_at
                        FROM recent)
                    SELECT rider_id, count(*) AS overlapping_assignments, 1 AS violations
                    FROM ordered
                    WHERE assigned_at < earlier_done_at
                    GROUP BY rider_id
                    """),
            new Invariant("rider_ghost_busy",
                    "Riders marked BUSY for an order that has a different rider or is already finished (scenario 3.6).",
                    """
                    SELECT r.id AS rider_id, r.current_order_id, o.rider_id AS order_rider, o.status, 1 AS violations
                    FROM riders r LEFT JOIN orders o ON o.id = r.current_order_id
                    WHERE r.status = 'BUSY' AND (o.id IS NULL OR o.rider_id IS DISTINCT FROM r.id OR o.status <> 'RIDER_ASSIGNED')
                    """),
            new Invariant("duplicate_orders",
                    "Orders created twice for the same Idempotency-Key since the scenario started (scenario 1.3).",
                    """
                    SELECT customer_id, client_request_id, count(*) AS copies, count(*) - 1 AS violations
                    FROM orders
                    WHERE client_request_id IS NOT NULL AND created_at >= :marker
                    GROUP BY customer_id, client_request_id
                    HAVING count(*) > 1
                    """));

    private final JdbcClient jdbc;
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public InvariantChecker(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        for (Invariant invariant : INVARIANTS) {
            AtomicLong value = new AtomicLong();
            gauges.put(invariant.name(), value);
            Gauge.builder("quickdrop.invariant.violations", value, AtomicLong::get)
                    .description("Violations of a business invariant; should always be 0")
                    .tag("invariant", invariant.name())
                    .register(meters);
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refresh() {
        check(0);
    }

    public List<Result> check(int sampleSize) {
        // Read the marker once and pass its value. As a subquery, "created_at >= (SELECT started_at ...)" hides
        // the value from the planner, which then scans all 1.5M orders instead of using the index (41 ms vs 0.7 ms).
        java.sql.Timestamp marker = jdbc.sql("SELECT started_at FROM scenario_marker").query(java.sql.Timestamp.class).single();
        return INVARIANTS.stream().map(invariant -> {
            try {
                var statement = jdbc.sql(invariant.sql());
                if (invariant.sql().contains(":marker")) {
                    statement = statement.param("marker", marker);
                }
                List<Map<String, Object>> rows = statement.query().listOfRows();
                long violations = rows.stream().mapToLong(r -> ((Number) r.get("violations")).longValue()).sum();
                gauges.get(invariant.name()).set(violations);
                List<Map<String, Object>> samples = rows.stream().limit(sampleSize).map(LinkedHashMap::new)
                        .map(m -> (Map<String, Object>) m).toList();
                return new Result(invariant.name(), invariant.description(), violations, samples);
            } catch (RuntimeException e) {
                log.warn("Invariant {} could not be checked: {}", invariant.name(), e.getMessage());
                return new Result(invariant.name(), invariant.description(), -1, List.of());
            }
        }).toList();
    }
}
