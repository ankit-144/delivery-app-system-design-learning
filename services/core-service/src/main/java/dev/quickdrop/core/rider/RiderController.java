package dev.quickdrop.core.rider;

import java.util.Map;

import dev.quickdrop.common.toggles.StrategySelector;
import dev.quickdrop.core.web.NotFoundException;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RiderController {

    private final RiderDao riders;
    private final StrategySelector<RiderLocator> locators;

    public RiderController(RiderDao riders, StrategySelector<RiderLocator> locators) {
        this.riders = riders;
        this.locators = locators;
    }

    public record LocationPing(@NotNull Double lat, @NotNull Double lon) {
    }

    /** Riders' phones ping their location every few seconds. */
    @PostMapping("/api/riders/{id}/location")
    public ResponseEntity<Void> location(@PathVariable long id, @RequestBody LocationPing ping) {
        RiderDao.PingResult rider = riders.updateLocation(id, ping.lat(), ping.lon()).orElseThrow(() -> new NotFoundException("rider", id));
        locators.current().onLocationUpdate(id, rider.cityId(), ping.lat(), ping.lon(), rider.available());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/riders/{id}/online")
    public Map<String, Object> online(@PathVariable long id) {
        return Map.of("riderId", id, "changed", riders.setAvailability(id, true));
    }

    @PostMapping("/api/riders/{id}/offline")
    public Map<String, Object> offline(@PathVariable long id) {
        return Map.of("riderId", id, "changed", riders.setAvailability(id, false));
    }
}
