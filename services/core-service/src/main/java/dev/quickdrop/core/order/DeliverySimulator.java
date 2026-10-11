package dev.quickdrop.core.order;

import dev.quickdrop.common.toggles.StrategySelector;
import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.rider.RiderLocator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pretend riders: an order is "delivered" {@code auto-deliver-after-ms} after a rider was assigned,
 * and the rider becomes available again. Keeps the rider pool from draining during long load tests.
 */
@Component
public class DeliverySimulator {

    private final JdbcClient jdbc;
    private final ToggleRegistry toggles;
    private final StrategySelector<RiderLocator> locators;
    private final Counter delivered;

    public DeliverySimulator(JdbcClient jdbc, ToggleRegistry toggles, StrategySelector<RiderLocator> locators, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.toggles = toggles;
        this.locators = locators;
        this.delivered = Counter.builder("quickdrop.orders.delivered").register(meters);
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void deliver() {
        long afterMs = toggles.getLong(CoreToggles.AUTO_DELIVER_AFTER_MS);
        if (afterMs <= 0) {
            return;
        }
        record Freed(long id, short cityId, double lat, double lon) {
        }
        java.util.List<Freed> freed = jdbc.sql("""
                        WITH done AS (
                            UPDATE orders SET status = 'DELIVERED', delivered_at = now(), updated_at = now()
                            WHERE id IN (SELECT id FROM orders
                                         WHERE status = 'RIDER_ASSIGNED' AND assigned_at < now() - make_interval(secs => :secs)
                                         LIMIT 1000 FOR UPDATE SKIP LOCKED)
                            RETURNING id, rider_id
                        )
                        UPDATE riders r SET status = 'AVAILABLE', current_order_id = NULL
                        FROM done WHERE r.id = done.rider_id AND r.current_order_id = done.id
                        RETURNING r.id, r.city_id, r.lat, r.lon
                        """)
                .param("secs", afterMs / 1000.0)
                .query((rs, n) -> new Freed(rs.getLong(1), rs.getShort(2), rs.getDouble(3), rs.getDouble(4)))
                .list();
        RiderLocator locator = locators.current();
        freed.forEach(r -> locator.onRiderAvailable(r.id(), r.cityId(), r.lat(), r.lon()));
        delivered.increment(freed.size());
    }
}
