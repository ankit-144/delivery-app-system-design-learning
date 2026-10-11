package dev.quickdrop.core.order;

import java.util.List;

import java.time.Duration;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.catalog.CatalogService;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.redis.RedisLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orders placed when no rider was free sit in PENDING_RIDER. Every 2 seconds this job retries them.
 *
 * <p>It runs on every instance — scenario 3.6. {@code scheduler-lock} decides how they coordinate:
 * <ul>
 *   <li>{@code none}: every instance picks the same pending orders. Two instances assign two different
 *       riders to one order; the first rider stays BUSY forever for an order that isn't theirs.</li>
 *   <li>{@code redis}: only the instance holding a short Redis lock runs the job this round.</li>
 *   <li>{@code skip-locked}: every instance runs, but each claims orders with
 *       {@code SELECT … FOR UPDATE SKIP LOCKED}, so no two ever work on the same order.</li>
 * </ul>
 */
@Component
public class PendingRiderJob {

    private static final Logger log = LoggerFactory.getLogger(PendingRiderJob.class);

    private final JdbcClient jdbc;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final CatalogService catalog;
    private final ToggleRegistry toggles;
    private final TransactionTemplate tx;
    private final RedisLock lock;

    public PendingRiderJob(JdbcClient jdbc, OrderRepository orders, OrderService orderService, CatalogService catalog,
                           ToggleRegistry toggles, TransactionTemplate tx, RedisLock lock) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.orderService = orderService;
        this.catalog = catalog;
        this.toggles = toggles;
        this.tx = tx;
        this.lock = lock;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void retryPendingOrders() {
        if (toggles.is(CoreToggles.PENDING_RIDER_JOB, "off")) {
            return;
        }
        switch (toggles.get(CoreToggles.SCHEDULER_LOCK)) {
            case "redis" -> {
                try {
                    lock.tryOnce("job:pending-rider", Duration.ofMillis(1900)).ifPresent(lease -> {
                        try {
                            retryEach();
                        } finally {
                            lock.release(lease);
                        }
                    });
                } catch (RuntimeException e) {
                    log.warn("Pending-rider job skipped: {}", e.getMessage());
                }
            }
            case "skip-locked" -> claimAndRetry();
            default -> retryEach();
        }
    }

    /** Every instance claims different orders: the row lock is held until the assignment commits. */
    private void claimAndRetry() {
        for (int i = 0; i < 50; i++) {
            Boolean claimed = tx.execute(status -> {
                List<Long> ids = jdbc.sql("""
                                SELECT id FROM orders
                                WHERE status = 'PENDING_RIDER' AND created_at < now() - interval '2 seconds'
                                ORDER BY created_at LIMIT 1
                                FOR UPDATE SKIP LOCKED
                                """)
                        .query(Long.class)
                        .list();
                if (ids.isEmpty()) {
                    return false;
                }
                orders.findById(ids.get(0)).ifPresent(o -> orderService.assignRider(o, catalog.store(o.getStoreId())));
                return true;
            });
            if (!Boolean.TRUE.equals(claimed)) {
                return;
            }
        }
    }

    private void retryEach() {
        List<Long> pending = jdbc.sql("""
                        SELECT id FROM orders
                        WHERE status = 'PENDING_RIDER' AND created_at < now() - interval '2 seconds'
                        ORDER BY created_at LIMIT 50
                        """)
                .query(Long.class)
                .list();
        for (Long orderId : pending) {
            try {
                tx.executeWithoutResult(status -> orders.findById(orderId)
                        .filter(o -> o.getStatus() == OrderStatus.PENDING_RIDER)
                        .ifPresent(o -> orderService.assignRider(o, catalog.store(o.getStoreId()))));
            } catch (RuntimeException e) {
                log.warn("Retrying rider for order {} failed: {}", orderId, e.getMessage());
            }
        }
    }
}
