package dev.quickdrop.common.toggles;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;

/**
 * Holds the current value of every toggle on this instance.
 *
 * <p>Values start from {@code quickdrop.toggles.<name>} (so they can come from env vars such as
 * {@code QUICKDROP_TOGGLES_INVENTORYRESERVATION}) and can be changed at runtime through
 * {@code PUT /admin/toggles/<name>}. Each instance has its own registry: flipping a toggle on
 * one instance does not change the others. {@code scripts/toggle.sh} flips every instance.
 */
public class ToggleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToggleRegistry.class);

    private final Map<String, ToggleDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, String> initial = new LinkedHashMap<>();
    private final ApplicationEventPublisher events;
    private final String instance;

    public ToggleRegistry(Collection<ToggleCatalog> catalogs, Environment env, ApplicationEventPublisher events, String instance) {
        this.events = events;
        this.instance = instance;
        for (ToggleCatalog catalog : catalogs) {
            for (ToggleDefinition definition : catalog.toggles()) {
                if (definitions.putIfAbsent(definition.name(), definition) != null) {
                    throw new IllegalStateException("Toggle declared twice: " + definition.name());
                }
                String configured = definition.validate(env.getProperty("quickdrop.toggles." + definition.name(), definition.defaultValue()));
                values.put(definition.name(), configured);
                initial.put(definition.name(), configured);
            }
        }
        log.info("Toggles on {}: {}", instance, values);
    }

    public String get(ToggleDefinition toggle) {
        String value = values.get(toggle.name());
        if (value == null) {
            throw new IllegalArgumentException("Unknown toggle " + toggle.name() + " — is its catalog registered as a bean?");
        }
        return value;
    }

    public long getLong(ToggleDefinition toggle) {
        return Long.parseLong(get(toggle));
    }

    public boolean is(ToggleDefinition toggle, String value) {
        return get(toggle).equals(value);
    }

    public String set(String name, String value) {
        ToggleDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown toggle '%s'. Known toggles: %s".formatted(name, definitions.keySet()));
        }
        String newValue = definition.validate(value);
        String oldValue = values.put(name, newValue);
        if (!newValue.equals(oldValue)) {
            log.warn("TOGGLE {} changed on {}: {} -> {}", name, instance, oldValue, newValue);
            events.publishEvent(new ToggleChangedEvent(name, oldValue, newValue, instance));
        }
        return newValue;
    }

    /** Puts every toggle back to its startup value. Every scenario starts with this. */
    public void resetAll() {
        initial.forEach(this::set);
    }

    public List<ToggleDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    public String instance() {
        return instance;
    }
}
