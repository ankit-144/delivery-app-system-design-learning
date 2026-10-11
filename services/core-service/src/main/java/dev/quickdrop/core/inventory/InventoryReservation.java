package dev.quickdrop.core.inventory;

import java.util.List;
import java.util.function.Supplier;

import dev.quickdrop.common.toggles.NamedStrategy;

/**
 * One way of taking stock off the shelf for an order. Selected by the {@code inventory-reservation} toggle.
 *
 * <p>{@link #reserve} runs inside the order's database transaction. Strategies that lock outside the
 * database (a JVM lock, later a Redis lock) override {@link #aroundTransaction} so the lock is held
 * until the transaction has <em>committed</em>, not just until {@code reserve} returns.
 */
public interface InventoryReservation extends NamedStrategy {

    /** Reserves every line or throws {@link OutOfStockException}. Lines arrive in the order they must be locked. */
    void reserve(long storeId, List<OrderLine> lines);

    default <T> T aroundTransaction(long storeId, Supplier<T> transaction) {
        return transaction.get();
    }
}
