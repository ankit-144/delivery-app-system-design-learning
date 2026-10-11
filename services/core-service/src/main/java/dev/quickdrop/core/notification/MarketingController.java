package dev.quickdrop.core.notification;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "The flash sale has started!" to every customer — the notification spike of scenarios 6.4, 6.5 and 6.8.
 * Runs in the background at {@code rate} messages per second through the current notification-mode.
 * <pre>
 * curl -X POST 'localhost:8081/api/marketing/blast?count=30000&rate=1500'
 * </pre>
 */
@RestController
public class MarketingController {

    private final NotificationService notifications;
    private final MeterRegistry meters;
    private final AtomicInteger running = new AtomicInteger();

    public MarketingController(NotificationService notifications, MeterRegistry meters) {
        this.notifications = notifications;
        this.meters = meters;
    }

    @PostMapping("/api/marketing/blast")
    public Map<String, Object> blast(@RequestParam(defaultValue = "30000") int count, @RequestParam(defaultValue = "1000") int rate) {
        running.incrementAndGet();
        Thread.ofVirtual().name("marketing-blast").start(() -> {
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                long due = start + (long) (i * 1_000_000_000.0 / rate);
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                notifications.sendPromo("+9190" + (10_000_000 + i % 89_999_999), "Flash sale! 50% off everything for the next hour.");
                meters.counter("quickdrop.marketing.sent").increment();
            }
            running.decrementAndGet();
        });
        return Map.of("blast", count, "ratePerSecond", rate);
    }
}
