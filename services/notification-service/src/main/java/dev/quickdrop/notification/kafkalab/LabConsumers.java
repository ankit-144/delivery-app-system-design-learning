package dev.quickdrop.notification.kafkalab;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Consumer groups with every knob exposed (Phase 7). Each group runs N consumer threads in this JVM and
 * counts what happened: processed, duplicates (a messageId seen before), out-of-order events per order,
 * rebalances, and which partitions each consumer owned.
 *
 * <p>commit modes (K3):
 * <ul>
 *   <li>{@code after}: process, then commit (at-least-once). A crash between the two re-delivers the batch.</li>
 *   <li>{@code before}: commit the polled batch, then process it (at-most-once). A crash mid-batch loses the rest of it.</li>
 *   <li>{@code after-dedupe}: at-least-once, plus skip messageIds already processed (an idempotent consumer).</li>
 * </ul>
 * {@code crashEvery=N}: every N records the consumer "crashes" at the worst moment for its commit mode,
 * then rejoins the group as a new member.
 */
@Component
public class LabConsumers {

    public record Params(String topic, String group, int count, int processingMs, int maxPollRecords, int maxPollIntervalMs,
                         String commit, String assignor, int crashEvery, boolean sendSms, String isolation, boolean staticMembership) {
    }

    static final class GroupStats {
        final Params params;
        final AtomicBoolean running = new AtomicBoolean(true);
        volatile boolean crashStop;   // stop like kill -9: don't commit what was processed
        final AtomicLong processed = new AtomicLong();
        final AtomicLong handled = new AtomicLong();   // records looked at, processed or not: drives crashEvery
        final AtomicLong duplicates = new AtomicLong();
        final AtomicLong skippedDuplicates = new AtomicLong();
        final AtomicLong outOfOrder = new AtomicLong();
        final AtomicLong rebalances = new AtomicLong();
        final AtomicLong crashes = new AtomicLong();
        final AtomicLong commitFailures = new AtomicLong();
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        final Map<Long, Integer> lastSeq = new ConcurrentHashMap<>();
        final Map<String, String> assignments = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> perConsumer = new ConcurrentHashMap<>();
        final List<Thread> threads = new ArrayList<>();

        GroupStats(Params params) {
            this.params = params;
        }
    }

    private final String bootstrap;
    private final ObjectMapper json;
    private final RestClient vendor;
    private final MeterRegistry meters;
    private final Map<String, GroupStats> groups = new ConcurrentHashMap<>();

    public LabConsumers(@Value("${spring.kafka.bootstrap-servers}") String bootstrap, ObjectMapper json, RestClient.Builder builder,
                        @Value("${quickdrop.vendor.base-url}") String vendorUrl, MeterRegistry meters) {
        this.bootstrap = bootstrap;
        this.json = json;
        this.vendor = builder.clone().baseUrl(vendorUrl).build();
        this.meters = meters;
    }

    public Map<String, Object> start(Params p) {
        stop(p.group(), false);
        GroupStats stats = new GroupStats(p);
        groups.put(p.group(), stats);
        for (int i = 0; i < p.count(); i++) {
            String name = p.group() + "-c" + i;
            Thread t = Thread.ofPlatform().name(name).start(() -> runConsumer(stats, name));
            stats.threads.add(t);
        }
        return Map.of("group", p.group(), "consumers", p.count(), "topic", p.topic());
    }

    /** crash=true: stop without committing what was processed (like kill -9). */
    public Map<String, Object> stop(String group, boolean crash) {
        GroupStats stats = groups.get(group);
        if (stats == null) {
            return Map.of("group", group, "stopped", false);
        }
        stats.crashStop = crash;
        stats.running.set(false);
        if (crash) {
            stats.crashes.incrementAndGet();
        }
        for (Thread t : stats.threads) {
            try {
                t.join(15000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return stats(group);
    }

    private void runConsumer(GroupStats stats, String name) {
        Params p = stats.params;
        int restarts = 0;
        while (stats.running.get()) {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, p.group());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, p.maxPollRecords());
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, p.maxPollIntervalMs());
            props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);
            props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1000);
            props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, p.isolation());
            props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, p.assignor().equals("cooperative-sticky")
                    ? "org.apache.kafka.clients.consumer.CooperativeStickyAssignor"
                    : "org.apache.kafka.clients.consumer.RangeAssignor");
            if (p.staticMembership()) {
                props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, name);   // K5: a restart within session.timeout causes no rebalance
            }
            boolean crashed = false;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.subscribe(List.of(p.topic()), new ConsumerRebalanceListener() {
                    @Override
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                        if (!partitions.isEmpty()) {
                            stats.rebalances.incrementAndGet();
                        }
                    }

                    @Override
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        stats.assignments.put(name, consumer.assignment().stream().map(tp -> Integer.toString(tp.partition())).sorted().toList().toString());
                    }
                });
                while (stats.running.get() && !crashed) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (p.commit().equals("before") && !records.isEmpty()) {
                        Map<TopicPartition, OffsetAndMetadata> polled = new LinkedHashMap<>();
                        for (TopicPartition tp : records.partitions()) {
                            List<ConsumerRecord<String, String>> part = records.records(tp);
                            polled.put(tp, new OffsetAndMetadata(part.get(part.size() - 1).offset() + 1));
                        }
                        consumer.commitSync(polled);   // commit the whole batch first (what auto-commit effectively does)...
                    }
                    Map<TopicPartition, OffsetAndMetadata> done = new LinkedHashMap<>();
                    for (ConsumerRecord<String, String> record : records) {
                        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                        OffsetAndMetadata next = new OffsetAndMetadata(record.offset() + 1);
                        boolean crashNow = p.crashEvery() > 0 && stats.handled.incrementAndGet() % p.crashEvery() == 0;
                        if (crashNow && p.commit().equals("before")) {
                            crashed = true;                             // ...then die mid-batch: the rest of it is never processed
                            break;
                        }
                        process(stats, name, record);
                        done.put(tp, next);
                        if (crashNow && !p.commit().equals("before")) {
                            crashed = true;                             // processed but never committed: redelivered
                            break;
                        }
                    }
                    if (!crashed && !stats.crashStop && !p.commit().equals("before") && !done.isEmpty()) {
                        try {
                            consumer.commitSync(done);
                        } catch (RuntimeException e) {
                            stats.commitFailures.incrementAndGet();     // K5: we were kicked out of the group mid-batch
                        }
                    }
                }
                if (crashed) {
                    stats.crashes.incrementAndGet();
                }
            } catch (RuntimeException e) {
                stats.commitFailures.incrementAndGet();
            }
            stats.assignments.remove(name);
            if (crashed && stats.running.get()) {
                restarts++;
                sleep(500);   // the "restarted" consumer rejoins as a new member
            } else {
                return;
            }
        }
    }

    private void process(GroupStats stats, String consumer, ConsumerRecord<String, String> record) {
        JsonNode node;
        try {
            node = json.readTree(record.value());
        } catch (Exception e) {
            return;
        }
        String messageId = node.path("messageId").asText();
        boolean firstTime = stats.seen.add(messageId);
        if (!firstTime) {
            stats.duplicates.incrementAndGet();
            if (stats.params.commit().equals("after-dedupe")) {
                stats.skippedDuplicates.incrementAndGet();   // idempotent consumer: already handled, do nothing
                return;
            }
        }
        if (stats.params.processingMs() > 0) {
            sleep(stats.params.processingMs());
        }
        if (stats.params.sendSms()) {
            try {
                vendor.post().uri("/v1/sms").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("messageId", messageId, "type", "PROMO", "to", "+919999999999", "body", "lab"))
                        .retrieve().toBodilessEntity();
            } catch (RuntimeException ignored) {
                // vendor errors are not what this lab measures
            }
        }
        long orderId = node.path("orderId").asLong();
        int seq = node.path("seq").asInt();
        Integer previous = stats.lastSeq.merge(orderId, seq, Math::max);
        if (previous != null && previous > seq) {
            stats.outOfOrder.incrementAndGet();   // e.g. DELIVERED was processed before RIDER_ASSIGNED
        }
        stats.processed.incrementAndGet();
        stats.perConsumer.computeIfAbsent(consumer, k -> new AtomicLong()).incrementAndGet();
        meters.counter("lab.processed", "group", stats.params.group()).increment();
    }

    public Map<String, Object> stats(String group) {
        GroupStats s = groups.get(group);
        if (s == null) {
            return Map.of("group", group, "exists", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", group);
        out.put("running", s.running.get());
        out.put("processed", s.processed.get());
        out.put("uniqueMessages", s.seen.size());
        out.put("duplicates", s.duplicates.get());
        out.put("duplicatesSkipped", s.skippedDuplicates.get());
        out.put("outOfOrder", s.outOfOrder.get());
        out.put("rebalances", s.rebalances.get());
        out.put("crashes", s.crashes.get());
        out.put("commitFailures", s.commitFailures.get());
        out.put("partitionsPerConsumer", new LinkedHashMap<>(s.assignments));
        Map<String, Long> per = new LinkedHashMap<>();
        s.perConsumer.forEach((k, v) -> per.put(k, v.get()));
        out.put("processedPerConsumer", per);
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
