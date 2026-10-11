package dev.quickdrop.core.redis;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The StringRedisTemplate every component uses, with an optional circuit breaker around the single
 * method all Redis commands go through — scenario 3.9.
 *
 * <pre>
 *  CLOSED ──(≥50% of the last 20 calls failed)──▶ OPEN ──(after 5 s)──▶ HALF_OPEN ──(3 trial calls ok)──▶ CLOSED
 *    calls go to Redis                              calls fail instantly             a few calls test Redis
 * </pre>
 *
 * While OPEN, callers get a RedisConnectionFailureException immediately, so every fallback
 * ({@code redis-failure-mode=degrade}) runs in microseconds instead of after a 1 s timeout per call.
 */
public class GuardedRedisTemplate extends StringRedisTemplate {

    private final CircuitBreaker breaker;
    private final ToggleRegistry toggles;

    public GuardedRedisTemplate(RedisConnectionFactory connections, CircuitBreaker breaker, ToggleRegistry toggles) {
        super(connections);
        this.breaker = breaker;
        this.toggles = toggles;
    }

    @Override
    public <T> T execute(RedisCallback<T> action, boolean exposeConnection, boolean pipeline) {
        if (toggles.is(CoreToggles.REDIS_CIRCUIT_BREAKER, "off")) {
            return super.execute(action, exposeConnection, pipeline);
        }
        try {
            return breaker.executeSupplier(() -> super.execute(action, exposeConnection, pipeline));
        } catch (CallNotPermittedException e) {
            throw new RedisConnectionFailureException("Redis circuit breaker is OPEN: not calling Redis", e);
        }
    }
}
