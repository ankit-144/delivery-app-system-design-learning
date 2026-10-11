package dev.quickdrop.core.rider;

import java.util.List;

import dev.quickdrop.common.toggles.NamedStrategy;
import dev.quickdrop.core.catalog.Store;

/** Finds available riders near a store, nearest first. Selected by the {@code rider-locator} toggle. */
public interface RiderLocator extends NamedStrategy {

    double SEARCH_RADIUS_KM = 5.0;

    List<RiderCandidate> nearestAvailable(Store store, int limit);

    /** Called on every location ping, after the database row has been updated. */
    default void onLocationUpdate(long riderId, short cityId, double lat, double lon, boolean available) {
    }

    /** A rider was just assigned an order on this instance. */
    default void onRiderBusy(long riderId, short cityId) {
    }

    /** A rider finished a delivery (freed by this instance) and can take orders again. */
    default void onRiderAvailable(long riderId, short cityId, double lat, double lon) {
    }
}
