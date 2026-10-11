package dev.quickdrop.notification;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Consumer lag the way Kafka defines it: for every partition, the newest offset minus the group's
 * <em>committed</em> offset. Exported as {@code notify_consumer_lag{group, topic}}.
 *
 * <p>Why compute it ourselves? The consumers' own metric ({@code records_lag}) counts what they haven't
 * <em>fetched</em> yet — but they prefetch thousands of messages into a local buffer, so it understates the
 * real backlog several times over. kafka-exporter's number is right but its series drop out whenever a group
 * rebalances. Committed offsets are the truth: they say what has actually been processed.
 */
@Component
public class LagReporter {

    private static final List<String> GROUPS = List.of("notification-service", "notification-service-priority");

    private final KafkaAdmin kafkaAdmin;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final Map<String, AtomicLong> lags = new ConcurrentHashMap<>();
    private AdminClient admin;

    public LagReporter(KafkaAdmin kafkaAdmin, MeterRegistry meters, @Value("${spring.kafka.listener.auto-startup:false}") boolean enabled) {
        this.kafkaAdmin = kafkaAdmin;
        this.meters = meters;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void report() {
        if (!enabled) {
            return;
        }
        try {
            if (admin == null) {
                admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
            }
            for (String group : GROUPS) {
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                if (committed.isEmpty()) {
                    continue;
                }
                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest = admin.listOffsets(
                        committed.keySet().stream().collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest())))
                        .all().get(5, TimeUnit.SECONDS);
                Map<String, Long> perTopic = new ConcurrentHashMap<>();
                committed.forEach((tp, offset) -> perTopic.merge(tp.topic(),
                        Math.max(0, latest.get(tp).offset() - (offset == null ? 0 : offset.offset())), Long::sum));
                perTopic.forEach((topic, lag) -> gauge(group, topic).set(lag));
            }
        } catch (Exception e) {
            // Kafka unreachable: keep the last values rather than pretending the backlog is zero.
        }
    }

    private AtomicLong gauge(String group, String topic) {
        return lags.computeIfAbsent(group + "|" + topic, key -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder("notify.consumer.lag", value, AtomicLong::get)
                    .description("Messages not yet processed (latest offset minus committed offset)")
                    .tag("group", group).tag("topic", topic).register(meters);
            return value;
        });
    }
}
