package dev.quickdrop.core.order;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.inventory.OrderLine;
import org.springframework.stereotype.Component;

/**
 * Decides the order in which a cart's stock rows get locked (scenario 1.4).
 *
 * <p>Cart A = [milk, bread], cart B = [bread, milk]. A locks milk and waits for bread, B locks bread
 * and waits for milk: deadlock. Postgres detects it after {@code deadlock_timeout} and kills one of
 * them. Locking in one global order (sorted by product id) makes the cycle impossible.
 */
@Component
public class LineOrdering {

    private final ToggleRegistry toggles;

    public LineOrdering(ToggleRegistry toggles) {
        this.toggles = toggles;
    }

    public List<OrderLine> apply(List<PlaceOrderRequest.Line> requested) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (PlaceOrderRequest.Line line : requested) {
            merged.merge(line.productId(), line.quantity(), Integer::sum);
        }
        List<OrderLine> lines = merged.entrySet().stream().map(e -> new OrderLine(e.getKey(), e.getValue())).toList();
        if (toggles.is(CoreToggles.INVENTORY_LOCK_ORDER, "sorted")) {
            return lines.stream().sorted(Comparator.comparingLong(OrderLine::productId)).toList();
        }
        return lines;
    }
}
