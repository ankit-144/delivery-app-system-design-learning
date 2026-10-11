package dev.quickdrop.notification.kafkalab;

import java.time.Duration;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * K6 — what does "acknowledged" promise? Produces numbered messages at a steady rate to the 3-broker cluster and
 * remembers exactly which numbers the broker acknowledged. While it runs, the lab script kills the partition
 * leader. {@link #verify} then reads the topic back: an acknowledged number that isn't there is a lost write.
 */
@Component
public class AcksLab {

    private final String bootstrap;
    private final BitSet acked = new BitSet();
    private volatile int sent;
    private volatile String lastAcks = "";

    public AcksLab(@Value("${quickdrop.kafka-lab.cluster-bootstrap:kafka-a:9092,kafka-b:9092,kafka-c:9092}") String bootstrap) {
        this.bootstrap = bootstrap;
    }

    public synchronized Map<String, Object> produce(String topic, String acks, int ratePerSecond, int seconds, int deliveryTimeoutMs) {
        synchronized (acked) {
            acked.clear();
        }
        lastAcks = acks;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, acks);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, acks.equals("all"));   // idempotence requires acks=all
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 128L * 1024 * 1024);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.min(5000, deliveryTimeoutMs - 5));
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        AtomicLong ackedCount = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        Map<String, AtomicLong> errors = new ConcurrentHashMap<>();
        Map<Long, long[]> perSecond = new ConcurrentHashMap<>();   // second → [acked, failed]
        int total = ratePerSecond * seconds;
        String padding = "p".repeat(100);
        long start = System.nanoTime();
        int i = 0;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (; i < total; i++) {
                if (i % 500 == 0) {
                    long due = start + (long) (i * 1e9 / ratePerSecond);   // pace: this message is due at this time
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        sleepNanos(wait);
                    }
                }
                final int n = i;
                try {
                    producer.send(new ProducerRecord<>(topic, null, n + ":" + padding), (meta, error) -> {
                        long second = (System.nanoTime() - start) / 1_000_000_000L;
                        long[] bucket = perSecond.computeIfAbsent(second, k -> new long[2]);
                        if (error == null) {
                            synchronized (acked) {
                                acked.set(n);
                            }
                            ackedCount.incrementAndGet();
                            synchronized (bucket) {
                                bucket[0]++;
                            }
                        } else {
                            failed.incrementAndGet();
                            errors.computeIfAbsent(error.getClass().getSimpleName(), k -> new AtomicLong()).incrementAndGet();
                            synchronized (bucket) {
                                bucket[1]++;
                            }
                        }
                    });
                } catch (RuntimeException e) {   // buffer full for max.block.ms, or metadata unavailable
                    failed.incrementAndGet();
                    errors.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicLong()).incrementAndGet();
                }
            }
            producer.flush();
        }
        sent = i;
        Map<String, String> timeline = new TreeMap<>();
        perSecond.forEach((sec, b) -> timeline.put("%03d".formatted(sec), b[0] + " acked / " + b[1] + " failed"));
        return Map.of("acks", acks, "sent", i, "acked", ackedCount.get(), "failed", failed.get(),
                "seconds", Math.round((System.nanoTime() - start) / 1e7) / 100.0,
                "errors", errors.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())),
                "perSecond", timeline);
    }

    /** Reads the whole topic back and compares it with what the broker acknowledged. */
    public Map<String, Object> verify(String topic) {
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        c.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000);
        BitSet present = new BitSet();
        long records = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(c)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(pi -> new TopicPartition(topic, pi.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline && partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    records++;
                    String v = r.value();
                    present.set(Integer.parseInt(v.substring(0, v.indexOf(':'))));
                }
            }
        }
        BitSet lost;
        BitSet ackedCopy;
        synchronized (acked) {
            ackedCopy = (BitSet) acked.clone();
        }
        lost = (BitSet) ackedCopy.clone();
        lost.andNot(present);
        BitSet unackedButStored = (BitSet) present.clone();
        unackedButStored.andNot(ackedCopy);
        return Map.of("acks", lastAcks, "sent", sent, "acked", ackedCopy.cardinality(), "recordsInTopic", records,
                "distinctInTopic", present.cardinality(), "duplicatesInTopic", records - present.cardinality(),
                "ackedButLost", lost.cardinality(), "reportedFailedButStored", unackedButStored.cardinality(),
                "firstLost", lost.isEmpty() ? -1 : lost.nextSetBit(0));
    }

    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
