package dev.quickdrop.common.toggles;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the strategy whose name matches the current toggle value. Looked up on every call, so a
 * runtime toggle flip takes effect on the very next request.
 */
public class StrategySelector<T extends NamedStrategy> {

    private final ToggleRegistry toggles;
    private final ToggleDefinition toggle;
    private final Map<String, T> strategies;

    public StrategySelector(ToggleRegistry toggles, ToggleDefinition toggle, List<T> implementations) {
        this.toggles = toggles;
        this.toggle = toggle;
        this.strategies = implementations.stream().collect(Collectors.toMap(NamedStrategy::strategyName, Function.identity()));
        for (String name : strategies.keySet()) {
            if (!toggle.allowedValues().contains(name)) {
                throw new IllegalStateException("Strategy '%s' is not a value of toggle %s %s".formatted(name, toggle.name(), toggle.allowedValues()));
            }
        }
        for (String value : toggle.allowedValues()) {
            if (!strategies.containsKey(value)) {
                throw new IllegalStateException("Toggle %s allows '%s' but no strategy implements it".formatted(toggle.name(), value));
            }
        }
    }

    public T current() {
        return strategies.get(toggles.get(toggle));
    }

    public String currentName() {
        return toggles.get(toggle);
    }
}
