package dev.quickdrop.core.notification;

import java.util.UUID;

/**
 * One SMS. The same JSON goes to the vendor, to notification-service over HTTP, and onto Kafka.
 *
 * @param messageId unique per logical message. Order confirmations use "order-{id}", so a duplicate send of
 *                  the same confirmation is recognisable (the vendor counts duplicates).
 * @param createdAt epoch ms when it was created: notification-service measures delivery delay from here
 */
public record SmsMessage(String messageId, String type, String to, String body, long createdAt, Long orderId) {

    public static SmsMessage of(String type, String to, String body) {
        return new SmsMessage(UUID.randomUUID().toString(), type, to, body, System.currentTimeMillis(), null);
    }

    public static SmsMessage forOrder(long orderId, String to, String body) {
        return new SmsMessage("order-" + orderId, "ORDER_PLACED", to, body, System.currentTimeMillis(), orderId);
    }
}
