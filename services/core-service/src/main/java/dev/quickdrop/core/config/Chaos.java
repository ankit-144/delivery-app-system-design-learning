package dev.quickdrop.core.config;

import dev.quickdrop.common.toggles.ToggleRegistry;
import org.springframework.stereotype.Component;

/** Helpers for the artificial delays used to make problems visible. */
@Component
public class Chaos {

    private final ToggleRegistry toggles;

    public Chaos(ToggleRegistry toggles) {
        this.toggles = toggles;
    }

    /** Sleeps for {@code race-window-ms}: the gap between "check" and "act" in the naive strategies. */
    public void raceWindow() {
        sleep(toggles.getLong(CoreToggles.RACE_WINDOW_MS));
    }

    /** Sleeps for {@code per-line-delay-ms}: per-item work done while a stock row is locked. */
    public void perLine() {
        sleep(toggles.getLong(CoreToggles.PER_LINE_DELAY_MS));
    }

    public static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
