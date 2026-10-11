package dev.quickdrop.common.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationView;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Phase 8. Every service runs background jobs: the outbox relay polls every 200 ms, the invariant checker every
 * 5 s, rate limiters touch Redis. With tracing on, each of their SQL queries and Redis commands would become a
 * trace of its own: thousands of one-span traces burying the ones you're looking for.
 *
 * <p>Rules: a SQL or Redis span is only recorded inside something worth tracing (an HTTP request, a Kafka
 * message), and actuator requests (scrapes, health checks) aren't traced. With tracing off, Redis commands aren't observed at all, so the Redis lab (R5) measures Redis, not
 * instrumentation.
 */
public class TracingNoiseFilter implements ObservationPredicate {

    private final boolean tracingEnabled;

    public TracingNoiseFilter(boolean tracingEnabled) {
        this.tracingEnabled = tracingEnabled;
    }

    @Override
    public boolean test(String name, Observation.Context context) {
        if (tracingEnabled && context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith("/actuator")) {
            return false;   // Prometheus scrapes and health checks: a trace every 5 s per service, of nothing
        }
        if (!isLeaf(name)) {
            return true;
        }
        if (!tracingEnabled) {
            return false;
        }
        // Keep it only if something real is above it. A query's parent is its connection span; if that was
        // dropped, the parent is a no-op placeholder (no name), so walk up rather than check "parent != null".
        ObservationView parent = context.getParentObservation();
        while (parent != null) {
            String parentName = parent.getContextView().getName();
            if (parentName == null) {
                return false;
            }
            if (!isLeaf(parentName)) {
                return true;
            }
            parent = parent.getContextView().getParentObservation();
        }
        return false;
    }

    private static boolean isLeaf(String name) {
        return name.startsWith("jdbc") || name.startsWith("lettuce") || name.startsWith("redis");
    }
}
