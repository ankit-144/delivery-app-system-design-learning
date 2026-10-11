package dev.quickdrop.notification.kafkalab;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * K11 — read-process-write: copy lab-6p → lab-out, "crashing" every N records before committing.
 * <ul>
 *   <li>{@code transactional=false}: write, then commit the input offsets. A crash in between re-copies the batch:
 *       duplicates in lab-out.</li>
 *   <li>{@code transactional=true}: the writes AND the input offsets are one Kafka transaction
 *       (sendOffsetsToTransaction). A crash aborts it; read_committed consumers never see the aborted writes.</li>
 * </ul>
 */
@Component
public class LabCopier {

    private final String bootstrap;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong copied = new AtomicLong();
    private final AtomicLong crashes = new AtomicLong();
    private Thread worker;

    public LabCopier(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.bootstrap = bootstrap;
    }

    public synchronized Map<String, Object> start(boolean transactional, int crashEvery, String group) {
        stop();
        running.set(true);
        copied.set(0);
        crashes.set(0);
        worker = Thread.ofPlatform().name("lab-copier").start(() -> {
            while (running.get()) {
                copyUntilCrash(transactional, crashEvery, group);
            }
        });
        return Map.of("copying", "lab-6p -> lab-out", "transactional", transactional, "crashEvery", crashEvery);
    }

    public synchronized Map<String, Object> stop() {
        running.set(false);
        if (worker != null) {
            try {
                worker.join(15000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("copiedIncludingRetries", copied.get());
        out.put("crashes", crashes.get());
        return out;
    }

    private void copyUntilCrash(boolean transactional, int crashEvery, String group) {
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        c.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        c.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        if (transactional) {
            p.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "lab-copier");   // fixed id: a restarted copier fences the old one
        }
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(c); KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            if (transactional) {
                producer.initTransactions();
            }
            consumer.subscribe(List.of("lab-6p"));
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    continue;
                }
                if (transactional) {
                    producer.beginTransaction();
                }
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                boolean crash = false;
                for (ConsumerRecord<String, String> r : records) {
                    producer.send(new ProducerRecord<>("lab-out", r.key(), r.value()));
                    offsets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
                    if (crashEvery > 0 && copied.incrementAndGet() % crashEvery == 0) {
                        crash = true;
                        break;
                    }
                    if (crashEvery <= 0) {
                        copied.incrementAndGet();
                    }
                }
                if (crash) {
                    crashes.incrementAndGet();
                    if (transactional) {
                        producer.flush();              // the writes reached the broker (as they usually have when a process dies)...
                        producer.abortTransaction();   // ...but the transaction never commits: they're marked aborted
                    } else {
                        producer.flush();              // the writes are out; the offsets are not committed
                    }
                    return;                            // "restart": new consumer + producer, resume from committed offsets
                }
                if (transactional) {
                    producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                    producer.commitTransaction();
                } else {
                    producer.flush();
                    consumer.commitSync(offsets);
                }
            }
        } catch (RuntimeException e) {
            sleep(500);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
