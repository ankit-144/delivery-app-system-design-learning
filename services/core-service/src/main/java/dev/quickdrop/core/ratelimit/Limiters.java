package dev.quickdrop.core.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The rate limiting algorithms of scenario 3.5, from worst to best. All use a one-minute window.
 */
public final class Limiters {

    private Limiters() {
    }

    private static final long WINDOW_MS = 60_000;

    @Component
    public static class Off implements RateLimiter {
        @Override
        public String strategyName() {
            return "off";
        }

        @Override
        public boolean tryAcquire(String key, int limit) {
            return true;
        }
    }

    /**
     * BROKEN WITH MORE THAN ONE INSTANCE. A counter per key per minute in this JVM's memory. With three
     * instances behind a round-robin load balancer, each one allows {@code limit}: the bot gets 3×.
     */
    @Component
    public static class Local implements RateLimiter {

        private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(2 * WINDOW_MS))
                .maximumSize(1_000_000)
                .build();

        @Override
        public String strategyName() {
            return "local";
        }

        @Override
        public boolean tryAcquire(String key, int limit) {
            long window = System.currentTimeMillis() / WINDOW_MS;
            return counters.get(key + ":" + window, k -> new AtomicInteger()).incrementAndGet() <= limit;
        }
    }

    /**
     * SHARED, BUT BURSTY AT THE EDGES. INCR a counter named after the current minute. Correct per
     * minute, yet a client can send {@code limit} at 12:00:59 and {@code limit} more at 12:01:00:
     * twice the limit within two seconds.
     */
    @Component
    public static class RedisFixedWindow implements RateLimiter {

        private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
                local count = redis.call('INCR', KEYS[1])
                if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
                return count
                """, Long.class);

        private final StringRedisTemplate redis;

        public RedisFixedWindow(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public String strategyName() {
            return "redis-fixed";
        }

        @Override
        public boolean tryAcquire(String key, int limit) {
            long window = System.currentTimeMillis() / WINDOW_MS;
            Long count = redis.execute(SCRIPT, List.of("rl:fixed:" + key + ":" + window), Long.toString(WINDOW_MS));
            return count != null && count <= limit;
        }
    }

    /**
     * EXACT. A sorted set of request timestamps per key: drop the ones older than a minute, count the
     * rest. Never more than {@code limit} in any 60-second span. Costs one set entry per request.
     * Uses Redis' own clock (TIME), so instances with skewed clocks still agree.
     */
    @Component
    public static class RedisSlidingLog implements RateLimiter {

        private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
                local t = redis.call('TIME')
                local now = t[1] * 1000 + math.floor(t[2] / 1000)
                local window = tonumber(ARGV[1])
                redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
                if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[2]) then
                  redis.call('ZADD', KEYS[1], now, ARGV[3])
                  redis.call('PEXPIRE', KEYS[1], window)
                  return 1
                end
                return 0
                """, Long.class);

        private final StringRedisTemplate redis;

        public RedisSlidingLog(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public String strategyName() {
            return "redis-sliding";
        }

        @Override
        public boolean tryAcquire(String key, int limit) {
            String member = System.nanoTime() + "-" + ThreadLocalRandom.current().nextInt();
            Long allowed = redis.execute(SCRIPT, List.of("rl:sliding:" + key), Long.toString(WINDOW_MS), Integer.toString(limit), member);
            return allowed != null && allowed == 1;
        }
    }

    /**
     * SMOOTH. A bucket holds up to {@code limit} tokens and refills at {@code limit} per minute; each
     * request takes one. Allows short bursts, enforces the average rate, and stores two numbers per key.
     */
    @Component
    public static class RedisTokenBucket implements RateLimiter {

        private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
                local t = redis.call('TIME')
                local now = t[1] * 1000 + math.floor(t[2] / 1000)
                local capacity = tonumber(ARGV[1])
                local refill_per_ms = tonumber(ARGV[2])
                local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
                local tokens = tonumber(bucket[1]) or capacity
                local ts = tonumber(bucket[2]) or now
                tokens = math.min(capacity, tokens + (now - ts) * refill_per_ms)
                local allowed = 0
                if tokens >= 1 then
                  tokens = tokens - 1
                  allowed = 1
                end
                redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
                redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill_per_ms) + 1000)
                return allowed
                """, Long.class);

        private final StringRedisTemplate redis;

        public RedisTokenBucket(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public String strategyName() {
            return "redis-token-bucket";
        }

        @Override
        public boolean tryAcquire(String key, int limit) {
            double refillPerMs = (double) limit / WINDOW_MS;
            Long allowed = redis.execute(SCRIPT, List.of("rl:bucket:" + key), Integer.toString(limit), Double.toString(refillPerMs));
            return allowed != null && allowed == 1;
        }
    }
}
