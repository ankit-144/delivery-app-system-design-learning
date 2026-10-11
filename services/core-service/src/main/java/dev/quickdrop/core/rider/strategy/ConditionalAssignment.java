package dev.quickdrop.core.rider.strategy;

import java.util.List;
import java.util.Optional;

import dev.quickdrop.core.rider.RiderAssignment;
import dev.quickdrop.core.rider.RiderCandidate;
import dev.quickdrop.core.rider.RiderDao;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * FIX — scenario 1.2. {@code UPDATE riders SET status='BUSY' ... WHERE id = ? AND status = 'AVAILABLE'}.
 * Postgres re-checks the condition after waiting for any concurrent writer, so only one order can
 * win a rider; the losers move on to the next candidate.
 */
@Component
public class ConditionalAssignment implements RiderAssignment {

    private final RiderDao riders;
    private final Counter lostRaces;

    public ConditionalAssignment(RiderDao riders, MeterRegistry meters) {
        this.riders = riders;
        this.lostRaces = Counter.builder("quickdrop.rider.assignment.lost.races")
                .description("Candidates that were taken by a concurrent order before we could claim them")
                .register(meters);
    }

    @Override
    public String strategyName() {
        return "conditional";
    }

    @Override
    public Optional<Long> assign(long orderId, List<RiderCandidate> candidates) {
        for (RiderCandidate candidate : candidates) {
            if (riders.markBusyIfAvailable(candidate.riderId(), orderId)) {
                return Optional.of(candidate.riderId());
            }
            lostRaces.increment();
        }
        return Optional.empty();
    }
}
