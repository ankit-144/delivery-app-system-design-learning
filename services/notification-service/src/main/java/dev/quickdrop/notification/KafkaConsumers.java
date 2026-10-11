package dev.quickdrop.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka path — scenarios 6.5-6.8. Two consumer groups:
 * <ul>
 *   <li>{@code notification-service} reads topic {@code notifications} (order confirmations, promos, and OTPs
 *       unless they're routed separately).</li>
 *   <li>{@code notification-service-priority} reads {@code notifications-priority} (OTPs when core routes them
 *       there, 6.8): its own lag, its own consumers, never stuck behind a promo blast.</li>
 * </ul>
 * Offsets are committed after each record is processed (ack-mode=record): at-least-once delivery.
 */
@Component
public class KafkaConsumers {

    private final ObjectMapper json;
    private final Delivery delivery;
    private final MeterRegistry meters;

    public KafkaConsumers(ObjectMapper json, Delivery delivery, MeterRegistry meters) {
        this.json = json;
        this.delivery = delivery;
        this.meters = meters;
    }

    @KafkaListener(id = "notifications", topics = "notifications", groupId = "notification-service")
    public void onNotification(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record);
    }

    @KafkaListener(id = "notifications-priority", topics = "notifications-priority", groupId = "notification-service-priority")
    public void onPriority(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record);
    }

    private void handle(ConsumerRecord<String, String> record) throws JsonProcessingException {
        Notification n = json.readValue(record.value(), Notification.class);
        delivery.send(n, "kafka");
        meters.counter("notify.kafka.consumed", "topic", record.topic(), "type", n.type()).increment();
    }
}
