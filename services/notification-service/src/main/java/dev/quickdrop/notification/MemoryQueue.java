package dev.quickdrop.notification;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.quickdrop.common.toggles.ToggleChangedEvent;
import dev.quickdrop.common.toggles.ToggleRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * delivery-mode=async-memory — scenario 6.3. Answer 202 at once, send later from a thread pool whose queue
 * lives in this JVM's heap: unbounded, and gone on restart.
 */
@Component
public class MemoryQueue {

    private final ThreadPoolExecutor pool;
    private final Delivery delivery;

    public MemoryQueue(Delivery delivery, ToggleRegistry toggles, MeterRegistry meters) {
        this.delivery = delivery;
        int workers = (int) toggles.getLong(NotificationToggles.WORKERS);
        this.pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                Thread.ofPlatform().name("memory-sender-", 0).factory());
        Gauge.builder("notify.memory.queue", pool, p -> p.getQueue().size())
                .description("Notifications waiting in the in-memory queue").register(meters);
    }

    public void submit(Notification n) {
        pool.execute(() -> delivery.send(n, "memory"));
    }

    @EventListener
    public void onToggle(ToggleChangedEvent event) {
        if (event.toggle().equals("workers")) {
            int n = Integer.parseInt(event.newValue());
            if (n > pool.getMaximumPoolSize()) {
                pool.setMaximumPoolSize(n);
                pool.setCorePoolSize(n);
            } else {
                pool.setCorePoolSize(n);
                pool.setMaximumPoolSize(n);
            }
        }
    }
}
