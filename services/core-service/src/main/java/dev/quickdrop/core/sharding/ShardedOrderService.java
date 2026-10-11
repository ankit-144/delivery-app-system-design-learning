package dev.quickdrop.core.sharding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.catalog.CatalogService;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.web.NotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * Orders spread over N shard databases — scenarios 5.2-5.7. Every operation reports which shards it
 * had to ask: one shard is a cheap query, "every shard" is a scatter-gather.
 */
@Service
public class ShardedOrderService {

    /**
     * {@code id} is sent as a JSON <em>string</em>: Snowflake ids use all 63 bits, and JavaScript numbers
     * are exact only up to 2^53 — a browser (or k6) would silently round "order 123456789012345678" to a
     * different id. (Twitter's API returns {@code id_str} for the same reason.)
     */
    public record ShardedOrder(@com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long id,
                               int shard, long customerId, int cityId, long storeId, int totalPaise, Instant createdAt) {
    }

    /** @param shardsQueried how many shard databases this answer needed */
    public record Answer<T>(T result, int shardsQueried, Map<Integer, Long> millisPerShard) {
    }

    public static class CrossShardCrash extends RuntimeException {
        public CrossShardCrash(String message) {
            super(message);
        }
    }

    private final Shards shards;
    private final ShardRouter router;
    private final SnowflakeIds snowflake;
    private final CatalogService catalog;
    private final ToggleRegistry toggles;
    private final MeterRegistry meters;
    private final ExecutorService scatter = Executors.newVirtualThreadPerTaskExecutor();

    public ShardedOrderService(Shards shards, ShardRouter router, SnowflakeIds snowflake, CatalogService catalog,
                               ToggleRegistry toggles, MeterRegistry meters) {
        this.shards = shards;
        this.router = router;
        this.snowflake = snowflake;
        this.catalog = catalog;
        this.toggles = toggles;
        this.meters = meters;
    }

    public ShardedOrder place(long customerId, long storeId, int totalPaise) {
        shards.requireEnabled();
        int cityId = catalog.store(storeId).getCityId();
        int shard = router.shardFor(customerId, cityId);
        Shards.Shard target = shards.get(shard);
        long id = toggles.is(CoreToggles.ID_STRATEGY, "snowflake")
                ? snowflake.next(shard)
                : target.jdbc().sql("SELECT nextval('sharded_orders_seq')").query(Long.class).single();   // 1, 2, 3 ... on every shard
        timed(shard, "insert", () -> target.jdbc().sql("""
                        INSERT INTO sharded_orders (id, customer_id, city_id, store_id, total_paise) VALUES (?, ?, ?, ?, ?)
                        """)
                .params(id, customerId, cityId, storeId, totalPaise)
                .update());
        return new ShardedOrder(id, shard, customerId, cityId, storeId, totalPaise, Instant.now());
    }

    /**
     * With Snowflake ids the shard is in the id: ask that one shard. If the order has since moved to
     * another shard (5.7), the id points to the wrong place — fall back to asking every shard. With
     * per-shard sequences we must always ask every shard, and may find several different orders (5.4).
     */
    public Answer<List<ShardedOrder>> findById(long id) {
        shards.requireEnabled();
        if (toggles.is(CoreToggles.ID_STRATEGY, "snowflake") && SnowflakeIds.shardOf(id) < shards.count()) {
            int home = SnowflakeIds.shardOf(id);
            Answer<List<ShardedOrder>> direct = on(List.of(home), "lookup", s -> query(s, "WHERE id = ?", id, 1));
            if (!direct.result().isEmpty()) {
                return direct;
            }
            meters.counter("quickdrop.shard.lookup.misses").increment();
            Answer<List<ShardedOrder>> everywhere = on(allShards(), "lookup", s -> query(s, "WHERE id = ?", id, 1));
            return new Answer<>(everywhere.result(), 1 + everywhere.shardsQueried(), everywhere.millisPerShard());
        }
        return on(allShards(), "lookup", s -> query(s, "WHERE id = ?", id, 1));
    }

    public Answer<List<ShardedOrder>> customerOrders(long customerId, int limit) {
        shards.requireEnabled();
        List<Integer> targets = router.customerIsOnOneShard() ? List.of(router.shardFor(customerId, 0)) : allShards();
        return limit(on(targets, "customer", s -> query(s, "WHERE customer_id = ? ORDER BY created_at DESC", customerId, limit)), limit);
    }

    public Answer<List<ShardedOrder>> storeOrders(long storeId, int limit) {
        shards.requireEnabled();
        int cityId = catalog.store(storeId).getCityId();
        List<Integer> targets = router.storeIsOnOneShard() ? List.of(router.shardFor(0, cityId)) : allShards();
        return limit(on(targets, "store", s -> query(s, "WHERE store_id = ? ORDER BY created_at DESC", storeId, limit)), limit);
    }

    /**
     * Scenario 5.7: the customer changes the order to a store in another city. If that city lives on
     * another shard, the order must move: insert on the new shard, delete from the old one — two
     * databases, two transactions, nothing that makes them atomic. Both steps are idempotent
     * (ON CONFLICT DO NOTHING, DELETE by id), so retrying a move that crashed halfway finishes it.
     */
    public Map<String, Object> move(long orderId, long newStoreId) {
        List<ShardedOrder> found = findById(orderId).result();
        if (found.isEmpty()) {
            throw new NotFoundException("sharded order", orderId);
        }
        // During a half-finished move the order exists twice; the copy NOT on the target shard is the source.
        int newCityForTarget = catalog.store(newStoreId).getCityId();
        int targetShard = router.shardFor(found.get(0).customerId(), newCityForTarget);
        ShardedOrder order = found.stream().filter(o -> o.shard() != targetShard).findFirst().orElse(found.get(0));
        int newCity = catalog.store(newStoreId).getCityId();
        int target = router.shardFor(order.customerId(), newCity);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("fromShard", order.shard());
        result.put("toShard", target);
        if (target == order.shard()) {
            shards.get(target).jdbc().sql("UPDATE sharded_orders SET store_id = ?, city_id = ? WHERE id = ?")
                    .params(newStoreId, newCity, orderId).update();
            result.put("result", "same shard: one UPDATE");
            return result;
        }
        shards.get(target).jdbc().sql("""
                        INSERT INTO sharded_orders (id, customer_id, city_id, store_id, total_paise, created_at) VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .params(orderId, order.customerId(), newCity, newStoreId, order.totalPaise(), java.sql.Timestamp.from(order.createdAt()))
                .update();
        if (ThreadLocalRandom.current().nextInt(100) < toggles.getLong(CoreToggles.CROSS_SHARD_CRASH_PERCENT)) {
            meters.counter("quickdrop.shard.move.crashes").increment();
            throw new CrossShardCrash("Crashed after inserting order " + orderId + " on shard " + target
                    + " and before deleting it from shard " + order.shard());
        }
        shards.get(order.shard()).jdbc().sql("DELETE FROM sharded_orders WHERE id = ?").param(orderId).update();
        result.put("result", "moved: INSERT on shard " + target + ", then DELETE on shard " + order.shard());
        return result;
    }

    private List<ShardedOrder> query(int shard, String where, long value, int limit) {
        return shards.get(shard).jdbc()
                .sql("SELECT id, customer_id, city_id, store_id, total_paise, created_at FROM sharded_orders " + where + " LIMIT " + limit)
                .param(value)
                .query((rs, n) -> new ShardedOrder(rs.getLong(1), shard, rs.getLong(2), rs.getInt(3), rs.getLong(4), rs.getInt(5),
                        rs.getTimestamp(6).toInstant()))
                .list();
    }

    private interface ShardQuery {
        List<ShardedOrder> run(int shard);
    }

    /** Asks the given shards in parallel and merges the answers; as slow as the slowest shard. */
    private Answer<List<ShardedOrder>> on(List<Integer> targets, String kind, ShardQuery q) {
        Map<Integer, Long> millis = new LinkedHashMap<>();
        List<ShardedOrder> merged = new ArrayList<>();
        try {
            List<Future<List<ShardedOrder>>> futures = new ArrayList<>();
            for (int shard : targets) {
                futures.add(scatter.submit(() -> {
                    long start = System.nanoTime();
                    List<ShardedOrder> rows = timed(shard, kind, () -> q.run(shard));
                    synchronized (millis) {
                        millis.put(shard, (System.nanoTime() - start) / 1_000_000);
                    }
                    return rows;
                }));
            }
            for (Future<List<ShardedOrder>> f : futures) {
                merged.addAll(f.get());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Shard query failed: " + e.getMessage(), e);
        }
        return new Answer<>(merged, targets.size(), millis);
    }

    private static Answer<List<ShardedOrder>> limit(Answer<List<ShardedOrder>> answer, int limit) {
        List<ShardedOrder> sorted = answer.result().stream()
                .sorted(Comparator.comparing(ShardedOrder::createdAt).reversed()).limit(limit).toList();
        return new Answer<>(sorted, answer.shardsQueried(), answer.millisPerShard());
    }

    private List<Integer> allShards() {
        return shards.all().stream().map(Shards.Shard::index).toList();
    }

    private <T> T timed(int shard, String kind, java.util.function.Supplier<T> work) {
        meters.counter("quickdrop.shard.queries", "shard", Integer.toString(shard), "kind", kind).increment();
        return Timer.builder("quickdrop.shard.query").tag("shard", Integer.toString(shard)).register(meters).record(work);
    }
}
