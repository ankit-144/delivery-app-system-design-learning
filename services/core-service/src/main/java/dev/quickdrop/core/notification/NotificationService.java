package dev.quickdrop.core.notification;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.order.OrderView;
import dev.quickdrop.core.order.PlacedOrder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends SMS (order confirmations, OTPs, promos). How depends on {@code notification-mode}:
 *
 * <pre>
 *  Phase 2 — core talks to the vendor itself
 *    none          don't send
 *    inline        call the vendor on the request thread, after commit           (2.1)
 *    inline-in-tx  call the vendor before commit, holding a connection and locks (2.2)
 *    async-memory  hand it to an in-memory thread pool                           (2.1, 6.3)
 *
 *  Phase 6 — notification-service does the sending
 *    http          call notification-service synchronously                       (6.1, 6.2)
 *    kafka-direct  publish to Kafka after commit — a dual write                  (6.5, 6.6)
 *    kafka-in-tx   publish to Kafka before commit — the other dual write         (6.6)
 *    outbox        write the event in the order's transaction; a relay publishes (6.6)
 * </pre>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** kafka-in-tx chaos: the order rolls back after its event was already published. */
    public static class RolledBackAfterPublish extends RuntimeException {
        public RolledBackAfterPublish(long orderId) {
            super("Order " + orderId + " rolled back after its event was published");
        }
    }

    private final VendorClient vendor;
    private final NotificationClient notificationService;
    private final KafkaPublisher kafka;
    private final Outbox outbox;
    private final ToggleRegistry toggles;
    private final MeterRegistry meters;
    private final ThreadPoolExecutor asyncPool;

    public NotificationService(VendorClient vendor, NotificationClient notificationService, KafkaPublisher kafka, Outbox outbox,
                               ToggleRegistry toggles, MeterRegistry meters) {
        this.vendor = vendor;
        this.notificationService = notificationService;
        this.kafka = kafka;
        this.outbox = outbox;
        this.toggles = toggles;
        this.meters = meters;
        this.asyncPool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                Thread.ofPlatform().name("notify-", 0).factory());
        Gauge.builder("quickdrop.notifications.queue.size", asyncPool, p -> p.getQueue().size())
                .description("Messages waiting in the in-memory notification queue")
                .register(meters);
        for (String mode : List.of("inline", "inline-in-tx", "async-memory", "http", "kafka-direct", "kafka-in-tx", "outbox")) {
            for (String result : List.of("sent", "failed", "queued", "dropped", "lost_crash")) {
                meters.counter("quickdrop.notifications", "mode", mode, "result", result);
            }
        }
    }

    /** Called inside the order transaction. */
    public void insideTransaction(PlacedOrder placed) {
        String mode = mode();
        SmsMessage message = orderConfirmation(placed);
        switch (mode) {
            case "inline-in-tx" -> sendToVendor(message, mode);
            case "outbox" -> {
                outbox.add(message);   // commits (or rolls back) together with the order
                count(mode, "queued");
            }
            case "kafka-in-tx" -> {
                publishToKafka(message, mode);
                if (chance(CoreToggles.ROLLBACK_AFTER_PUBLISH_PERCENT)) {
                    throw new RolledBackAfterPublish(placed.order().id());   // the event is out; the order is not
                }
            }
            default -> {
            }
        }
    }

    /** Called after the order transaction committed. */
    public void afterCommit(PlacedOrder placed) {
        String mode = mode();
        if (mode.equals("kafka-direct") && chance(CoreToggles.CRASH_AFTER_COMMIT_PERCENT)) {
            // The process "dies" between the database commit and the Kafka publish.
            count(mode, "lost_crash");
            return;
        }
        if (!List.of("inline-in-tx", "outbox", "kafka-in-tx").contains(mode)) {
            dispatch(orderConfirmation(placed));
        }
    }

    public void sendOtp(String phone, String code) {
        dispatch(SmsMessage.of("OTP", phone, "Your QuickDrop login code is " + code));
    }

    public void sendPromo(String phone, String text) {
        dispatch(SmsMessage.of("PROMO", phone, text));
    }

    /** Non-transactional sends (after commit, OTPs, promos). */
    private void dispatch(SmsMessage message) {
        String mode = mode();
        switch (mode) {
            case "none" -> {
            }
            case "async-memory" -> enqueue(message);
            case "http" -> {
                try {
                    notificationService.send(message);
                    count(mode, "sent");
                } catch (RuntimeException e) {
                    // Timeout, breaker open or bulkhead full: the SMS is lost, the order goes on (scenario 6.2).
                    count(mode, "dropped");
                    log.debug("notification-service call failed: {}", e.getMessage());
                }
            }
            case "kafka-direct", "kafka-in-tx" -> publishToKafka(message, mode);
            case "outbox" -> {
                outbox.add(message);   // OTPs and promos: no surrounding transaction, but the same relay
                count(mode, "queued");
            }
            default -> sendToVendor(message, mode.equals("inline-in-tx") ? "inline" : mode);
        }
    }

    private void publishToKafka(SmsMessage message, String mode) {
        try {
            if (message.type().equals("PROMO")) {
                kafka.publishAsync(message, () -> count(mode, "failed"));   // bulk: let the producer batch
                count(mode, "queued");
                return;
            }
            kafka.publish(message);
            count(mode, "queued");
        } catch (RuntimeException e) {
            count(mode, "failed");
            log.debug("Kafka publish failed: {}", e.getMessage());
        }
    }

    private void enqueue(SmsMessage message) {
        long capacity = toggles.getLong(CoreToggles.ASYNC_QUEUE_CAPACITY);
        if (capacity > 0 && asyncPool.getQueue().size() >= capacity) {
            count("async-memory", "dropped");
            return;
        }
        asyncPool.execute(() -> sendToVendor(message, "async-memory"));
        count("async-memory", "queued");
    }

    private void sendToVendor(SmsMessage message, String mode) {
        try {
            vendor.sendSms(message);
            count(mode, "sent");
        } catch (RuntimeException e) {
            // Best effort: a failed SMS must not fail the order. (Is that the right call? See scenario 6.2.)
            count(mode, "failed");
            log.debug("SMS {} to {} failed: {}", message.messageId(), message.to(), e.getMessage());
        }
    }

    private SmsMessage orderConfirmation(PlacedOrder placed) {
        OrderView order = placed.order();
        String rider = order.riderId() == null ? "We are finding a rider for you." : "Rider #" + order.riderId() + " is on the way.";
        return SmsMessage.forOrder(order.id(), placed.customerPhone(),
                "QuickDrop order #%d confirmed (₹%.2f). %s".formatted(order.id(), order.totalPaise() / 100.0, rider));
    }

    private boolean chance(dev.quickdrop.common.toggles.ToggleDefinition percent) {
        return ThreadLocalRandom.current().nextInt(100) < toggles.getLong(percent);
    }

    private String mode() {
        return toggles.get(CoreToggles.NOTIFICATION_MODE);
    }

    private void count(String mode, String result) {
        Counter.builder("quickdrop.notifications").tag("mode", mode).tag("result", result).register(meters).increment();
    }
}
