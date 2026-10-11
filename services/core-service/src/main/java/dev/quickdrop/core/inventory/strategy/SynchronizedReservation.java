package dev.quickdrop.core.inventory.strategy;

import java.util.List;
import java.util.function.Supplier;

import dev.quickdrop.core.inventory.InventoryReservation;
import dev.quickdrop.core.inventory.OrderLine;
import org.springframework.stereotype.Component;

/**
 * WORKS ON ONE INSTANCE — scenario 1.1, breaks in 3.1. The JVM lock is held around the whole
 * transaction, including the commit, so the naive read-check-write is safe... as long as every
 * request for this store reaches this JVM.
 *
 * <p>Cost: all orders for a store are serialised, including the parts that have nothing to do
 * with stock (rider matching, inserts, notifications in inline-in-tx mode).
 */
@Component
public class SynchronizedReservation implements InventoryReservation {

    private final NaiveReservation naive;
    private final JvmLocks locks;

    public SynchronizedReservation(NaiveReservation naive, JvmLocks locks) {
        this.naive = naive;
        this.locks = locks;
    }

    @Override
    public String strategyName() {
        return "synchronized";
    }

    @Override
    public <T> T aroundTransaction(long storeId, Supplier<T> transaction) {
        synchronized (locks.forStore(storeId)) {
            return transaction.get();
        }
    }

    @Override
    public void reserve(long storeId, List<OrderLine> lines) {
        naive.reserve(storeId, lines);
    }
}
