package dev.quickdrop.core.inventory;

/** One line of a cart: how many units of which product. */
public record OrderLine(long productId, int quantity) {
}
