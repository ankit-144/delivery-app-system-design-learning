package dev.quickdrop.core.notification;

import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes SMS requests to Kafka (Phase 6). Key = recipient, so one person's messages stay in order. */
@Component
public class KafkaPublisher {

    public static final String TOPIC = "notifications";
    public static final String PRIORITY_TOPIC = "notifications-priority";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final ToggleRegistry toggles;

    public KafkaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json, ToggleRegistry toggles) {
        this.kafka = kafka;
        this.json = json;
        this.toggles = toggles;
    }

    /** OTPs go to their own topic when otp-topic=priority (scenario 6.8). */
    public String topicFor(SmsMessage message) {
        return message.type().equals("OTP") && toggles.is(CoreToggles.OTP_TOPIC, "priority") ? PRIORITY_TOPIC : TOPIC;
    }

    /** Waits for the broker's acknowledgement (acks=all), so a failure is an exception here, not a silent loss. */
    public void publish(SmsMessage message) {
        try {
            kafka.send(topicFor(message), message.to(), toJson(message)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka publish failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fire-and-forget, for bulk promos: the producer batches messages (linger.ms, batch.size) instead of
     * waiting for each acknowledgement. Much faster; a failure only shows up in the callback.
     */
    public void publishAsync(SmsMessage message, Runnable onFailure) {
        kafka.send(topicFor(message), message.to(), toJson(message)).whenComplete((result, error) -> {
            if (error != null) {
                onFailure.run();
            }
        });
    }

    public String toJson(SmsMessage message) {
        try {
            return json.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
