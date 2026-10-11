package dev.quickdrop.core.catalog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.quickdrop.common.toggles.ToggleChangedEvent;
import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.redis.RedisUnavailable;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Product details (names, prices) cached according to {@code product-cache} — scenario 3.3.
 *
 * <ul>
 *   <li>{@code none}      every lookup goes to Postgres.</li>
 *   <li>{@code local}     an in-memory cache in each JVM. Fast, but a price change only evicts the
 *                         instance that made it: the others serve the old price until the TTL expires.</li>
 *   <li>{@code redis}     one shared copy in Redis. Every instance sees the change at once; every lookup
 *                         is a network round trip.</li>
 *   <li>{@code two-tier}  in-memory in front of Redis, kept honest by publishing an invalidation message
 *                         on a Redis pub/sub channel that every instance listens to.</li>
 * </ul>
 */
@Component
public class ProductCache {

    private static final Logger log = LoggerFactory.getLogger(ProductCache.class);
    private static final String CHANNEL = "product-invalidations";

    private final ProductRepository products;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ToggleRegistry toggles;
    private final RedisMessageListenerContainer listeners;
    private final MeterRegistry meters;
    private final MessageListener onInvalidation;
    private volatile Cache<Long, ProductSnapshot> local;
    private volatile boolean subscribed;

    public ProductCache(ProductRepository products, StringRedisTemplate redis, ObjectMapper json, ToggleRegistry toggles,
                        RedisMessageListenerContainer listeners, MeterRegistry meters) {
        this.products = products;
        this.redis = redis;
        this.json = json;
        this.toggles = toggles;
        this.listeners = listeners;
        this.meters = meters;
        this.local = newLocalCache();
        this.onInvalidation = (message, pattern) -> {
            long id = Long.parseLong(new String(message.getBody()));
            local.invalidate(id);
            meters.counter("quickdrop.product.cache.invalidations.received").increment();
        };
        for (String result : List.of("hit", "miss")) {
            for (String layer : List.of("local", "redis")) {
                meters.counter("quickdrop.product.cache", "layer", layer, "result", result);
            }
        }
    }

    public Map<Long, ProductSnapshot> getAll(Collection<Long> ids) {
        String mode = toggles.get(CoreToggles.PRODUCT_CACHE);
        try {
            return switch (mode) {
                case "local" -> fromLocal(ids, this::fromDatabase);
                case "redis" -> fromRedis(ids);
                case "two-tier" -> fromLocal(ids, this::fromRedis);
                default -> fromDatabase(ids);
            };
        } catch (RuntimeException e) {
            if (RedisUnavailable.is(e) && toggles.is(CoreToggles.REDIS_FAILURE_MODE, "degrade")) {
                meters.counter("quickdrop.product.cache.fallbacks").increment();
                return fromDatabase(ids);
            }
            throw e;
        }
    }

    /** Called after a price change has committed. */
    public void evict(long productId) {
        local.invalidate(productId);
        String mode = toggles.get(CoreToggles.PRODUCT_CACHE);
        if (mode.equals("redis") || mode.equals("two-tier")) {
            redis.delete(key(productId));
        }
        if (mode.equals("two-tier")) {
            redis.convertAndSend(CHANNEL, Long.toString(productId));
        }
    }

    private Map<Long, ProductSnapshot> fromLocal(Collection<Long> ids, Function<Collection<Long>, Map<Long, ProductSnapshot>> loader) {
        Map<Long, ProductSnapshot> found = local.getAllPresent(ids);
        meters.counter("quickdrop.product.cache", "layer", "local", "result", "hit").increment(found.size());
        List<Long> missing = ids.stream().filter(id -> !found.containsKey(id)).toList();
        if (missing.isEmpty()) {
            return found;
        }
        meters.counter("quickdrop.product.cache", "layer", "local", "result", "miss").increment(missing.size());
        Map<Long, ProductSnapshot> loaded = loader.apply(missing);
        local.putAll(loaded);
        Map<Long, ProductSnapshot> all = new HashMap<>(found);
        all.putAll(loaded);
        return all;
    }

    private Map<Long, ProductSnapshot> fromRedis(Collection<Long> ids) {
        List<Long> ordered = new ArrayList<>(ids);
        List<String> values = redis.opsForValue().multiGet(ordered.stream().map(ProductCache::key).toList());
        Map<Long, ProductSnapshot> found = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            String value = values == null ? null : values.get(i);
            if (value == null) {
                missing.add(ordered.get(i));
            } else {
                found.put(ordered.get(i), read(value));
            }
        }
        meters.counter("quickdrop.product.cache", "layer", "redis", "result", "hit").increment(found.size());
        if (!missing.isEmpty()) {
            meters.counter("quickdrop.product.cache", "layer", "redis", "result", "miss").increment(missing.size());
            Duration ttl = Duration.ofSeconds(toggles.getLong(CoreToggles.PRODUCT_CACHE_TTL_S));
            Map<Long, ProductSnapshot> loaded = fromDatabase(missing);
            loaded.forEach((id, snapshot) -> redis.opsForValue().set(key(id), write(snapshot), ttl));
            found.putAll(loaded);
        }
        return found;
    }

    private Map<Long, ProductSnapshot> fromDatabase(Collection<Long> ids) {
        return products.findAllById(ids).stream().map(ProductSnapshot::of)
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        syncSubscription();
    }

    @EventListener
    public void onToggle(ToggleChangedEvent event) {
        if (event.toggle().equals("product-cache") || event.toggle().equals("product-cache-ttl-s")) {
            local = newLocalCache();   // start every experiment from an empty cache
            syncSubscription();
        }
    }

    /** Only listen on the pub/sub channel in two-tier mode, so Phases 0-2 never try to reach Redis. */
    private synchronized void syncSubscription() {
        boolean want = toggles.is(CoreToggles.PRODUCT_CACHE, "two-tier");
        if (want && !subscribed) {
            listeners.addMessageListener(onInvalidation, new ChannelTopic(CHANNEL));
            subscribed = true;
            log.info("Subscribed to {}", CHANNEL);
        } else if (!want && subscribed) {
            listeners.removeMessageListener(onInvalidation);
            subscribed = false;
        }
    }

    private Cache<Long, ProductSnapshot> newLocalCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(toggles.getLong(CoreToggles.PRODUCT_CACHE_TTL_S)))
                .maximumSize(10_000)
                .build();
    }

    private static String key(long id) {
        return "product:" + id;
    }

    private ProductSnapshot read(String value) {
        try {
            return json.readValue(value, ProductSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(ProductSnapshot snapshot) {
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
