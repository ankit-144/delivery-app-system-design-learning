package dev.quickdrop.notification;

/**
 * One SMS to send. The same JSON shape arrives over HTTP and over Kafka.
 *
 * @param messageId unique per logical message: the vendor uses it to spot duplicates
 * @param type      ORDER_PLACED, OTP or PROMO
 * @param createdAt when the producer created it (epoch ms): delivery delay = sent time - createdAt
 */
public record Notification(String messageId, String type, String to, String body, long createdAt, Long orderId) {
}
