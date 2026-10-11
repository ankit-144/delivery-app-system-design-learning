package dev.quickdrop.core.inventory.strategy;

import java.util.List;

import dev.quickdrop.core.inventory.InventoryReservation;
import dev.quickdrop.core.inventory.OrderLine;
import org.springframework.stereotype.Component;

/**
 * STILL BROKEN — scenario 1.1. The naive read-check-write wrapped in a {@code synchronized} block.
 *
 * <p>The lock is released when {@code reserve} returns, but the transaction commits later. The next
 * thread enters, reads the <em>old committed</em> value (our write is not visible yet), and the lost
 * update is back — just less often. This is the classic "@Transactional + synchronized" bug.
 */
@Component
public class SynchronizedInTxReservation implements InventoryReservation {

    private final NaiveReservation naive;
    private final JvmLocks locks;

    public SynchronizedInTxReservation(NaiveReservation naive, JvmLocks locks) {
        this.naive = naive;
        this.locks = locks;
    }

    @Override
    public String strategyName() {
        return "synchronized-in-tx";
    }

    @Override
    public void reserve(long storeId, List<OrderLine> lines) {
        synchronized (locks.forStore(storeId)) {
            naive.reserve(storeId, lines);
        }
    }
}
