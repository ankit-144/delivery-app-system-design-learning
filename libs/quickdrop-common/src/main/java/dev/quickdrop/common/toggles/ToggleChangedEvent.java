package dev.quickdrop.common.toggles;

public record ToggleChangedEvent(String toggle, String oldValue, String newValue, String instance) {
}
