package dev.quickdrop.core.inventory.strategy;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.Chaos;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.inventory.ConcurrentUpdateException;
import dev.quickdrop.core.inventory.InventoryDao;
import dev.quickdrop.core.inventory.InventoryReservation;
import dev.quickdrop.core.inventory.OrderLine;
import dev.quickdrop.core.inventory.OutOfStockException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * FIX WITH A CATCH — scenarios 1.1 and 1.5. Read the row with its version, write only if the
 * version is unchanged, otherwise re-read and retry.
 *
 * <p>No locks are held while thinking, which is great when conflicts are rare. On a hot product
 * (flash sale) almost every attempt conflicts: the retries are wasted work, latency climbs and
 * requests give up. Watch {@code quickdrop_inventory_conflicts_total}.
 */
@Component
public class OptimisticReservation implements InventoryReservation {

    private final InventoryDao inventory;
    private final ToggleRegistry toggles;
    private final Chaos chaos;
    private final Counter conflicts;
    private final Counter gaveUp;

    public OptimisticReservation(InventoryDao inventory, ToggleRegistry toggles, Chaos chaos, MeterRegistry meters) {
        this.inventory = inventory;
        this.toggles = toggles;
        this.chaos = chaos;
        this.conflicts = Counter.builder("quickdrop.inventory.conflicts")
                .description("Optimistic version conflicts (each one is a wasted attempt)")
                .register(meters);
        this.gaveUp = Counter.builder("quickdrop.inventory.conflicts.exhausted")
                .description("Reservations abandoned after too many version conflicts")
                .register(meters);
    }

    @Override
    public String strategyName() {
        return "optimistic";
    }

    @Override
    public void reserve(long storeId, List<OrderLine> lines) {
        long maxRetries = toggles.getLong(CoreToggles.OPTIMISTIC_MAX_RETRIES);
        for (OrderLine line : lines) {
            int attempt = 0;
            while (true) {
                InventoryDao.Versioned row = inventory.versioned(storeId, line.productId())
                        .orElseThrow(() -> new OutOfStockException(storeId, line.productId()));
                if (row.quantity() < line.quantity()) {
                    throw new OutOfStockException(storeId, line.productId());
                }
                chaos.raceWindow();
                if (inventory.compareAndSet(storeId, line.productId(), row.quantity() - line.quantity(), row.version())) {
                    chaos.perLine();
                    break;
                }
                conflicts.increment();
                if (++attempt > maxRetries) {
                    gaveUp.increment();
                    throw new ConcurrentUpdateException(storeId, line.productId(), attempt);
                }
                // Small random backoff so the retries do not collide again in lockstep.
                Chaos.sleep(ThreadLocalRandom.current().nextLong(1, 5L * attempt + 1));
            }
        }
    }
}
