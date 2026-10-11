package dev.quickdrop.common.toggles;

import java.util.List;

/** Chaos knobs every service has. */
public class CommonToggles implements ToggleCatalog {

    public static final ToggleDefinition REQUEST_DELAY_MS = ToggleDefinition.number("request-delay-ms", 0,
            "Adds this many milliseconds to every /api request on this instance. Used to make one instance slow (scenario 3.7).");

    public static final ToggleDefinition ERROR_PERCENT = ToggleDefinition.number("error-percent", 0,
            "Percentage of matching /api requests on this instance that fail with HTTP 500 (alerts, game day: Phase 8).");

    public static final ToggleDefinition ERROR_SCOPE = ToggleDefinition.choice("error-scope", "all",
            "Which requests error-percent applies to: every /api request, or only checkout (POST /api/orders).",
            "all", "checkout");

    @Override
    public List<ToggleDefinition> toggles() {
        return List.of(REQUEST_DELAY_MS, ERROR_PERCENT, ERROR_SCOPE);
    }
}
