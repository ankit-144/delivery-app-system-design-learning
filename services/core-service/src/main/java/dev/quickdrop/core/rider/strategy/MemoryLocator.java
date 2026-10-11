package dev.quickdrop.core.rider.strategy;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.quickdrop.common.toggles.ToggleChangedEvent;
import dev.quickdrop.core.catalog.Store;
import dev.quickdrop.core.rider.Geo;
import dev.quickdrop.core.rider.RiderCandidate;
import dev.quickdrop.core.rider.RiderDao;
import dev.quickdrop.core.rider.RiderLocator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * BROKEN WITH MORE THAN ONE INSTANCE — scenario 3.4. Available riders' positions, kept in a map in
 * this JVM: added on pings and when this instance frees a rider, removed when this instance assigns one.
 * Right with one instance. With three, each instance only sees the pings, assignments and deliveries
 * the load balancer happens to send it: its picture of who is free, and where, is partial and stale —
 * and after a restart it knows nobody at all.
 */
@Component
public class MemoryLocator implements RiderLocator {

    private record Position(short cityId, double lat, double lon) {
    }

    private final Map<Long, Position> positions = new ConcurrentHashMap<>();
    private final RiderDao riders;

    public MemoryLocator(RiderDao riders) {
        this.riders = riders;
    }

    @Override
    public String strategyName() {
        return "memory";
    }

    @Override
    public void onLocationUpdate(long riderId, short cityId, double lat, double lon, boolean available) {
        if (available) {
            positions.put(riderId, new Position(cityId, lat, lon));
        } else {
            positions.remove(riderId);
        }
    }

    @Override
    public void onRiderBusy(long riderId, short cityId) {
        positions.remove(riderId);
    }

    @Override
    public void onRiderAvailable(long riderId, short cityId, double lat, double lon) {
        positions.put(riderId, new Position(cityId, lat, lon));
    }

    @Override
    public List<RiderCandidate> nearestAvailable(Store store, int limit) {
        List<RiderCandidate> nearest = positions.entrySet().stream()
                .filter(e -> e.getValue().cityId() == store.getCityId())
                .map(e -> new RiderCandidate(e.getKey(), Geo.haversineKm(store.getLat(), store.getLon(), e.getValue().lat(), e.getValue().lon())))
                .filter(c -> c.distanceKm() <= SEARCH_RADIUS_KM)
                .sorted(Comparator.comparingDouble(RiderCandidate::distanceKm))
                .limit(limit * 3L)
                .toList();
        return riders.keepAvailable(nearest).stream().limit(limit).toList();
    }

    /** Switching to this locator starts from an empty map, like a freshly deployed instance. */
    @EventListener
    public void onToggle(ToggleChangedEvent event) {
        if (event.toggle().equals("rider-locator")) {
            positions.clear();
        }
    }

    public int known() {
        return positions.size();
    }
}
