package dev.quickdrop.core.config;

import java.util.List;

import dev.quickdrop.common.toggles.StrategySelector;
import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.inventory.InventoryReservation;
import dev.quickdrop.core.rider.RiderAssignment;
import dev.quickdrop.core.rider.RiderLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires each strategy family to the toggle that selects it. */
@Configuration
public class StrategyConfig {

    @Bean
    StrategySelector<InventoryReservation> inventoryReservations(ToggleRegistry toggles, List<InventoryReservation> all) {
        return new StrategySelector<>(toggles, CoreToggles.INVENTORY_RESERVATION, all);
    }

    @Bean
    StrategySelector<RiderAssignment> riderAssignments(ToggleRegistry toggles, List<RiderAssignment> all) {
        return new StrategySelector<>(toggles, CoreToggles.RIDER_ASSIGNMENT, all);
    }

    @Bean
    StrategySelector<RiderLocator> riderLocators(ToggleRegistry toggles, List<RiderLocator> all) {
        return new StrategySelector<>(toggles, CoreToggles.RIDER_LOCATOR, all);
    }
}
