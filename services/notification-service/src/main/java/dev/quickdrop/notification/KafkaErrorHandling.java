package dev.quickdrop.notification;

import dev.quickdrop.common.toggles.ToggleRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * What happens when a listener throws — scenario K4 (poison pill). Kafka has no "skip this one": a consumer
 * commits offsets in order, so a message it can never process blocks everything behind it in its partition.
 * {@code poison-handling} decides:
 * <ul>
 *   <li>{@code retry-forever}: keep retrying every second. Nothing behind it in that partition is ever processed.</li>
 *   <li>{@code skip}: 3 tries, then log and move on. The partition flows again; the message is gone.</li>
 *   <li>{@code dlq}: 3 tries, then publish it to {@code notifications.DLT} (a dead-letter topic) and move on.
 *       Nothing is blocked and nothing is lost: someone can inspect, fix and replay it later.</li>
 * </ul>
 */
@Configuration
public class KafkaErrorHandling {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template, ToggleRegistry toggles, MeterRegistry meters) {
        // Name the DLT explicitly. The default name differs between Spring Kafka versions (".DLT" vs "-dlt"); ours
        // resolved to "notifications-dlt", which didn't exist (auto-create is off), so every dead-lettering blocked for
        // max.block.ms, failed, and was retried: the "fix" kept the partition stuck. Partition -1: let the key decide.
        DeadLetterPublishingRecoverer toDlt = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition("notifications.DLT", -1));
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            if (toggles.is(NotificationToggles.POISON_HANDLING, "dlq")) {
                toDlt.accept(record, exception);
                meters.counter("notify.poison", "outcome", "dead_lettered").increment();
            } else {
                meters.counter("notify.poison", "outcome", "skipped").increment();   // gone; a log line is all that's left
            }
        };
        BackOff backOff = () -> new BackOffExecution() {
            private int attempts;

            @Override
            public long nextBackOff() {
                meters.counter("notify.poison.retries").increment();
                if (toggles.is(NotificationToggles.POISON_HANDLING, "retry-forever")) {
                    return 1000;
                }
                return ++attempts <= 3 ? 200 : STOP;
            }
        };
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
