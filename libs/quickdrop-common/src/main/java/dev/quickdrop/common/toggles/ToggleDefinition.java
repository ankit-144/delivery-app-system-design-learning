package dev.quickdrop.common.toggles;

import java.util.List;

/**
 * One switch that changes how the system behaves. Most toggles pick between a "wrong" and a
 * "right" strategy; numeric toggles are knobs such as an artificial delay.
 *
 * @param name          kebab-case name, also the property suffix: {@code quickdrop.toggles.<name>}
 * @param defaultValue  value used when no property is set
 * @param allowedValues allowed values for a choice toggle; empty for a numeric toggle
 * @param description   what the toggle does, shown by {@code GET /admin/toggles}
 */
public record ToggleDefinition(String name, String defaultValue, List<String> allowedValues, String description) {

    public static ToggleDefinition choice(String name, String defaultValue, String description, String... values) {
        List<String> allowed = List.of(values);
        if (!allowed.contains(defaultValue)) {
            throw new IllegalArgumentException("Default '%s' is not one of %s for toggle %s".formatted(defaultValue, allowed, name));
        }
        return new ToggleDefinition(name, defaultValue, allowed, description);
    }

    public static ToggleDefinition number(String name, long defaultValue, String description) {
        return new ToggleDefinition(name, Long.toString(defaultValue), List.of(), description);
    }

    public boolean isNumeric() {
        return allowedValues.isEmpty();
    }

    /** Returns the normalised value, or throws if it is not valid for this toggle. */
    public String validate(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (isNumeric()) {
            try {
                return Long.toString(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Toggle %s expects a number, got '%s'".formatted(name, value));
            }
        }
        if (!allowedValues.contains(trimmed)) {
            throw new IllegalArgumentException("Toggle %s expects one of %s, got '%s'".formatted(name, allowedValues, value));
        }
        return trimmed;
    }
}
