package dev.quickdrop.core.inventory;

public class OutOfStockException extends RuntimeException {

    private final long productId;

    public OutOfStockException(long storeId, long productId) {
        super("Product %d is out of stock in store %d".formatted(productId, storeId));
        this.productId = productId;
    }

    public long productId() {
        return productId;
    }
}
