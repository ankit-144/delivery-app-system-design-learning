package dev.quickdrop.common.toggles;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * curl localhost:8081/admin/toggles
 * curl -X PUT 'localhost:8081/admin/toggles/inventory-reservation?value=atomic'
 * curl -X POST localhost:8081/admin/toggles/reset
 * </pre>
 */
@RestController
@RequestMapping("/admin/toggles")
public class ToggleController {

    private final ToggleRegistry toggles;

    public ToggleController(ToggleRegistry toggles) {
        this.toggles = toggles;
    }

    public record ToggleView(String name, String value, String defaultValue, List<String> allowedValues, String description) {
    }

    @GetMapping
    public Map<String, Object> list() {
        List<ToggleView> views = toggles.definitions().stream()
                .map(d -> new ToggleView(d.name(), toggles.get(d), d.defaultValue(), d.allowedValues(), d.description()))
                .toList();
        return Map.of("instance", toggles.instance(), "toggles", views);
    }

    @PutMapping("/{name}")
    public Map<String, String> set(@PathVariable String name,
                                   @RequestParam(required = false) String value,
                                   @RequestBody(required = false) Map<String, String> body) {
        String requested = value != null ? value : body == null ? null : body.get("value");
        String applied = toggles.set(name, requested);
        return Map.of("instance", toggles.instance(), "toggle", name, "value", applied);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        toggles.resetAll();
        return list();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badToggle(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
