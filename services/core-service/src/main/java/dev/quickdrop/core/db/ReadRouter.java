package dev.quickdrop.core.db;

import java.time.Duration;
import java.util.function.Supplier;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Decides where a customer's reads go — scenario 5.1, {@code read-routing}:
 * <ul>
 *   <li>{@code primary}: everything on the primary. Always consistent, all load on one database.</li>
 *   <li>{@code replica}: reads on the replica. Takes load off the primary, but the replica is behind by
 *       its replication lag: a customer who just ordered may not see the order.</li>
 *   <li>{@code replica-ryw}: read-your-writes. For a few seconds after a customer writes, their reads go
 *       to the primary; everyone else's go to the replica. The "recent writer" marker lives in Redis so
 *       every instance sees it.</li>
 * </ul>
 */
@Component
public class ReadRouter {

    private final ToggleRegistry toggles;
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    public ReadRouter(ToggleRegistry toggles, StringRedisTemplate redis, MeterRegistry meters) {
        this.toggles = toggles;
        this.redis = redis;
        this.meters = meters;
        for (String target : new String[] {"primary", "replica"}) {
            meters.counter("quickdrop.reads.routed", "target", target);
        }
    }

    public <T> T read(long customerId, Supplier<T> work) {
        boolean replica = switch (toggles.get(CoreToggles.READ_ROUTING)) {
            case "replica" -> true;
            case "replica-ryw" -> !recentWriter(customerId);
            default -> false;
        };
        meters.counter("quickdrop.reads.routed", "target", replica ? "replica" : "primary").increment();
        return replica ? ReadRouting.onReplica(work) : work.get();
    }

    /** Called after a customer's write has committed. */
    public void wrote(long customerId) {
        if (!toggles.is(CoreToggles.READ_ROUTING, "replica-ryw")) {
            return;
        }
        try {
            redis.opsForValue().set(key(customerId), "1", Duration.ofSeconds(toggles.getLong(CoreToggles.RYW_WINDOW_S)));
        } catch (RuntimeException e) {
            // If we can't remember the write, the worst case is a stale read — the same as plain "replica".
        }
    }

    private boolean recentWriter(long customerId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(customerId)));
        } catch (RuntimeException e) {
            return true;   // can't tell: play safe, read from the primary
        }
    }

    private static String key(long customerId) {
        return "ryw:customer:" + customerId;
    }
}
