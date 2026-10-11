package dev.quickdrop.core.rider.strategy;

import java.util.List;
import java.util.Optional;

import dev.quickdrop.core.config.Chaos;
import dev.quickdrop.core.rider.RiderAssignment;
import dev.quickdrop.core.rider.RiderCandidate;
import dev.quickdrop.core.rider.RiderDao;
import org.springframework.stereotype.Component;

/**
 * BROKEN — scenario 1.2. "Is the rider available? Yes → mark busy."
 *
 * <p>Every order at the same store sees the same nearest rider as available. Each request checks,
 * each request writes, and one rider ends up carrying several orders while the customers wait.
 */
@Component
public class NaiveAssignment implements RiderAssignment {

    private final RiderDao riders;
    private final Chaos chaos;

    public NaiveAssignment(RiderDao riders, Chaos chaos) {
        this.riders = riders;
        this.chaos = chaos;
    }

    @Override
    public String strategyName() {
        return "naive";
    }

    @Override
    public Optional<Long> assign(long orderId, List<RiderCandidate> candidates) {
        for (RiderCandidate candidate : candidates) {
            if (riders.status(candidate.riderId()).filter("AVAILABLE"::equals).isPresent()) {
                chaos.raceWindow();
                riders.markBusy(candidate.riderId(), orderId);
                return Optional.of(candidate.riderId());
            }
        }
        return Optional.empty();
    }
}
