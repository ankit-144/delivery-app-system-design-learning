package dev.quickdrop.core.inventory.strategy;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.Chaos;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.inventory.InventoryDao;
import dev.quickdrop.core.inventory.InventoryReservation;
import dev.quickdrop.core.inventory.LockLostException;
import dev.quickdrop.core.inventory.LockUnavailableException;
import dev.quickdrop.core.inventory.OrderLine;
import dev.quickdrop.core.inventory.OutOfStockException;
import dev.quickdrop.core.redis.RedisLock;
import dev.quickdrop.core.redis.RedisUnavailable;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * FIXES 3.1, BREAKS IN ITS OWN WAY — scenario 3.2. The {@code synchronized} strategy, with the lock
 * moved from the JVM into Redis so every instance shares it. The naive read-check-write runs inside.
 *
 * <ul>
 *   <li>Lock expiry: a holder that freezes longer than {@code redis-lock-ttl-ms} loses the lock without
 *       knowing; a second holder enters, and the frozen one wakes up and writes stale data.</li>
 *   <li>{@code redis-lock-fencing=on}: every write carries the lease's fencing token and the database
 *       refuses tokens older than the last one written.</li>
 *   <li>{@code redis-failure-mode=degrade}: if Redis is unreachable, fall back to the database's own
 *       row lock ({@code SELECT … FOR UPDATE}) instead of failing the order (scenario 3.9).</li>
 * </ul>
 */
@Component
public class RedisLockReservation implements InventoryReservation {

    private static final ThreadLocal<RedisLock.Lease> LEASE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> FALLBACK = ThreadLocal.withInitial(() -> false);

    private final RedisLock lock;
    private final InventoryDao inventory;
    private final PessimisticReservation pessimistic;
    private final ToggleRegistry toggles;
    private final MeterRegistry meters;

    public RedisLockReservation(RedisLock lock, InventoryDao inventory, PessimisticReservation pessimistic,
                                ToggleRegistry toggles, MeterRegistry meters) {
        this.lock = lock;
        this.inventory = inventory;
        this.pessimistic = pessimistic;
        this.toggles = toggles;
        this.meters = meters;
        meters.counter("quickdrop.redis.lock.fallbacks");
        meters.counter("quickdrop.redis.lock.holder.pauses");
    }

    @Override
    public String strategyName() {
        return "redis-lock";
    }

    @Override
    public <T> T aroundTransaction(long storeId, Supplier<T> transaction) {
        RedisLock.Lease lease;
        try {
            lease = lock.acquire("stock:" + storeId, toggles.getLong(CoreToggles.REDIS_LOCK_TTL_MS),
                    toggles.getLong(CoreToggles.REDIS_LOCK_WAIT_MS))
                    .orElseThrow(() -> new LockUnavailableException("Stock lock for store " + storeId + " is busy"));
        } catch (RuntimeException e) {
            if (RedisUnavailable.is(e) && toggles.is(CoreToggles.REDIS_FAILURE_MODE, "degrade")) {
                meters.counter("quickdrop.redis.lock.fallbacks").increment();
                FALLBACK.set(true);
                try {
                    return transaction.get();
                } finally {
                    FALLBACK.remove();
                }
            }
            throw e;
        }
        LEASE.set(lease);
        try {
            return transaction.get();
        } finally {
            LEASE.remove();
            lock.release(lease);
        }
    }

    @Override
    public void reserve(long storeId, List<OrderLine> lines) {
        if (FALLBACK.get()) {
            pessimistic.reserve(storeId, lines);
            return;
        }
        RedisLock.Lease lease = LEASE.get();
        boolean fencing = toggles.is(CoreToggles.REDIS_LOCK_FENCING, "on");
        for (OrderLine line : lines) {
            int available = inventory.quantity(storeId, line.productId())
                    .orElseThrow(() -> new OutOfStockException(storeId, line.productId()));
            if (available < line.quantity()) {
                throw new OutOfStockException(storeId, line.productId());
            }
            maybeFreeze();
            int remaining = available - line.quantity();
            if (fencing) {
                if (!inventory.setQuantityFenced(storeId, line.productId(), remaining, lease.fence())) {
                    throw new LockLostException(storeId, line.productId(), lease.fence());
                }
            } else {
                inventory.setQuantity(storeId, line.productId(), remaining);
            }
        }
    }

    /** Simulates a stop-the-world GC pause (or a VM being descheduled) at the worst possible moment. */
    private void maybeFreeze() {
        if (ThreadLocalRandom.current().nextInt(100) < toggles.getLong(CoreToggles.LOCK_HOLDER_PAUSE_PERCENT)) {
            meters.counter("quickdrop.redis.lock.holder.pauses").increment();
            Chaos.sleep(toggles.getLong(CoreToggles.LOCK_HOLDER_PAUSE_MS));
        }
    }
}
