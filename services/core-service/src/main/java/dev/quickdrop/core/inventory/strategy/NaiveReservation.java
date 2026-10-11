package dev.quickdrop.core.inventory.strategy;

import java.util.List;

import dev.quickdrop.core.config.Chaos;
import dev.quickdrop.core.inventory.InventoryDao;
import dev.quickdrop.core.inventory.InventoryReservation;
import dev.quickdrop.core.inventory.OrderLine;
import dev.quickdrop.core.inventory.OutOfStockException;
import org.springframework.stereotype.Component;

/**
 * BROKEN — scenario 1.1. Read the stock, check it in Java, write the new value back.
 *
 * <p>Two requests both read 10, both decide there is enough, both write 9. Two units were sold,
 * the shelf says one was. This is a <em>lost update</em>. The stock never goes negative, so a
 * {@code CHECK (quantity >= 0)} constraint would not catch it either.
 */
@Component
public class NaiveReservation implements InventoryReservation {

    private final InventoryDao inventory;
    private final Chaos chaos;

    public NaiveReservation(InventoryDao inventory, Chaos chaos) {
        this.inventory = inventory;
        this.chaos = chaos;
    }

    @Override
    public String strategyName() {
        return "naive";
    }

    @Override
    public void reserve(long storeId, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            int available = inventory.quantity(storeId, line.productId())
                    .orElseThrow(() -> new OutOfStockException(storeId, line.productId()));
            if (available < line.quantity()) {
                throw new OutOfStockException(storeId, line.productId());
            }
            chaos.raceWindow();
            inventory.setQuantity(storeId, line.productId(), available - line.quantity());
            chaos.perLine();
        }
    }
}
