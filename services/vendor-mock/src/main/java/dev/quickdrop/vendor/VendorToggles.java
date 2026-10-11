package dev.quickdrop.vendor;

import static dev.quickdrop.common.toggles.ToggleDefinition.choice;
import static dev.quickdrop.common.toggles.ToggleDefinition.number;

import java.util.List;

import dev.quickdrop.common.toggles.ToggleCatalog;
import dev.quickdrop.common.toggles.ToggleDefinition;
import org.springframework.stereotype.Component;

/** Knobs that make the vendor behave like a real third party on a bad day. */
@Component
public class VendorToggles implements ToggleCatalog {

    public static final ToggleDefinition LATENCY_MS = number("latency-ms", 100,
            "Base response time of every send (scenario 2.1 sets it to 1000).");

    public static final ToggleDefinition JITTER_MS = number("jitter-ms", 20,
            "Random extra latency between 0 and this value.");

    public static final ToggleDefinition SLOW_PERCENT = number("slow-percent", 0,
            "Percentage of sends that take slow-ms instead: the long tail every real vendor has (scenario 0.2).");

    public static final ToggleDefinition SLOW_MS = number("slow-ms", 2000,
            "Latency of the slow sends.");

    public static final ToggleDefinition ERROR_RATE_PERCENT = number("error-rate-percent", 0,
            "Percentage of sends that fail with HTTP 500.");

    public static final ToggleDefinition RATE_LIMIT_RPS = number("rate-limit-rps", 0,
            "Sends allowed per second across all callers; the rest get HTTP 429. 0 = unlimited (scenario 6.7).");

    public static final ToggleDefinition MAX_CONCURRENCY = number("max-concurrency", 0,
            "Requests processed at once; the rest get HTTP 503. 0 = unlimited.");

    public static final ToggleDefinition OVERLOAD_MODE = choice("overload-mode", "reject",
            "Beyond max-concurrency: reject at once with 503, or queue (requests wait their turn; the vendor keeps "
                    + "working on requests whose callers have already timed out — scenario 8.3).", "reject", "queue");

    @Override
    public List<ToggleDefinition> toggles() {
        return List.of(LATENCY_MS, JITTER_MS, SLOW_PERCENT, SLOW_MS, ERROR_RATE_PERCENT, RATE_LIMIT_RPS, MAX_CONCURRENCY, OVERLOAD_MODE);
    }
}
