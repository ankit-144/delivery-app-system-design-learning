package dev.quickdrop.core.sharding;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import org.springframework.stereotype.Component;

/**
 * Which shard owns an order — scenario 5.2, {@code shard-key}:
 * <ul>
 *   <li>{@code city}: by the store's city. An order and its store live together, so "today's orders in
 *       store 4" is one shard — but a customer who orders in two cities has orders on two shards, and
 *       one big city makes one shard hot (5.5).</li>
 *   <li>{@code customer}: by a hash of the customer id. "My orders" is one shard and load spreads evenly —
 *       but a store's orders are scattered over every shard.</li>
 * </ul>
 */
@Component
public class ShardRouter {

    private final ToggleRegistry toggles;
    private final Shards shards;

    public ShardRouter(ToggleRegistry toggles, Shards shards) {
        this.toggles = toggles;
        this.shards = shards;
    }

    public int shardFor(long customerId, int cityId) {
        int n = shards.count();
        return toggles.is(CoreToggles.SHARD_KEY, "city") ? Math.floorMod(cityId - 1, n) : Math.floorMod(mix(customerId), n);
    }

    /** True if every order of a customer is known to be on one shard. */
    public boolean customerIsOnOneShard() {
        return toggles.is(CoreToggles.SHARD_KEY, "customer");
    }

    /** True if every order of a store is known to be on one shard. */
    public boolean storeIsOnOneShard() {
        return toggles.is(CoreToggles.SHARD_KEY, "city");
    }

    /**
     * MurmurHash3's 64-bit finalizer: spreads nearby inputs (customer 41, 42, 43...) all over the 64-bit
     * range. A single multiply is not enough — evenly spaced inputs would come out evenly spaced, which
     * skews a consistent-hash ring badly (scenario 5.6).
     */
    static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }
}
