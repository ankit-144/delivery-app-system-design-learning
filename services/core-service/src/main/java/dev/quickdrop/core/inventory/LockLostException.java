package dev.quickdrop.core.inventory;

/** Our lock expired while we were working and someone newer already wrote: the fencing token said no. */
public class LockLostException extends RuntimeException {

    public LockLostException(long storeId, long productId, long fence) {
        super("Fencing token %d is stale for product %d in store %d: our lock expired and a newer holder wrote first"
                .formatted(fence, productId, storeId));
    }
}
