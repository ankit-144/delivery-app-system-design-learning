package dev.quickdrop.core.sharding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.flywaydb.core.Flyway;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operating the shards (Phase 5):
 * <pre>
 * GET  /admin/sharding/status                 rows, size and schema version per shard
 * POST /admin/sharding/reset                  empty every shard, schema back to version 2
 * POST /admin/sharding/migrate?target=3       run shard migrations up to a version, shard by shard (5.8)
 * POST /admin/sharding/legacy-row?shard=1     insert a row an old import bug left behind (5.8)
 * GET  /admin/sharding/duplicates             ids that exist on more than one shard (5.4, 5.7)
 * GET  /admin/sharding/resharding?from=2&to=3 how many keys move under three schemes (5.6)
 * </pre>
 */
@RestController
public class ShardingAdminController {

    private final Shards shards;
    private final SnowflakeIds ids;

    public ShardingAdminController(Shards shards, SnowflakeIds ids) {
        this.shards = shards;
        this.ids = ids;
    }

    /** Every instance brings the shards to schema version 2 on startup (Flyway's lock makes that safe). */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateOnStartup() {
        if (shards.enabled()) {
            migrate("2");
        }
    }

    @GetMapping("/admin/sharding/status")
    public List<Map<String, Object>> status() {
        shards.requireEnabled();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Shards.Shard s : shards.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shard", s.index());
            m.put("schemaVersion", flyway(s, null).info().current() == null ? "none" : flyway(s, null).info().current().getVersion().getVersion());
            m.put("rows", s.jdbc().sql("SELECT count(*) FROM sharded_orders").query(Long.class).single());
            m.put("size", s.jdbc().sql("SELECT pg_size_pretty(pg_total_relation_size('sharded_orders'))").query(String.class).single());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/admin/sharding/reset")
    public List<Map<String, Object>> reset() {
        shards.requireEnabled();
        for (Shards.Shard s : shards.all()) {
            s.jdbc().sql("DROP SCHEMA public CASCADE").update();
            s.jdbc().sql("CREATE SCHEMA public").update();
        }
        migrate("2");
        return status();
    }

    @PostMapping("/admin/sharding/migrate")
    public List<Map<String, Object>> migrate(@RequestParam(defaultValue = "latest") String target) {
        shards.requireEnabled();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Shards.Shard s : shards.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shard", s.index());
            try {
                var result = flyway(s, target).migrate();
                m.put("applied", result.migrationsExecuted);
                m.put("version", result.targetSchemaVersion == null ? result.initialSchemaVersion : result.targetSchemaVersion);
            } catch (Exception e) {
                m.put("error", rootMessage(e));
            }
            out.add(m);
        }
        return out;
    }

    @PostMapping("/admin/sharding/legacy-row")
    public Map<String, Object> legacyRow(@RequestParam(defaultValue = "1") int shard) {
        shards.requireEnabled();
        long id = ids.next(shard);
        shards.get(shard).jdbc().sql("""
                        INSERT INTO sharded_orders (id, customer_id, city_id, store_id, total_paise) VALUES (?, 1, 2, 3, -100)
                        """)
                .param(id).update();
        return Map.of("shard", shard, "id", id, "totalPaise", -100, "note", "a refund imported as a negative order by an old script");
    }

    @GetMapping("/admin/sharding/duplicates")
    public Map<String, Object> duplicates() {
        shards.requireEnabled();
        Map<Long, Integer> seen = new HashMap<>();
        Set<Long> duplicated = new HashSet<>();
        for (Shards.Shard s : shards.all()) {
            for (Long id : s.jdbc().sql("SELECT id FROM sharded_orders").query(Long.class).list()) {
                if (seen.merge(id, 1, Integer::sum) > 1) {
                    duplicated.add(id);
                }
            }
        }
        return Map.of("idsOnMoreThanOneShard", duplicated.size(), "examples", duplicated.stream().limit(5).toList());
    }

    /**
     * Adding shards: what share of keys has to move to a different shard? (Scenario 5.6 — pure arithmetic.)
     * <ul>
     *   <li>modulo: shard = hash(key) % N</li>
     *   <li>consistent hashing: shards own arcs of a hash ring (100 virtual nodes each)</li>
     *   <li>logical buckets: hash(key) % 256 picks a bucket; a table maps buckets to shards; move whole buckets</li>
     * </ul>
     */
    @GetMapping("/admin/sharding/resharding")
    public Map<String, Object> resharding(@RequestParam(defaultValue = "2") int from, @RequestParam(defaultValue = "3") int to,
                                          @RequestParam(defaultValue = "100000") int keys) {
        TreeMap<Long, Integer> ringFrom = ring(from);
        TreeMap<Long, Integer> ringTo = ring(to);
        int[] bucketsFrom = buckets(256, from, null);
        int[] bucketsTo = buckets(256, to, bucketsFrom);
        int movedModulo = 0;
        int movedRing = 0;
        int movedBuckets = 0;
        for (long k = 1; k <= keys; k++) {
            long h = ShardRouter.mix(k);
            if (Math.floorMod(h, from) != Math.floorMod(h, to)) movedModulo++;
            if (owner(ringFrom, h) != owner(ringTo, h)) movedRing++;
            int b = (int) Math.floorMod(h, 256);
            if (bucketsFrom[b] != bucketsTo[b]) movedBuckets++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("keys", keys);
        out.put("ideal", pct(keys - (long) keys * from / to, keys));
        out.put("modulo", pct(movedModulo, keys));
        out.put("consistentHashing", pct(movedRing, keys));
        out.put("logicalBuckets", pct(movedBuckets, keys));
        return out;
    }

    private static TreeMap<Long, Integer> ring(int shards) {
        TreeMap<Long, Integer> ring = new TreeMap<>();
        for (int s = 0; s < shards; s++) {
            for (int v = 0; v < 100; v++) {
                ring.put(ShardRouter.mix(s * 1_000_003L + v * 7_919L + 1), s);
            }
        }
        return ring;
    }

    private static int owner(TreeMap<Long, Integer> ring, long hash) {
        Map.Entry<Long, Integer> e = ring.ceilingEntry(hash);
        return (e == null ? ring.firstEntry() : e).getValue();
    }

    /** Assign buckets to shards; when growing, move only as many buckets as needed to the new shards. */
    private static int[] buckets(int count, int shards, int[] previous) {
        int[] map = new int[count];
        if (previous == null) {
            for (int b = 0; b < count; b++) map[b] = b % shards;
            return map;
        }
        System.arraycopy(previous, 0, map, 0, count);
        int target = count / shards;
        int[] load = new int[shards];
        for (int b : map) load[b]++;
        for (int newShard = 0; newShard < shards; newShard++) {
            for (int b = 0; b < count && load[newShard] < target; b++) {
                int owner = map[b];
                if (owner != newShard && load[owner] > target) {
                    map[b] = newShard;
                    load[owner]--;
                    load[newShard]++;
                }
            }
        }
        return map;
    }

    private static String pct(long moved, long total) {
        return String.format("%.1f%%", 100.0 * moved / total);
    }

    private static Flyway flyway(Shards.Shard s, String target) {
        var config = Flyway.configure().dataSource(s.pool()).locations("classpath:db/shard");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage();
    }
}
