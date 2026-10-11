package dev.quickdrop.notification;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import dev.quickdrop.common.toggles.ToggleRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * delivery-mode=db-queue — scenario 6.4. Notifications are rows in notify.notifications; worker threads
 * claim batches with FOR UPDATE SKIP LOCKED (from 3.6), send them, and mark them SENT. Durable and
 * restart-proof — and every insert, poll and status update lands on the Postgres that core also uses.
 */
@Component
@EnableScheduling
public class DbQueue {

    private static final Logger log = LoggerFactory.getLogger(DbQueue.class);
    private static final int MAX_WORKERS = 32;

    record Claimed(long id, Notification notification) {
    }

    private final JdbcClient jdbc;
    private final Delivery delivery;
    private final ToggleRegistry toggles;
    private final AtomicLong pending = new AtomicLong();

    public DbQueue(JdbcClient jdbc, Delivery delivery, ToggleRegistry toggles, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.delivery = delivery;
        this.toggles = toggles;
        Gauge.builder("notify.db.pending", pending, AtomicLong::get).description("PENDING rows in notify.notifications").register(meters);
    }

    /** ON CONFLICT DO NOTHING: a retried request with the same messageId is stored once. */
    public void submit(Notification n) {
        jdbc.sql("""
                        INSERT INTO notifications (message_id, type, recipient, body, created_at)
                        VALUES (?, ?, ?, ?, to_timestamp(? / 1000.0)) ON CONFLICT (message_id) DO NOTHING
                        """)
                .params(n.messageId(), n.type(), n.to(), n.body(), n.createdAt())
                .update();
    }

    /** Start the worker threads; each one only works while its number is below the "workers" toggle. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        for (int i = 0; i < MAX_WORKERS; i++) {
            int index = i;
            Thread.ofVirtual().name("db-sender-" + i).start(() -> work(index));
        }
    }

    private void work(int index) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean active = toggles.is(NotificationToggles.DELIVERY_MODE, "db-queue")
                        && index < toggles.getLong(NotificationToggles.WORKERS);
                List<Claimed> batch = active ? claim(10) : List.of();
                if (batch.isEmpty()) {
                    Thread.sleep(active ? toggles.getLong(NotificationToggles.DB_POLL_MS) : 1000);
                    continue;
                }
                for (Claimed c : batch) {
                    Delivery.Result result = delivery.send(c.notification(), "db");
                    jdbc.sql("UPDATE notifications SET status = ?, sent_at = now() WHERE id = ?")
                            .params(result == Delivery.Result.SENT ? "SENT" : "FAILED", c.id())
                            .update();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.debug("db-queue worker {}: {}", index, e.getMessage());
                sleepQuietly(500);
            }
        }
    }

    /**
     * Claims up to {@code n} rows in ONE statement: the inner SELECT locks rows nobody else holds (SKIP
     * LOCKED), the UPDATE marks them SENDING before the lock is released. A plain SELECT ... FOR UPDATE
     * outside a transaction would release its locks immediately and two workers could send the same SMS.
     * Rows SENDING for more than 30 s belonged to a worker that crashed (a lease) and are claimed again.
     */
    private List<Claimed> claim(int n) {
        return jdbc.sql("""
                        UPDATE notifications SET status = 'SENDING', claimed_at = now(), attempts = attempts + 1
                        WHERE id IN (SELECT id FROM notifications
                                     WHERE status = 'PENDING' OR (status = 'SENDING' AND claimed_at < now() - interval '30 seconds')
                                     ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
                        RETURNING id, message_id, type, recipient, body, (extract(epoch FROM created_at) * 1000)::bigint
                        """)
                .param(n)
                .query((rs, i) -> new Claimed(rs.getLong(1), new Notification(rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getLong(6), null)))
                .list();
    }

    @Scheduled(fixedDelay = 5000)
    public void countPending() {
        try {
            pending.set(jdbc.sql("SELECT count(*) FROM notifications WHERE status IN ('PENDING', 'SENDING')").query(Long.class).single());
        } catch (RuntimeException ignored) {
            // the database is busy or down: keep the last value
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
