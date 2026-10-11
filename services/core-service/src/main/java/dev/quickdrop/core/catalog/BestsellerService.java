package dev.quickdrop.core.catalog;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.Chaos;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.redis.RedisLock;
import dev.quickdrop.core.redis.RedisUnavailable;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * "Best sellers in this store, last 90 days" on the home screen — scenario 3.8 (cache stampede).
 * The query aggregates tens of thousands of order lines, so it's cached:
 *
 * <ul>
 *   <li>{@code cache-aside}: on a miss, compute and store with a TTL. When the hot key expires, every
 *       request arriving in the next few hundred milliseconds misses at once and runs the expensive query
 *       — on every instance. Watch {@code quickdrop_bestsellers_computing} spike every TTL.</li>
 *   <li>{@code single-flight}: on a miss, only the request that wins a Redis lock recomputes. Everyone
 *       else gets the previous ("stale") value immediately, or waits briefly for the fresh one.</li>
 * </ul>
 */
@Service
public class BestsellerService {

    public record Bestseller(long productId, String name, long units) {
    }

    private static final TypeReference<List<Bestseller>> LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final RedisLock lock;
    private final ObjectMapper json;
    private final ToggleRegistry toggles;
    private final MeterRegistry meters;
    private final AtomicInteger computing = new AtomicInteger();
    /** The last answer this instance served per store: what we show when Redis is gone (bestsellers-fallback=last-known). */
    private final java.util.Map<Long, List<Bestseller>> lastKnown = new java.util.concurrent.ConcurrentHashMap<>();
    private final Timer queryTimer;

    public BestsellerService(JdbcClient jdbc, StringRedisTemplate redis, RedisLock lock, ObjectMapper json,
                             ToggleRegistry toggles, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.lock = lock;
        this.json = json;
        this.toggles = toggles;
        this.meters = meters;
        this.queryTimer = Timer.builder("quickdrop.bestsellers.query").description("The expensive aggregate query").register(meters);
        Gauge.builder("quickdrop.bestsellers.computing", computing, AtomicInteger::get)
                .description("Best-seller queries running right now on this instance")
                .register(meters);
        for (String result : List.of("hit", "miss", "stale", "waited", "uncached", "fallback_computed", "fallback_last_known")) {
            meters.counter("quickdrop.bestsellers.requests", "result", result);
        }
    }

    public List<Bestseller> bestsellers(long storeId) {
        List<Bestseller> result = lookup(storeId);
        lastKnown.put(storeId, result);
        return result;
    }

    private List<Bestseller> lookup(long storeId) {
        String mode = toggles.get(CoreToggles.BESTSELLERS_CACHE);
        try {
            return switch (mode) {
                case "cache-aside" -> cacheAside(storeId);
                case "single-flight" -> singleFlight(storeId);
                default -> {
                    count("uncached");
                    yield compute(storeId);
                }
            };
        } catch (RuntimeException e) {
            if (RedisUnavailable.is(e) && toggles.is(CoreToggles.REDIS_FAILURE_MODE, "degrade")) {
                if (toggles.is(CoreToggles.BESTSELLERS_FALLBACK, "last-known")) {
                    // A slightly old list, or none at all: the home screen still loads, the database is not touched.
                    count("fallback_last_known");
                    return lastKnown.getOrDefault(storeId, List.of());
                }
                count("fallback_computed");   // every request now runs the 0.6 CPU-second query: Postgres is next
                return compute(storeId);
            }
            throw e;
        }
    }

    private List<Bestseller> cacheAside(long storeId) {
        String cached = redis.opsForValue().get(freshKey(storeId));
        if (cached != null) {
            count("hit");
            return read(cached);
        }
        count("miss");
        List<Bestseller> fresh = compute(storeId);
        redis.opsForValue().set(freshKey(storeId), write(fresh), ttl());
        return fresh;
    }

    private List<Bestseller> singleFlight(long storeId) {
        String cached = redis.opsForValue().get(freshKey(storeId));
        if (cached != null) {
            count("hit");
            return read(cached);
        }
        var lease = lock.tryOnce("bestsellers:" + storeId, Duration.ofSeconds(10));
        if (lease.isPresent()) {
            try {
                count("miss");
                List<Bestseller> fresh = compute(storeId);
                String value = write(fresh);
                redis.opsForValue().set(freshKey(storeId), value, ttl());
                redis.opsForValue().set(staleKey(storeId), value, ttl().multipliedBy(30));
                return fresh;
            } finally {
                lock.release(lease.get());
            }
        }
        // Someone else is recomputing. A slightly old answer now beats a fresh one in 300 ms.
        String stale = redis.opsForValue().get(staleKey(storeId));
        if (stale != null) {
            count("stale");
            return read(stale);
        }
        for (int i = 0; i < 60; i++) {   // first time ever: no stale copy yet, wait up to ~3 s
            Chaos.sleep(50);
            String fresh = redis.opsForValue().get(freshKey(storeId));
            if (fresh != null) {
                count("waited");
                return read(fresh);
            }
        }
        count("miss");
        return compute(storeId);
    }

    private List<Bestseller> compute(long storeId) {
        computing.incrementAndGet();
        try {
            return queryTimer.record(() -> jdbc.sql("""
                            SELECT oi.product_id, p.name, sum(oi.quantity) AS units
                            FROM orders o
                                     JOIN order_items oi ON oi.order_id = o.id
                                     JOIN products p ON p.id = oi.product_id
                            WHERE o.store_id = :store AND o.created_at > now() - interval '90 days'
                            GROUP BY oi.product_id, p.name
                            ORDER BY units DESC
                            LIMIT 10
                            """)
                    .param("store", storeId)
                    .query((rs, n) -> new Bestseller(rs.getLong(1), rs.getString(2), rs.getLong(3)))
                    .list());
        } finally {
            computing.decrementAndGet();
        }
    }

    private Duration ttl() {
        return Duration.ofSeconds(toggles.getLong(CoreToggles.BESTSELLERS_TTL_S));
    }

    private void count(String result) {
        meters.counter("quickdrop.bestsellers.requests", "result", result).increment();
    }

    private static String freshKey(long storeId) {
        return "bestsellers:" + storeId;
    }

    private static String staleKey(long storeId) {
        return "bestsellers:" + storeId + ":stale";
    }

    private List<Bestseller> read(String value) {
        try {
            return json.readValue(value, LIST);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(List<Bestseller> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
