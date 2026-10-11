package dev.quickdrop.core.rider.strategy;

import java.util.Comparator;
import java.util.List;

import dev.quickdrop.core.catalog.Store;
import dev.quickdrop.core.rider.Geo;
import dev.quickdrop.core.rider.RiderCandidate;
import dev.quickdrop.core.rider.RiderLocator;
import dev.quickdrop.core.rider.RiderRepository;
import org.springframework.stereotype.Component;

/**
 * SLOW — scenario 2.6. Load every rider in the city as a JPA entity, filter and sort in Java.
 * Fine with 50 riders in a demo, burns CPU and memory with thousands, on every single order.
 */
@Component
public class DbScanLocator implements RiderLocator {

    private final RiderRepository riders;

    public DbScanLocator(RiderRepository riders) {
        this.riders = riders;
    }

    @Override
    public String strategyName() {
        return "db-scan";
    }

    @Override
    public List<RiderCandidate> nearestAvailable(Store store, int limit) {
        return riders.findByCityId(store.getCityId()).stream()
                .filter(r -> "AVAILABLE".equals(r.getStatus()))
                .map(r -> new RiderCandidate(r.getId(), Geo.haversineKm(store.getLat(), store.getLon(), r.getLat(), r.getLon())))
                .filter(c -> c.distanceKm() <= SEARCH_RADIUS_KM)
                .sorted(Comparator.comparingDouble(RiderCandidate::distanceKm))
                .limit(limit)
                .toList();
    }
}
