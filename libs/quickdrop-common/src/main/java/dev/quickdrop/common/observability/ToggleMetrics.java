package dev.quickdrop.common.observability;

import java.util.List;

import dev.quickdrop.common.toggles.ToggleChangedEvent;
import dev.quickdrop.common.toggles.ToggleRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Exports {@code quickdrop_toggle_info{toggle="...",value="..."} 1} for the current value of every
 * toggle, so dashboards can show which strategy each instance is running.
 */
public class ToggleMetrics {

    private final ToggleRegistry toggles;
    private final MeterRegistry meters;
    private final MultiGauge info;

    public ToggleMetrics(ToggleRegistry toggles, MeterRegistry meters) {
        this.toggles = toggles;
        this.meters = meters;
        this.info = MultiGauge.builder("quickdrop.toggle.info")
                .description("1 for the current value of each toggle")
                .register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    @EventListener
    public void onChange(ToggleChangedEvent event) {
        Counter.builder("quickdrop.toggle.changes")
                .description("Number of runtime toggle changes")
                .tag("toggle", event.toggle())
                .register(meters)
                .increment();
        refresh();
    }

    private void refresh() {
        List<MultiGauge.Row<?>> rows = toggles.definitions().stream()
                .<MultiGauge.Row<?>>map(d -> MultiGauge.Row.of(Tags.of("toggle", d.name(), "value", toggles.get(d)), 1))
                .toList();
        info.register(rows, true);
    }
}
