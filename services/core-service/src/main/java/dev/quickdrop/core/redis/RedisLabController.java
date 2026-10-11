package dev.quickdrop.core.redis;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately dangerous Redis operations for the Redis deep-dive scenarios (R1–R3). The kind of
 * "harmless debug endpoint" that takes production Redis down. Never ship anything like this.
 *
 * <pre>
 * POST /admin/redis-lab/populate?count=2000000      DEBUG POPULATE: millions of junk keys
 * POST /admin/redis-lab/keys?pattern=user:*          KEYS: walks every key while everyone else waits (R1)
 * POST /admin/redis-lab/scan?pattern=user:*          SCAN: the same answer in small steps (R1 fix)
 * POST /admin/redis-lab/big-hash?fields=1000000      one huge hash (R2)
 * POST /admin/redis-lab/big-hash/read                HGETALL on it (R2)
 * POST /admin/redis-lab/big-hash/delete?how=del      DEL (blocking) or UNLINK (background) (R2)
 * POST /admin/redis-lab/flush-junk                   remove what the lab created
 * POST /admin/redis-lab/config?name=maxmemory&value=64mb       CONFIG SET (R3)
 * GET  /admin/redis-lab/info                          memory, keys, evictions
 * </pre>
 */
@RestController
public class RedisLabController {

    private final StringRedisTemplate redis;

    public RedisLabController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostMapping("/admin/redis-lab/populate")
    public Map<String, Object> populate(@RequestParam(defaultValue = "2000000") long count) {
        return timed("DEBUG POPULATE " + count, () -> redis.execute((RedisCallback<Object>) c ->
                c.execute("DEBUG", bytes("POPULATE"), bytes(Long.toString(count)), bytes("junk"), bytes("64"))));
    }

    @PostMapping("/admin/redis-lab/keys")
    public Map<String, Object> keys(@RequestParam(defaultValue = "nothing-matches-this:*") String pattern) {
        return timed("KEYS " + pattern, () -> redis.keys(pattern).size());
    }

    @PostMapping("/admin/redis-lab/scan")
    public Map<String, Object> scan(@RequestParam(defaultValue = "nothing-matches-this:*") String pattern) {
        return timed("SCAN MATCH " + pattern + " COUNT 1000", () -> {
            int found = 0;
            try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(1000).build())) {
                while (cursor.hasNext()) {
                    cursor.next();
                    found++;
                }
            }
            return found;
        });
    }

    @PostMapping("/admin/redis-lab/big-hash")
    public Map<String, Object> bigHash(@RequestParam(defaultValue = "1000000") int fields) {
        return timed("HSET lab:big-hash x " + fields + " fields", () -> {
            redis.delete("lab:big-hash");
            int batch = 10_000;
            for (int start = 0; start < fields; start += batch) {
                Map<String, String> chunk = new HashMap<>();
                for (int i = start; i < Math.min(fields, start + batch); i++) {
                    chunk.put("customer:" + i, "{\"lastOrder\":" + i + ",\"segment\":\"regular\"}");
                }
                redis.opsForHash().putAll("lab:big-hash", chunk);
            }
            return redis.opsForHash().size("lab:big-hash");
        });
    }

    @PostMapping("/admin/redis-lab/big-hash/read")
    public Map<String, Object> readBigHash() {
        return timed("HGETALL lab:big-hash", () -> redis.opsForHash().entries("lab:big-hash").size());
    }

    @PostMapping("/admin/redis-lab/big-hash/delete")
    public Map<String, Object> deleteBigHash(@RequestParam(defaultValue = "del") String how) {
        if (how.equals("unlink")) {
            return timed("UNLINK lab:big-hash", () -> redis.unlink("lab:big-hash"));
        }
        return timed("DEL lab:big-hash", () -> redis.delete("lab:big-hash"));
    }

    @PostMapping("/admin/redis-lab/flush-junk")
    public Map<String, Object> flushJunk() {
        return timed("delete junk:* and lab:* (SCAN + UNLINK in batches)", () -> {
            long removed = 0;
            for (String pattern : new String[] {"junk:*", "lab:*"}) {
                try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(10_000).build())) {
                    java.util.List<String> batch = new java.util.ArrayList<>();
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() == 10_000) {
                            removed += redis.unlink(batch);
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        removed += redis.unlink(batch);
                    }
                }
            }
            return removed;
        });
    }

    @PostMapping("/admin/redis-lab/config")
    public Map<String, Object> config(@RequestParam String name, @RequestParam String value) {
        return timed("CONFIG SET " + name + " " + value, () -> {
            redis.execute((RedisCallback<Object>) c -> {
                c.serverCommands().setConfig(name, value);
                return null;
            });
            return "OK";
        });
    }

    @org.springframework.web.bind.annotation.GetMapping("/admin/redis-lab/info")
    public Map<String, Object> info() {
        java.util.Properties memory = redis.execute((RedisCallback<java.util.Properties>) c -> c.serverCommands().info("memory"));
        java.util.Properties stats = redis.execute((RedisCallback<java.util.Properties>) c -> c.serverCommands().info("stats"));
        java.util.Properties config = redis.execute((RedisCallback<java.util.Properties>) c -> c.serverCommands().getConfig("maxmemory*"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", redis.execute((RedisCallback<Long>) c -> c.serverCommands().dbSize()));
        out.put("usedMemory", memory.getProperty("used_memory_human"));
        out.put("maxmemory", config.getProperty("maxmemory"));
        out.put("policy", config.getProperty("maxmemory-policy"));
        out.put("evictedKeys", stats.getProperty("evicted_keys"));
        out.put("rejectedOom", stats.getProperty("total_error_replies"));
        return out;
    }

    private static Map<String, Object> timed(String command, java.util.function.Supplier<Object> action) {
        long start = System.nanoTime();
        Object result = action.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", command);
        out.put("result", result);
        out.put("millis", (System.nanoTime() - start) / 1_000_000);
        return out;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
