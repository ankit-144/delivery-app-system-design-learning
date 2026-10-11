package dev.quickdrop.common.toggles;

/** An implementation that can be picked by a choice toggle. {@link #strategyName()} must match a toggle value. */
public interface NamedStrategy {
    String strategyName();
}
