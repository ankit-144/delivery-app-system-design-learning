package dev.quickdrop.notification;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertmanager's webhook receiver (Phase 8, scenario 8.2): the lab's stand-in for PagerDuty or a Slack channel.
 * Every notification is logged ({@code ALERT FIRING …}), counted, and kept in memory so a scenario script can
 * print the timeline of what would have woken someone up.
 *
 * <pre>
 * POST   /api/alerts   Alertmanager webhook payload
 * GET    /api/alerts   what arrived, oldest first
 * DELETE /api/alerts   forget everything (scenario start)
 * </pre>
 */
@RestController
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);
    private static final int KEEP = 1000;

    private final MeterRegistry meters;
    private final Deque<Map<String, Object>> received = new ArrayDeque<>();

    public AlertWebhookController(MeterRegistry meters) {
        this.meters = meters;
    }

    @PostMapping("/api/alerts")
    public Map<String, Object> receive(@RequestBody JsonNode payload) {
        int count = 0;
        for (JsonNode alert : payload.path("alerts")) {
            String status = alert.path("status").asText();
            String name = alert.path("labels").path("alertname").asText();
            String severity = alert.path("labels").path("severity").asText("none");
            String summary = alert.path("annotations").path("summary").asText("");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("receivedAt", Instant.now().toString());
            entry.put("status", status);
            entry.put("alertname", name);
            entry.put("severity", severity);
            entry.put("labels", alert.path("labels"));
            entry.put("summary", summary);
            entry.put("startsAt", alert.path("startsAt").asText());
            synchronized (received) {
                received.addLast(entry);
                while (received.size() > KEEP) {
                    received.removeFirst();
                }
            }
            meters.counter("quickdrop.alerts.received", "alertname", name, "status", status, "severity", severity).increment();
            if (!name.equals("Watchdog")) {
                log.warn("ALERT {} [{}] {}: {}", status.toUpperCase(), severity, name, summary);
            }
            count++;
        }
        return Map.of("received", count);
    }

    @GetMapping("/api/alerts")
    public List<Map<String, Object>> list() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    @DeleteMapping("/api/alerts")
    public Map<String, Object> clear() {
        synchronized (received) {
            int n = received.size();
            received.clear();
            return Map.of("cleared", n);
        }
    }
}
