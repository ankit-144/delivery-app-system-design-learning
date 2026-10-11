package dev.quickdrop.core.ratelimit;

import dev.quickdrop.common.toggles.NamedStrategy;

/** One rate limiting algorithm. Selected by the {@code rate-limiter} toggle. */
public interface RateLimiter extends NamedStrategy {

    /** Records one attempt for {@code key}; true if it is within {@code limit} per minute. */
    boolean tryAcquire(String key, int limit);
}
