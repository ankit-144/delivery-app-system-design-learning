package dev.quickdrop.core.inventory.strategy;

import java.util.List;

import dev.quickdrop.core.config.Chaos;
import dev.quickdrop.core.inventory.InventoryDao;
import dev.quickdrop.core.inventory.InventoryReservation;
import dev.quickdrop.core.inventory.OrderLine;
import dev.quickdrop.core.inventory.OutOfStockException;
import org.springframework.stereotype.Component;

/**
 * FIX — scenario 1.1. {@code UPDATE inventory SET quantity = quantity - ? WHERE ... AND quantity >= ?}.
 *
 * <p>The check and the write are one statement, so there is no gap to race in. The row lock is
 * held only from this statement to commit. Usually the best answer for a simple counter.
 */
@Component
public class AtomicReservation implements InventoryReservation {

    private final InventoryDao inventory;
    private final Chaos chaos;

    public AtomicReservation(InventoryDao inventory, Chaos chaos) {
        this.inventory = inventory;
        this.chaos = chaos;
    }

    @Override
    public String strategyName() {
        return "atomic";
    }

    @Override
    public void reserve(long storeId, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            if (!inventory.decrementIfAvailable(storeId, line.productId(), line.quantity())) {
                throw new OutOfStockException(storeId, line.productId());
            }
            chaos.perLine();
        }
    }
}
