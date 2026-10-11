package dev.quickdrop.notification.kafkalab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Produces test messages with every producer knob exposed (Phase 7). Each message is a little order
 * lifecycle event: {"messageId", "orderId", "seq" (0 PLACED, 1 RIDER_ASSIGNED, 2 DELIVERED), "padding"}.
 */
@Component
public class LabProducer {

    static final String[] EVENTS = {"PLACED", "RIDER_ASSIGNED", "DELIVERED"};

    /** Padding made of words, so compression ratios look like real JSON (a run of "xxxx" compresses ~50:1). */
    static final String[] WORDS = ("milk bread eggs paneer atta rice dal onion tomato potato banana apple curd butter "
            + "ghee sugar salt tea coffee biscuit chips soap shampoo detergent maggi juice water coke ice-cream chocolate "
            + "flat floor tower sector road lane near opposite gate block phase main cross nagar colony market metro "
            + "indiranagar koramangala whitefield andheri bandra powai saket dwarka gurgaon noida hsr btm jayanagar").split(" ");

    public record Params(String topic, int count, String keys, int eventsPerKey, String acks, int lingerMs, int batchKb,
                         String compression, int payloadBytes, boolean idempotence, int keySpace) {
    }

    private final String bootstrap;

    public LabProducer(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.bootstrap = bootstrap;
    }

    public Map<String, Object> produce(Params p) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, p.acks());
        props.put(ProducerConfig.LINGER_MS_CONFIG, p.lingerMs());
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, p.batchKb() * 1024);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, p.compression());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, p.idempotence() && p.acks().equals("all"));
        AtomicLong acked = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        long start = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            int partitions = producer.partitionsFor(p.topic()).size();
            for (int i = 0; i < p.count(); i++) {
                long orderId = i / Math.max(1, p.eventsPerKey());
                int seq = i % Math.max(1, p.eventsPerKey());
                String key = switch (p.keys()) {
                    case "order" -> "order-" + orderId;
                    case "rider" -> "rider-" + (i % Math.max(1, p.keySpace()));   // K10: many updates per rider
                    case "random" -> UUID.randomUUID().toString();
                    default -> null;
                };
                String value = "{\"messageId\":\"%s\",\"orderId\":%d,\"seq\":%d,\"type\":\"%s\",\"padding\":\"%s\"}"
                        .formatted(UUID.randomUUID(), orderId, seq, EVENTS[seq % EVENTS.length], padding(p.payloadBytes()));
                // No key: spread over partitions (what many services do when they leave the key empty).
                ProducerRecord<String, String> record = key == null
                        ? new ProducerRecord<>(p.topic(), ThreadLocalRandom.current().nextInt(partitions), null, value)
                        : new ProducerRecord<>(p.topic(), key, value);
                producer.send(record, (meta, error) -> (error == null ? acked : failed).incrementAndGet());
            }
            producer.flush();
            double seconds = (System.nanoTime() - start) / 1e9;
            out.put("sent", p.count());
            out.put("acked", acked.get());
            out.put("failed", failed.get());
            out.put("seconds", Math.round(seconds * 100) / 100.0);
            out.put("messagesPerSecond", Math.round(p.count() / seconds));
            // The producer's own view of batching and compression (K8).
            producer.metrics().forEach((name, metric) -> {
                if (name.group().equals("producer-metrics") && switch (name.name()) {
                    case "batch-size-avg", "compression-rate-avg", "record-queue-time-avg", "request-latency-avg",
                         "records-per-request-avg", "outgoing-byte-rate", "outgoing-byte-total", "record-size-avg" -> true;
                    default -> false;
                }) {
                    Object v = metric.metricValue();
                    out.put(name.name(), v instanceof Double d ? Math.round(d * 100) / 100.0 : v);
                }
            });
        }
        return out;
    }

    private static String padding(int bytes) {
        StringBuilder sb = new StringBuilder(bytes + 16);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (sb.length() < bytes) {
            sb.append(WORDS[random.nextInt(WORDS.length)]).append(' ');
            if (random.nextInt(8) == 0) {
                sb.append(random.nextInt(1, 999)).append(' ');
            }
        }
        sb.setLength(Math.max(0, bytes));
        return sb.toString();
    }
}
