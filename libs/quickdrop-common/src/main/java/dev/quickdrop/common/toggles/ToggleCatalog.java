package dev.quickdrop.common.toggles;

import java.util.List;

/** A service exposes its toggles by declaring a bean of this type. */
public interface ToggleCatalog {
    List<ToggleDefinition> toggles();
}
