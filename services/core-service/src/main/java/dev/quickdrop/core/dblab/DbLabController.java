package dev.quickdrop.core.dblab;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Database "second client" for Phase 4 (live schema changes): a migration tool, or a colleague's
 * forgotten psql session. Every operation opens its <em>own</em> connection (not from the app's pool)
 * and runs in the background, so a scenario can start it mid-load and watch what it does to traffic.
 *
 * <pre>
 * POST /admin/db-lab/hold?seconds=30                     BEGIN; SELECT … FROM orders; then sit idle in transaction (4.1)
 * POST /admin/db-lab/ddl?name=add-column&lockTimeoutMs=0  run a named schema change (see DDL below)
 * POST /admin/db-lab/backfill?mode=single|batched         fill orders.coupon_code for every row (4.5)
 * GET  /admin/db-lab/jobs                                 what ran, how long it took, errors
 * POST /admin/db-lab/reset                               undo every lab change
 * </pre>
 */
@RestController
public class DbLabController {

    /** The schema changes the scenarios use. Real projects ship these as Flyway migrations. */
    static final Map<String, String> DDL = Map.ofEntries(
            // 4.1 / 4.3: instant — only the catalog changes
            Map.entry("add-column", "ALTER TABLE orders ADD COLUMN coupon_code TEXT"),
            Map.entry("add-column-constant-default", "ALTER TABLE orders ADD COLUMN source TEXT NOT NULL DEFAULT 'app'"),
            // 4.3: rewrites every row under an ACCESS EXCLUSIVE lock
            Map.entry("add-column-volatile-default", "ALTER TABLE orders ADD COLUMN public_ref UUID NOT NULL DEFAULT gen_random_uuid()"),
            Map.entry("widen-column", "ALTER TABLE order_items ALTER COLUMN quantity TYPE BIGINT"),
            // 4.2
            // 4.2: look orders up by a public reference (an md5 of the id) — an expression index, ~2 s to build here
            Map.entry("create-index", "CREATE INDEX orders_ref_lab_idx ON orders (md5(id::text))"),
            Map.entry("create-index-concurrently", "CREATE INDEX CONCURRENTLY orders_ref_lab_idx ON orders (md5(id::text))"),
            // 4.4: renaming customers.phone
            Map.entry("rename-phone", "ALTER TABLE customers RENAME COLUMN phone TO phone_number"),
            Map.entry("expand-phone", "ALTER TABLE customers ADD COLUMN phone_number TEXT"),
            // Not "WHERE phone_number IS NULL": a v1 instance may have changed phone after a v1.5 instance set both.
            Map.entry("backfill-phone", "UPDATE customers SET phone_number = phone WHERE phone_number IS DISTINCT FROM phone"),
            Map.entry("contract-phone", "ALTER TABLE customers DROP COLUMN phone"));

    private static final List<String> RESET = List.of(
            "ALTER TABLE orders DROP COLUMN IF EXISTS coupon_code",
            "ALTER TABLE orders DROP COLUMN IF EXISTS source",
            "ALTER TABLE orders DROP COLUMN IF EXISTS public_ref",
            "ALTER TABLE order_items ALTER COLUMN quantity TYPE INTEGER",
            "DROP INDEX IF EXISTS orders_ref_lab_idx",
            // customers.phone back to its original shape
            """
            DO $$ BEGIN
              IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'customers' AND column_name = 'phone') THEN
                IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'customers' AND column_name = 'phone_number') THEN
                  ALTER TABLE customers RENAME COLUMN phone_number TO phone;
                ELSE
                  ALTER TABLE customers ADD COLUMN phone TEXT NOT NULL DEFAULT '';
                END IF;
              END IF;
              ALTER TABLE customers DROP COLUMN IF EXISTS phone_number;
            END $$""");

    public record Job(long id, String what, Instant startedAt, Instant finishedAt, Long millis, String result) {
    }

    private final String url;
    private final String replicaUrl;
    private final String user;
    private final String password;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();

    public DbLabController(@Value("${spring.datasource.url}") String url, @Value("${quickdrop.replica.url}") String replicaUrl,
                           @Value("${spring.datasource.username}") String user, @Value("${spring.datasource.password}") String password) {
        this.url = url;
        this.replicaUrl = replicaUrl;
        this.user = user;
        this.password = password;
    }

    /** A transaction that reads orders and then does nothing for {@code seconds}: it keeps its ACCESS SHARE lock. */
    @PostMapping("/admin/db-lab/hold")
    public Job hold(@RequestParam(defaultValue = "30") int seconds) {
        return background("idle in transaction for " + seconds + " s, holding a lock on orders", c -> {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET application_name = 'analyst-psql'");
                s.executeQuery("SELECT id FROM orders LIMIT 1").close();
                Thread.sleep(seconds * 1000L);
            }
            c.commit();
            return "committed";
        });
    }

    @PostMapping("/admin/db-lab/ddl")
    public Job ddl(@RequestParam String name, @RequestParam(defaultValue = "0") long lockTimeoutMs,
                   @RequestParam(defaultValue = "0") int retries, @RequestParam(defaultValue = "64") int maintenanceWorkMemMb) {
        String sql = DDL.get(name);
        if (sql == null) {
            throw new IllegalArgumentException("Unknown DDL '" + name + "'. Known: " + DDL.keySet());
        }
        return background(name + (lockTimeoutMs > 0 ? " (lock_timeout " + lockTimeoutMs + " ms, " + retries + " retries)" : ""), c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SET application_name = 'migration'");
                s.execute("SET maintenance_work_mem = '" + maintenanceWorkMemMb + "MB'");
                if (lockTimeoutMs > 0) {
                    s.execute("SET lock_timeout = '" + lockTimeoutMs + "ms'");
                }
                for (int attempt = 0; ; attempt++) {
                    try {
                        s.execute(sql);
                        return attempt == 0 ? "done" : "done after " + attempt + " lock timeouts";
                    } catch (SQLException e) {
                        boolean lockTimeout = "55P03".equals(e.getSQLState());
                        if (!lockTimeout || attempt >= retries) {
                            throw e;
                        }
                        Thread.sleep(Math.min(5000, 500L * (attempt + 1)));   // let the queued queries through, then try again
                    }
                }
            }
        });
    }

    /**
     * Sets orders.coupon_code = 'NONE' on every row (run "add-column" first).
     * single: one UPDATE, one transaction, every row locked until the end.
     * batched: 10,000 ids per transaction (walking the primary key), short pause between batches.
     */
    @PostMapping("/admin/db-lab/backfill")
    public Job backfill(@RequestParam(defaultValue = "single") String mode, @RequestParam(defaultValue = "10000") int batch,
                        @RequestParam(defaultValue = "50") int pauseMs) {
        return background("backfill coupon_code (" + mode + ")", c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SET application_name = 'backfill'");
                if (mode.equals("single")) {
                    return s.executeUpdate("UPDATE orders SET coupon_code = 'NONE' WHERE coupon_code IS NULL") + " rows in one transaction";
                }
                // Walk the primary key in ranges. ("WHERE coupon_code IS NULL LIMIT n" looks simpler, but every
                // batch rescans all the rows already updated: the job gets slower and slower.)
                long maxId;
                try (var rs = s.executeQuery("SELECT coalesce(max(id), 0) FROM orders")) {
                    rs.next();
                    maxId = rs.getLong(1);
                }
                long total = 0;
                int batches = 0;
                for (long from = 0; from < maxId; from += batch) {
                    total += s.executeUpdate("UPDATE orders SET coupon_code = 'NONE' WHERE id > %d AND id <= %d AND coupon_code IS NULL"
                            .formatted(from, from + batch));
                    batches++;
                    Thread.sleep(pauseMs);
                }
                return total + " rows in " + batches + " transactions";
            }
        });
    }

    @PostMapping("/admin/db-lab/reset")
    public Map<String, Object> reset() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            for (String sql : RESET) {
                s.execute(sql);
            }
            s.execute("UPDATE customers c SET phone = '+9190' || lpad(c.id::text, 8, '0') WHERE phone = ''");
            s.execute("VACUUM (ANALYZE) orders");
        }
        jobs.clear();
        return Map.of("reset", true);
    }

    /** Customers whose two phone columns disagree, while both exist (scenario 4.4). -1 if not applicable. */
    @GetMapping("/admin/db-lab/phone-drift")
    public Map<String, Object> phoneDrift() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement();
             var rs = s.executeQuery("""
                     SELECT CASE WHEN count(*) = 2 THEN 1 ELSE 0 END FROM information_schema.columns
                     WHERE table_name = 'customers' AND column_name IN ('phone', 'phone_number')
                     """)) {
            rs.next();
            if (rs.getInt(1) == 0) {
                return Map.of("drift", -1);
            }
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM customers WHERE phone_number IS DISTINCT FROM phone")) {
            rs.next();
            return Map.of("drift", rs.getLong(1));
        }
    }

    /**
     * Makes the replica apply WAL {@code ms} later than it receives it (recovery_min_apply_delay): a
     * replica that is behind, on demand (scenario 5.1). Real lag comes from load, long queries on the
     * replica, or a slow network — the effect on readers is the same.
     */
    @PostMapping("/admin/db-lab/replica-delay")
    public Map<String, Object> replicaDelay(@RequestParam long ms) throws SQLException {
        try (Connection c = DriverManager.getConnection(replicaUrl, user, password); Statement s = c.createStatement()) {
            s.execute("ALTER SYSTEM SET recovery_min_apply_delay = '" + ms + "ms'");
            s.execute("SELECT pg_reload_conf()");
        }
        return replicaStatus();
    }

    @GetMapping("/admin/db-lab/replica-status")
    public Map<String, Object> replicaStatus() throws SQLException {
        try (Connection c = DriverManager.getConnection(replicaUrl, user, password); Statement s = c.createStatement();
             var rs = s.executeQuery("""
                     SELECT pg_is_in_recovery(), current_setting('recovery_min_apply_delay'),
                            coalesce(extract(epoch FROM now() - pg_last_xact_replay_timestamp()), 0)
                     """)) {
            rs.next();
            return Map.of("isReplica", rs.getBoolean(1), "applyDelay", rs.getString(2), "secondsBehind", rs.getDouble(3));
        }
    }

    /** Size of a table including indexes and TOAST: shows bloat after a big UPDATE (scenario 4.5). */
    @GetMapping("/admin/db-lab/table-size")
    public Map<String, Object> tableSize(@RequestParam(defaultValue = "orders") String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password);
             var ps = c.prepareStatement("SELECT pg_size_pretty(pg_total_relation_size(?::regclass)), n_dead_tup FROM pg_stat_user_tables WHERE relname = ?")) {
            ps.setString(1, name);
            ps.setString(2, name);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return Map.of("table", name, "size", rs.getString(1), "deadRows", rs.getLong(2));
            }
        }
    }

    /** Indexes on orders with Postgres' own view of whether they are usable (scenario 4.2). */
    @GetMapping("/admin/db-lab/indexes")
    public List<Map<String, Object>> indexes() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement();
             var rs = s.executeQuery("""
                     SELECT ic.relname, i.indisvalid, pg_size_pretty(pg_relation_size(ic.oid))
                     FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid JOIN pg_class t ON t.oid = i.indrelid
                     WHERE t.relname = 'orders' ORDER BY ic.relname
                     """)) {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            while (rs.next()) {
                out.add(Map.of("index", rs.getString(1), "valid", rs.getBoolean(2), "size", rs.getString(3)));
            }
            return out;
        }
    }

    @GetMapping("/admin/db-lab/jobs")
    public List<Job> jobs() {
        return jobs.values().stream().sorted((a, b) -> Long.compare(a.id(), b.id())).toList();
    }

    private interface Work {
        String run(Connection c) throws Exception;
    }

    private Job background(String what, Work work) {
        long id = ids.incrementAndGet();
        Instant start = Instant.now();
        jobs.put(id, new Job(id, what, start, null, null, "running"));
        Thread.ofPlatform().name("db-lab-" + id).start(() -> {
            String result;
            try (Connection c = DriverManager.getConnection(url, user, password)) {
                result = work.run(c);
            } catch (Exception e) {
                result = "FAILED: " + e.getMessage();
            }
            Instant end = Instant.now();
            jobs.put(id, new Job(id, what, start, end, end.toEpochMilli() - start.toEpochMilli(), result));
        });
        return jobs.get(id);
    }
}
