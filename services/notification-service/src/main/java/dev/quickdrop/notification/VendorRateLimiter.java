package dev.quickdrop.notification;

import java.util.List;

import dev.quickdrop.common.toggles.ToggleRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Keeps us under the SMS vendor's rate limit — scenario 6.7. Blocks the sender until a token is free, so
 * a too-fast backlog turns into consumer lag (which Kafka holds safely) instead of HTTP 429s.
 *
 * <ul>
 *   <li>{@code local}: a token bucket in this JVM. Two instances each allow the full rate: twice the limit.</li>
 *   <li>{@code redis}: one token bucket in Redis shared by every instance (the Lua script from 3.5).</li>
 * </ul>
 */
@Component
public class VendorRateLimiter {

    private static final RedisScript<Long> TAKE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local rate = tonumber(ARGV[1])
            local capacity = rate
            local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(b[1]) or capacity
            local ts = tonumber(b[2]) or now
            tokens = math.min(capacity, tokens + (now - ts) * rate / 1000)
            local ok = 0
            if tokens >= 1 then tokens = tokens - 1; ok = 1 end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', KEYS[1], 10000)
            return ok
            """, Long.class);

    private final ToggleRegistry toggles;
    private final StringRedisTemplate redis;
    private double localTokens;
    private long localTs = System.currentTimeMillis();

    public VendorRateLimiter(ToggleRegistry toggles, StringRedisTemplate redis) {
        this.toggles = toggles;
        this.redis = redis;
    }

    public void acquire() {
        String mode = toggles.get(NotificationToggles.VENDOR_RATE_LIMIT);
        if (mode.equals("off")) {
            return;
        }
        long rps = Math.max(1, toggles.getLong(NotificationToggles.VENDOR_RATE_LIMIT_RPS));
        while (!(mode.equals("redis") ? takeRedis(rps) : takeLocal(rps))) {
            sleep(1000 / rps);
        }
    }

    private boolean takeRedis(long rps) {
        try {
            Long ok = redis.execute(TAKE, List.of("vendor-tokens"), Long.toString(rps));
            return ok != null && ok == 1;
        } catch (RuntimeException e) {
            return takeLocal(rps);   // Redis down: fall back to a local limit rather than flooding the vendor
        }
    }

    private synchronized boolean takeLocal(long rps) {
        long now = System.currentTimeMillis();
        localTokens = Math.min(rps, localTokens + (now - localTs) * rps / 1000.0);
        localTs = now;
        if (localTokens >= 1) {
            localTokens -= 1;
            return true;
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(1, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
