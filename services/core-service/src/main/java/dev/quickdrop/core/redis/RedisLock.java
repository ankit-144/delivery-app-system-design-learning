package dev.quickdrop.core.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import dev.quickdrop.core.config.Chaos;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A distributed lock on a single Redis — scenario 3.2.
 *
 * <pre>
 * acquire:  SET lock:{name} {random token} NX PX {ttl}     only if nobody holds it; expires on its own
 *           INCR fence:{name}                              an ever-increasing fencing token (same Lua script)
 * release:  if GET lock:{name} == my token then DEL        never delete a lock someone else now holds
 * </pre>
 *
 * The TTL is what makes this safe against crashed holders, and also what makes it unsafe: a holder
 * that pauses longer than the TTL (GC, swap, a slow network) keeps running while someone else holds
 * "its" lock. The fencing token lets the database reject the late writer.
 */
@Component
public class RedisLock {

    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
              return redis.call('INCR', KEYS[2])
            end
            return -1
            """, Long.class);

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    public RedisLock(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
        for (String result : List.of("acquired", "timeout", "released", "expired_before_release")) {
            meters.counter("quickdrop.redis.lock", "result", result);
        }
    }

    /** @param fence the fencing token: higher than every token handed out before it */
    public record Lease(String name, String token, long fence, long ttlMs) {
    }

    /** Tries to take the lock, retrying until {@code waitMs} has passed. */
    public Optional<Lease> acquire(String name, long ttlMs, long waitMs) {
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            Long fence = redis.execute(ACQUIRE, List.of("lock:" + name, "fence:" + name), token, Long.toString(ttlMs));
            if (fence != null && fence >= 0) {
                count("acquired");
                return Optional.of(new Lease(name, token, fence, ttlMs));
            }
            if (System.currentTimeMillis() >= deadline) {
                count("timeout");
                return Optional.empty();
            }
            Chaos.sleep(ThreadLocalRandom.current().nextLong(5, 20));
        }
    }

    /** Returns false if the lock had already expired (and maybe been taken by someone else) — a red flag. */
    public boolean release(Lease lease) {
        Long deleted = redis.execute(RELEASE, List.of("lock:" + lease.name()), lease.token());
        boolean ok = deleted != null && deleted == 1;
        count(ok ? "released" : "expired_before_release");
        return ok;
    }

    /** Plain "only one runs at a time" lock without waiting, for scheduled jobs. */
    public Optional<Lease> tryOnce(String name, Duration ttl) {
        return acquire(name, ttl.toMillis(), 0);
    }

    private void count(String result) {
        meters.counter("quickdrop.redis.lock", "result", result).increment();
    }
}
