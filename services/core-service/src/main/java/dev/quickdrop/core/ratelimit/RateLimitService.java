package dev.quickdrop.core.ratelimit;

import java.util.List;

import dev.quickdrop.common.toggles.StrategySelector;
import dev.quickdrop.common.toggles.ToggleDefinition;
import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.redis.RedisUnavailable;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Applies the current rate limiter to an endpoint. When Redis is unreachable and
 * {@code redis-failure-mode=degrade}, each endpoint decides for itself (scenario 3.9):
 * orders <b>fail open</b> (let customers buy; a few extra orders are fine), OTPs <b>fail closed</b>
 * (every OTP costs money and an open OTP endpoint is exactly what bots look for).
 */
@Service
public class RateLimitService {

    public enum Endpoint {
        OTP("otp", CoreToggles.RATE_LIMIT_OTP_PER_MINUTE, false),
        ORDERS("orders", CoreToggles.RATE_LIMIT_ORDERS_PER_MINUTE, true);

        final String name;
        final ToggleDefinition limit;
        final boolean failOpen;

        Endpoint(String name, ToggleDefinition limit, boolean failOpen) {
            this.name = name;
            this.limit = limit;
            this.failOpen = failOpen;
        }
    }

    private final StrategySelector<RateLimiter> limiters;
    private final ToggleRegistry toggles;
    private final MeterRegistry meters;

    public RateLimitService(ToggleRegistry toggles, List<RateLimiter> all, MeterRegistry meters) {
        this.limiters = new StrategySelector<>(toggles, CoreToggles.RATE_LIMITER, all);
        this.toggles = toggles;
        this.meters = meters;
        for (Endpoint endpoint : Endpoint.values()) {
            for (String result : List.of("allowed", "limited", "redis_down_allowed", "redis_down_denied")) {
                meters.counter("quickdrop.rate.limit", "endpoint", endpoint.name, "result", result);
            }
        }
    }

    public void check(Endpoint endpoint, String key) {
        boolean allowed;
        String result;
        try {
            allowed = limiters.current().tryAcquire(endpoint.name + ":" + key, (int) toggles.getLong(endpoint.limit));
            result = allowed ? "allowed" : "limited";
        } catch (RuntimeException e) {
            if (!RedisUnavailable.is(e) || toggles.is(CoreToggles.REDIS_FAILURE_MODE, "fail")) {
                throw e;
            }
            allowed = endpoint.failOpen;
            result = allowed ? "redis_down_allowed" : "redis_down_denied";
        }
        meters.counter("quickdrop.rate.limit", "endpoint", endpoint.name, "result", result).increment();
        if (!allowed) {
            throw new RateLimitedException(endpoint.name, key);
        }
    }
}
