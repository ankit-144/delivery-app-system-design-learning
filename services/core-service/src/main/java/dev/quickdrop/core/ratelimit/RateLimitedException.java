package dev.quickdrop.core.ratelimit;

public class RateLimitedException extends RuntimeException {

    public RateLimitedException(String endpoint, String key) {
        super("Too many %s requests for %s".formatted(endpoint, key));
    }
}
