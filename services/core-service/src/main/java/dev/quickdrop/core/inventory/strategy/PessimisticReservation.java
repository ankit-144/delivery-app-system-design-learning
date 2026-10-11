package dev.quickdrop.core.inventory.strategy;

import java.util.List;

import dev.quickdrop.core.config.Chaos;
import dev.quickdrop.core.inventory.InventoryDao;
import dev.quickdrop.core.inventory.InventoryReservation;
import dev.quickdrop.core.inventory.OrderLine;
import dev.quickdrop.core.inventory.OutOfStockException;
import org.springframework.stereotype.Component;

/**
 * FIX — scenario 1.1. {@code SELECT ... FOR UPDATE} locks the stock row in Postgres until commit.
 * Works across any number of app instances because the lock lives in the database.
 *
 * <p>Cost: buyers of the same product queue up, each holding a DB connection while waiting.
 * With multi-item carts locked in different orders you get deadlocks (scenario 1.4).
 */
@Component
public class PessimisticReservation implements InventoryReservation {

    private final InventoryDao inventory;
    private final Chaos chaos;

    public PessimisticReservation(InventoryDao inventory, Chaos chaos) {
        this.inventory = inventory;
        this.chaos = chaos;
    }

    @Override
    public String strategyName() {
        return "pessimistic";
    }

    @Override
    public void reserve(long storeId, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            int available = inventory.quantityForUpdate(storeId, line.productId())
                    .orElseThrow(() -> new OutOfStockException(storeId, line.productId()));
            if (available < line.quantity()) {
                throw new OutOfStockException(storeId, line.productId());
            }
            inventory.setQuantity(storeId, line.productId(), available - line.quantity());
            chaos.perLine();
        }
    }
}
