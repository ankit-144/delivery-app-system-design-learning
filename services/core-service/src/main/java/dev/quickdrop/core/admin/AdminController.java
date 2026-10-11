package dev.quickdrop.core.admin;

import java.util.List;
import java.util.Map;

import dev.quickdrop.core.rider.strategy.MemoryLocator;
import dev.quickdrop.core.rider.strategy.RedisGeoLocator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * curl localhost:8081/admin/invariants
 * curl -X POST localhost:8081/admin/fixtures/reset -H 'Content-Type: application/json' \
 *      -d '{"stock":[{"storeId":1,"productId":1,"quantity":10}],"riders":{"storeId":1,"available":5}}'
 * </pre>
 */
@RestController
public class AdminController {

    private final InvariantChecker invariants;
    private final FixturesService fixtures;
    private final RedisGeoLocator geo;
    private final MemoryLocator memory;

    public AdminController(InvariantChecker invariants, FixturesService fixtures, RedisGeoLocator geo, MemoryLocator memory) {
        this.invariants = invariants;
        this.fixtures = fixtures;
        this.geo = geo;
        this.memory = memory;
    }

    /** Copies every rider's last known position from Postgres into Redis GEO (scenario 3.4). */
    @PostMapping("/admin/riders/geo-sync")
    public Map<String, Object> geoSync() {
        return Map.of("ridersLoaded", geo.syncAll());
    }

    /** How many riders this instance's in-memory locator knows about. */
    @GetMapping("/admin/riders/memory")
    public Map<String, Object> memoryLocator() {
        return Map.of("knownRiders", memory.known());
    }

    @GetMapping("/admin/invariants")
    public List<InvariantChecker.Result> invariants(@RequestParam(defaultValue = "5") int samples) {
        return invariants.check(samples);
    }

    @PostMapping("/admin/fixtures/reset")
    public Map<String, Object> reset(@RequestBody(required = false) FixturesService.ResetRequest request) {
        return fixtures.reset(request == null ? new FixturesService.ResetRequest(null, 0, null, null) : request);
    }
}
