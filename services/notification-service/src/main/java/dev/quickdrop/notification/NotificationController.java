package dev.quickdrop.notification;

import java.util.Map;
import java.util.UUID;

import dev.quickdrop.common.toggles.ToggleRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * POST /notifications            {"messageId", "type", "to", "body", "createdAt"}   (core-service calls this in http mode)
 * POST /admin/blast?count=100000 a marketing blast created inside this service (scenario 6.3)
 * </pre>
 */
@RestController
public class NotificationController {

    private final Delivery delivery;
    private final MemoryQueue memory;
    private final DbQueue db;
    private final ToggleRegistry toggles;

    public NotificationController(Delivery delivery, MemoryQueue memory, DbQueue db, ToggleRegistry toggles) {
        this.delivery = delivery;
        this.memory = memory;
        this.db = db;
        this.toggles = toggles;
    }

    @PostMapping("/notifications")
    public ResponseEntity<Map<String, String>> submit(@RequestBody Notification n) {
        switch (toggles.get(NotificationToggles.DELIVERY_MODE)) {
            case "async-memory" -> memory.submit(n);
            case "db-queue" -> db.submit(n);
            default -> {
                // sync: the caller waits for the vendor (scenario 6.1)
                Delivery.Result result = delivery.send(n, "sync");
                return ResponseEntity.status(result == Delivery.Result.SENT ? HttpStatus.OK : HttpStatus.BAD_GATEWAY)
                        .body(Map.of("status", result.name()));
            }
        }
        return ResponseEntity.accepted().body(Map.of("status", "QUEUED"));
    }

    @PostMapping("/admin/blast")
    public Map<String, Object> blast(@RequestParam(defaultValue = "100000") int count) {
        String mode = toggles.get(NotificationToggles.DELIVERY_MODE);
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            Notification n = new Notification(UUID.randomUUID().toString(), "PROMO", "+9190" + (10000000 + i % 90000000),
                    "Flash sale! Everything at 50% off for the next hour. Order now on QuickDrop.", now, null);
            if (mode.equals("db-queue")) {
                db.submit(n);
            } else {
                memory.submit(n);
            }
        }
        return Map.of("queued", count, "mode", mode.equals("db-queue") ? "db-queue" : "async-memory");
    }
}
