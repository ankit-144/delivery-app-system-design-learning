package dev.quickdrop.core.rider;

import java.util.List;
import java.util.Optional;

import dev.quickdrop.common.toggles.NamedStrategy;

/** Claims one of the candidate riders for an order. Runs inside the order transaction. */
public interface RiderAssignment extends NamedStrategy {

    Optional<Long> assign(long orderId, List<RiderCandidate> candidates);
}
