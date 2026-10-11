package dev.quickdrop.core.inventory;

/** Could not get the distributed stock lock in time (it's busy, or Redis is down). */
public class LockUnavailableException extends RuntimeException {

    public LockUnavailableException(String message) {
        super(message);
    }
}
