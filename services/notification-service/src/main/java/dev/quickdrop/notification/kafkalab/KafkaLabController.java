package dev.quickdrop.notification.kafkalab;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Kafka deep-dive lab (Phase 7). Every scenario K1-K11 drives these endpoints.
 * <pre>
 * POST /admin/kafka-lab/produce?topic=lab-6p&count=50000&keys=none|order|random|rider&acks=all&lingerMs=5&compression=none
 * POST /admin/kafka-lab/consumers/start?topic=lab-6p&group=g1&count=6&processingMs=5&commit=after&crashEvery=0
 * POST /admin/kafka-lab/consumers/stop?group=g1&crash=false
 * GET  /admin/kafka-lab/consumers?group=g1                 stats
 * POST /admin/kafka-lab/reset-offsets?group=g1&to=earliest  replay (group must be stopped)
 * GET  /admin/kafka-lab/count?topic=lab-out&isolation=read_committed   records and unique messageIds from the start
 * POST /admin/kafka-lab/copier/start?transactional=true&crashEvery=500   /copier/stop
 * GET  /admin/kafka-lab/lag?group=g1&topic=lab-6p           lag from committed offsets (topic: count uncommitted partitions too)
 * POST /admin/kafka-lab/acks/produce?acks=1&rate=20000&seconds=20   GET /acks/verify   (K6, 3-broker cluster)
 * </pre>
 */
@RestController
public class KafkaLabController {

    private final LabProducer producer;
    private final LabConsumers consumers;
    private final LabCopier copier;
    private final AcksLab acksLab;
    private final KafkaAdmin kafkaAdmin;
    private final ObjectMapper json;
    private final String bootstrap;

    public KafkaLabController(LabProducer producer, LabConsumers consumers, LabCopier copier, AcksLab acksLab, KafkaAdmin kafkaAdmin,
                              ObjectMapper json, @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.producer = producer;
        this.consumers = consumers;
        this.copier = copier;
        this.acksLab = acksLab;
        this.kafkaAdmin = kafkaAdmin;
        this.json = json;
        this.bootstrap = bootstrap;
    }

    @PostMapping("/admin/kafka-lab/produce")
    public Map<String, Object> produce(@RequestParam(defaultValue = "lab-6p") String topic, @RequestParam(defaultValue = "10000") int count,
                                       @RequestParam(defaultValue = "none") String keys, @RequestParam(defaultValue = "3") int eventsPerKey,
                                       @RequestParam(defaultValue = "all") String acks, @RequestParam(defaultValue = "5") int lingerMs,
                                       @RequestParam(defaultValue = "16") int batchKb, @RequestParam(defaultValue = "none") String compression,
                                       @RequestParam(defaultValue = "200") int payloadBytes, @RequestParam(defaultValue = "true") boolean idempotence,
                                       @RequestParam(defaultValue = "1000") int keySpace) {
        return producer.produce(new LabProducer.Params(topic, count, keys, eventsPerKey, acks, lingerMs, batchKb, compression,
                payloadBytes, idempotence, keySpace));
    }

    @PostMapping("/admin/kafka-lab/consumers/start")
    public Map<String, Object> start(@RequestParam(defaultValue = "lab-6p") String topic, @RequestParam String group,
                                     @RequestParam(defaultValue = "3") int count, @RequestParam(defaultValue = "0") int processingMs,
                                     @RequestParam(defaultValue = "500") int maxPollRecords, @RequestParam(defaultValue = "300000") int maxPollIntervalMs,
                                     @RequestParam(defaultValue = "after") String commit, @RequestParam(defaultValue = "range") String assignor,
                                     @RequestParam(defaultValue = "0") int crashEvery, @RequestParam(defaultValue = "false") boolean sendSms,
                                     @RequestParam(defaultValue = "read_uncommitted") String isolation,
                                     @RequestParam(defaultValue = "false") boolean staticMembership) {
        return consumers.start(new LabConsumers.Params(topic, group, count, processingMs, maxPollRecords, maxPollIntervalMs, commit,
                assignor, crashEvery, sendSms, isolation, staticMembership));
    }

    @PostMapping("/admin/kafka-lab/consumers/stop")
    public Map<String, Object> stop(@RequestParam String group, @RequestParam(defaultValue = "false") boolean crash) {
        return consumers.stop(group, crash);
    }

    @GetMapping("/admin/kafka-lab/consumers")
    public Map<String, Object> stats(@RequestParam String group) {
        return consumers.stats(group);
    }

    /** Replay (K7): move a stopped group's committed offsets to the start (or end) of the topic. */
    @PostMapping("/admin/kafka-lab/reset-offsets")
    public Map<String, Object> resetOffsets(@RequestParam String group, @RequestParam(defaultValue = "lab-6p") String topic,
                                            @RequestParam(defaultValue = "earliest") String to) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            List<TopicPartition> partitions = admin.describeTopics(List.of(topic)).allTopicNames().get(5, TimeUnit.SECONDS)
                    .get(topic).partitions().stream().map(pi -> new TopicPartition(topic, pi.partition())).toList();
            OffsetSpec spec = to.equals("latest") ? OffsetSpec.latest() : OffsetSpec.earliest();
            var target = admin.listOffsets(partitions.stream().collect(Collectors.toMap(tp -> tp, tp -> spec))).all().get(5, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> offsets = target.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue().offset())));
            admin.alterConsumerGroupOffsets(group, offsets).all().get(10, TimeUnit.SECONDS);
            return Map.of("group", group, "topic", topic, "resetTo", to, "partitions", partitions.size());
        }
    }

    /**
     * Lag from committed offsets. With {@code topic}, partitions the group hasn't committed yet count in full
     * (from the log start). Without it, a group that has committed on 4 of 6 partitions would report "lag 0".
     */
    @GetMapping("/admin/kafka-lab/lag")
    public Map<String, Object> lag(@RequestParam String group, @RequestParam(required = false) String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS));
            committed.values().removeIf(java.util.Objects::isNull);
            Set<TopicPartition> partitions = new HashSet<>(committed.keySet());
            if (topic != null) {
                admin.describeTopics(List.of(topic)).allTopicNames().get(5, TimeUnit.SECONDS).get(topic).partitions()
                        .forEach(pi -> partitions.add(new TopicPartition(topic, pi.partition())));
            }
            if (partitions.isEmpty()) {
                return Map.of("group", group, "lag", -1);
            }
            var latest = admin.listOffsets(partitions.stream().collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()))).all().get(5, TimeUnit.SECONDS);
            var earliest = admin.listOffsets(partitions.stream().collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.earliest()))).all().get(5, TimeUnit.SECONDS);
            Map<String, Long> perPartition = new TreeMap<>();
            long total = 0;
            for (TopicPartition tp : partitions) {
                OffsetAndMetadata c = committed.get(tp);
                long from = Math.max(c == null ? 0 : c.offset(), earliest.get(tp).offset());
                long lag = Math.max(0, latest.get(tp).offset() - from);
                perPartition.put(tp.toString(), lag);
                total += lag;
            }
            return Map.of("group", group, "lag", total, "perPartition", perPartition);
        }
    }

    /** Reads a topic from the beginning with a throwaway consumer: total records and distinct messageIds (K10, K11). */
    @GetMapping("/admin/kafka-lab/count")
    public Map<String, Object> count(@RequestParam String topic, @RequestParam(defaultValue = "read_uncommitted") String isolation) {
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "lab-count-" + UUID.randomUUID());
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        c.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);
        c.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000);
        long records = 0;
        Set<String> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(c)) {
            consumer.subscribe(List.of(topic));
            int emptyPolls = 0;
            while (emptyPolls < 4) {
                var batch = consumer.poll(Duration.ofMillis(700));
                if (batch.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> r : batch) {
                    records++;
                    if (r.key() != null) {
                        keys.add(r.key());
                    }
                    try {
                        ids.add(json.readTree(r.value()).path("messageId").asText());
                    } catch (Exception ignored) {
                        // not one of ours
                    }
                }
            }
        }
        return Map.of("topic", topic, "isolation", isolation, "records", records, "uniqueMessageIds", ids.size(), "distinctKeys", keys.size());
    }

    @PostMapping("/admin/kafka-lab/copier/start")
    public Map<String, Object> copierStart(@RequestParam(defaultValue = "false") boolean transactional,
                                           @RequestParam(defaultValue = "500") int crashEvery) {
        String group = "lab-copier-" + (transactional ? "tx" : "plain") + "-" + UUID.randomUUID();
        Map<String, Object> out = new LinkedHashMap<>(copier.start(transactional, crashEvery, group));
        out.put("group", group);
        return out;
    }

    @PostMapping("/admin/kafka-lab/copier/stop")
    public Map<String, Object> copierStop() {
        return copier.stop();
    }

    /** K6 (3-broker cluster, make kafka-acks): produce numbered messages at a steady rate, remember which were acknowledged. */
    @PostMapping("/admin/kafka-lab/acks/produce")
    public Map<String, Object> acksProduce(@RequestParam(defaultValue = "acks-lab") String topic, @RequestParam(defaultValue = "all") String acks,
                                           @RequestParam(defaultValue = "20000") int rate, @RequestParam(defaultValue = "20") int seconds,
                                           @RequestParam(defaultValue = "30000") int deliveryTimeoutMs) {
        return acksLab.produce(topic, acks, rate, seconds, deliveryTimeoutMs);
    }

    /** K6: read the topic back. "ackedButLost" = acknowledged writes that are gone. */
    @GetMapping("/admin/kafka-lab/acks/verify")
    public Map<String, Object> acksVerify(@RequestParam(defaultValue = "acks-lab") String topic) {
        return acksLab.verify(topic);
    }

    /** K4: a message nobody can parse, on the real notifications topic. */
    @PostMapping("/admin/kafka-lab/poison")
    public Map<String, Object> poison(@RequestParam(defaultValue = "notifications") String topic, @RequestParam(defaultValue = "poison") String key) {
        Properties p = new Properties();
        p.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class);
        p.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class);
        try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<String, String>(p)) {
            var meta = producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, key,
                    "{\"messageId\": \"broken-" + UUID.randomUUID() + "\", \"type\": \"ORDER_PLACED\", \"to\": ")).get();
            return Map.of("topic", topic, "partition", meta.partition(), "offset", meta.offset(), "value", "truncated JSON");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Empties lab topics by deleting their records (truncation to the latest offset). */
    @PostMapping("/admin/kafka-lab/truncate")
    public Map<String, Object> truncate(@RequestParam String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            List<TopicPartition> partitions = admin.describeTopics(List.of(topic)).allTopicNames().get(5, TimeUnit.SECONDS)
                    .get(topic).partitions().stream().map(pi -> new TopicPartition(topic, pi.partition())).toList();
            var latest = admin.listOffsets(partitions.stream().collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()))).all().get(5, TimeUnit.SECONDS);
            admin.deleteRecords(latest.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                    e -> org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(e.getValue().offset())))).all().get(10, TimeUnit.SECONDS);
            return Map.of("topic", topic, "truncated", true);
        }
    }
}
