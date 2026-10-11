package dev.quickdrop.core.inventory;

/** The optimistic strategy kept losing version races and gave up. The client should retry later. */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(long storeId, long productId, int attempts) {
        super("Gave up reserving product %d in store %d after %d version conflicts".formatted(productId, storeId, attempts));
    }
}
