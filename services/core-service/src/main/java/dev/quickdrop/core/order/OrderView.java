package dev.quickdrop.core.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record OrderView(long id, long customerId, long storeId, OrderStatus status, int totalPaise, Long riderId,
                        Instant createdAt, List<Item> items) {

    public record Item(long productId, String name, int quantity, int pricePaise) {
    }

    /** For a freshly placed order: product names come from the snapshots we already have, no extra queries. */
    public static OrderView of(Order order, Map<Long, String> productNames) {
        List<Item> items = order.getItems().stream()
                .map(i -> {
                    long productId = i.getProduct().getId();
                    return new Item(productId, productNames.get(productId), i.getQuantity(), i.getPricePaise());
                })
                .toList();
        return new OrderView(order.getId(), order.getCustomerId(), order.getStoreId(), order.getStatus(),
                order.getTotalPaise(), order.getRiderId(), order.getCreatedAt(), items);
    }

    /** Touches every item and its product — with lazy loading that is one query per item (scenario 2.5). */
    public static OrderView of(Order order) {
        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getProduct().getId(), i.getProduct().getName(), i.getQuantity(), i.getPricePaise()))
                .toList();
        return new OrderView(order.getId(), order.getCustomerId(), order.getStoreId(), order.getStatus(),
                order.getTotalPaise(), order.getRiderId(), order.getCreatedAt(), items);
    }
}
