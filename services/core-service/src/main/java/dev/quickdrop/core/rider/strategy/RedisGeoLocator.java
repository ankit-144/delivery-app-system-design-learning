package dev.quickdrop.core.rider.strategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.catalog.Store;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.redis.RedisUnavailable;
import dev.quickdrop.core.rider.RiderCandidate;
import dev.quickdrop.core.rider.RiderDao;
import dev.quickdrop.core.rider.RiderLocator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

/**
 * FIX — scenario 3.4. Available riders' positions live in a Redis GEO set per city (a sorted set keyed by
 * geohash), shared by every instance:
 * <pre>
 * GEOADD    riders:available:1 77.64 12.97 "4211"     ping from an available rider, or a rider just freed
 * ZREM      riders:available:1 "4211"                 rider assigned (or pinging while busy)
 * GEOSEARCH riders:available:1 FROMLONLAT 77.64 12.97 BYRADIUS 5 km ASC COUNT 60    match an order
 * </pre>
 * Redis is an <em>index</em>; Postgres stays the source of truth. Every status change is written to both —
 * a dual write — so they can drift: we re-check availability in Postgres before assigning, and
 * {@code POST /admin/riders/geo-sync} rebuilds the set from Postgres. If Redis is down and
 * {@code redis-failure-mode=degrade}, we fall back to the database query.
 */
@Component
public class RedisGeoLocator implements RiderLocator {

    private final StringRedisTemplate redis;
    private final RiderDao riders;
    private final DbNearbyLocator fallback;
    private final ToggleRegistry toggles;
    private final MeterRegistry meters;

    public RedisGeoLocator(StringRedisTemplate redis, RiderDao riders, DbNearbyLocator fallback, ToggleRegistry toggles,
                           MeterRegistry meters) {
        this.redis = redis;
        this.riders = riders;
        this.fallback = fallback;
        this.toggles = toggles;
        this.meters = meters;
    }

    @Override
    public String strategyName() {
        return "redis-geo";
    }

    @Override
    public void onLocationUpdate(long riderId, short cityId, double lat, double lon, boolean available) {
        if (available) {
            onRiderAvailable(riderId, cityId, lat, lon);
        } else {
            onRiderBusy(riderId, cityId);
        }
    }

    @Override
    public void onRiderBusy(long riderId, short cityId) {
        quietly(() -> redis.opsForGeo().remove(key(cityId), Long.toString(riderId)));
    }

    @Override
    public void onRiderAvailable(long riderId, short cityId, double lat, double lon) {
        quietly(() -> redis.opsForGeo().add(key(cityId), new Point(lon, lat), Long.toString(riderId)));
    }

    /** Index updates must not fail the request when degrading; the next ping or geo-sync repairs the index. */
    private void quietly(Runnable update) {
        try {
            update.run();
        } catch (RuntimeException e) {
            if (!(RedisUnavailable.is(e) && toggles.is(CoreToggles.REDIS_FAILURE_MODE, "degrade"))) {
                throw e;
            }
        }
    }

    @Override
    public List<RiderCandidate> nearestAvailable(Store store, int limit) {
        try {
            var results = redis.opsForGeo().search(key(store.getCityId()),
                    GeoReference.fromCoordinate(store.getLon(), store.getLat()),
                    new Distance(SEARCH_RADIUS_KM, Metrics.KILOMETERS),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(limit * 3L));
            if (results == null) {
                return List.of();
            }
            List<RiderCandidate> nearest = results.getContent().stream()
                    .map(r -> new RiderCandidate(Long.parseLong(r.getContent().getName()), r.getDistance().getValue()))
                    .toList();
            return riders.keepAvailable(nearest).stream().limit(limit).toList();
        } catch (RuntimeException e) {
            if (RedisUnavailable.is(e) && toggles.is(CoreToggles.REDIS_FAILURE_MODE, "degrade")) {
                meters.counter("quickdrop.rider.locator.fallbacks").increment();
                return fallback.nearestAvailable(store, limit);
            }
            throw e;
        }
    }

    /** Rebuilds the sets from Postgres: every available rider at their last known position. */
    public int syncAll() {
        Map<String, Map<String, Point>> byCity = new HashMap<>();
        for (RiderDao.Position p : riders.availablePositions()) {
            byCity.computeIfAbsent(key(p.cityId()), k -> new HashMap<>()).put(Long.toString(p.riderId()), new Point(p.lon(), p.lat()));
        }
        int total = 0;
        for (var city : byCity.entrySet()) {
            redis.delete(city.getKey());
            redis.opsForGeo().add(city.getKey(), city.getValue());
            total += city.getValue().size();
        }
        return total;
    }

    private static String key(short cityId) {
        return "riders:available:" + cityId;
    }
}
