package dev.quickdrop.core.order;

/** @param replayed true when an earlier order was returned for a repeated Idempotency-Key */
public record PlacedOrder(OrderView order, String customerPhone, boolean replayed) {
}
