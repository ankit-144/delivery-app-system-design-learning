package dev.quickdrop.core.order;

import java.util.List;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.db.ReadRouter;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "My orders" screen — scenario 2.5 (N+1 queries) and 2.4 (missing index on customer_id).
 *
 * <ul>
 *   <li>{@code naive}: 1 query for the orders, then lazy loading fires 1 query per order for its items
 *       and 1 per distinct product. 20 orders → 40-60 queries.</li>
 *   <li>{@code two-step}: 1 query for the page of ids, 1 query that fetch-joins items and products.
 *       (A single fetch-join query with a LIMIT would make Hibernate paginate in memory — it loads every
 *       order of the customer and warns HHH90003004.)</li>
 * </ul>
 */
@Service
public class OrderHistoryService {

    private final OrderRepository orders;
    private final ToggleRegistry toggles;
    private final ReadRouter router;
    private final TransactionTemplate readOnlyTx;

    public OrderHistoryService(OrderRepository orders, ToggleRegistry toggles, ReadRouter router, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.toggles = toggles;
        this.router = router;
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /** The routing decision (primary or replica, scenario 5.1) is made before the transaction starts. */
    public List<OrderView> history(long customerId, int limit) {
        return router.read(customerId, () -> readOnlyTx.execute(status -> load(customerId, limit)));
    }

    private List<OrderView> load(long customerId, int limit) {
        if (toggles.is(CoreToggles.ORDER_HISTORY_QUERY, "two-step")) {
            List<Long> ids = orders.findIdsByCustomer(customerId, Limit.of(limit));
            return ids.isEmpty() ? List.of() : orders.findWithItems(ids).stream().map(OrderView::of).toList();
        }
        return orders.findByCustomerIdOrderByCreatedAtDesc(customerId, Limit.of(limit)).stream().map(OrderView::of).toList();
    }
}
