package dev.quickdrop.core.rider.strategy;

import java.util.List;

import dev.quickdrop.core.catalog.Store;
import dev.quickdrop.core.rider.RiderCandidate;
import dev.quickdrop.core.rider.RiderDao;
import dev.quickdrop.core.rider.RiderLocator;
import org.springframework.stereotype.Component;

/** Bounding-box query: Postgres filters to riders within ~5 km and returns only the nearest few. */
@Component
public class DbNearbyLocator implements RiderLocator {

    private final RiderDao riders;

    public DbNearbyLocator(RiderDao riders) {
        this.riders = riders;
    }

    @Override
    public String strategyName() {
        return "db-nearby";
    }

    @Override
    public List<RiderCandidate> nearestAvailable(Store store, int limit) {
        return riders.nearestAvailable(store.getCityId(), store.getLat(), store.getLon(), SEARCH_RADIUS_KM, limit);
    }
}
