package dev.quickdrop.core.notification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox — scenario 6.6.
 * <pre>
 *  order transaction:  INSERT order …;  INSERT INTO outbox (topic, key, payload) …;  COMMIT
 *  relay (every 200 ms, on every instance):
 *      BEGIN; SELECT … FROM outbox WHERE published_at IS NULL … FOR UPDATE SKIP LOCKED   (from 3.6)
 *      send each row to Kafka, wait for the broker to acknowledge
 *      UPDATE outbox SET published_at = now() …; COMMIT
 * </pre>
 * The order and its event commit together or not at all. If the relay crashes after sending but before
 * its commit, the rows are sent again: at-least-once, so consumers must tolerate duplicates (K3).
 *
 * <p>Tracing (8.1): the relay runs later, on a scheduler thread, often on another instance. With
 * {@code outbox-trace-context=propagate} the row carries the request's {@code traceparent} and the relay
 * continues that trace; with {@code drop} every published event starts an unrelated trace.
 */
@Component
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaPublisher publisher;
    private final TransactionTemplate tx;
    private final ToggleRegistry toggles;
    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeMs = new AtomicLong();
    private final Counter published;

    public Outbox(JdbcClient jdbc, KafkaTemplate<String, String> kafka, KafkaPublisher publisher, TransactionTemplate tx,
                  MeterRegistry meters, ToggleRegistry toggles, ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.publisher = publisher;
        this.tx = tx;
        this.toggles = toggles;
        this.tracer = tracer;
        this.propagator = propagator;
        this.published = Counter.builder("quickdrop.outbox.published").register(meters);
        Gauge.builder("quickdrop.outbox.pending", pending, AtomicLong::get).description("Events written but not yet published").register(meters);
        Gauge.builder("quickdrop.outbox.oldest.age", oldestAgeMs, AtomicLong::get).baseUnit("milliseconds").register(meters);
    }

    /** Must be called inside the business transaction. */
    public void add(SmsMessage message) {
        String traceParent = toggles.is(CoreToggles.OUTBOX_TRACE_CONTEXT, "propagate") ? currentTraceParent() : null;
        jdbc.sql("INSERT INTO outbox (topic, msg_key, payload, trace_parent) VALUES (?, ?, ?, ?)")
                .params(publisher.topicFor(message), message.to(), publisher.toJson(message), traceParent)
                .update();
    }

    /** "00-&lt;trace id&gt;-&lt;span id&gt;-01" for the current span, or null when tracing is off. */
    private String currentTraceParent() {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null || t.currentTraceContext().context() == null) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        p.inject(t.currentTraceContext().context(), headers, Map::put);
        return headers.get("traceparent");
    }

    /** Sends the row inside a span that continues the trace stored with it (if any). */
    private CompletableFuture<?> send(Row row) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (row.traceParent() == null || t == null || p == null) {
            return kafka.send(row.topic(), row.key(), row.payload());
        }
        Span span = p.extract(Map.of("traceparent", row.traceParent()), Map::get).name("outbox relay").start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            return kafka.send(row.topic(), row.key(), row.payload());
        } finally {
            span.end();
        }
    }

    record Row(long id, String topic, String key, String payload, String traceParent) {
    }

    @Scheduled(fixedDelay = 200, initialDelay = 5000)
    public void relay() {
        try {
            Integer sent;
            do {
                sent = tx.execute(status -> {
                    List<Row> rows = jdbc.sql("""
                                    SELECT id, topic, msg_key, payload, trace_parent FROM outbox WHERE published_at IS NULL
                                    ORDER BY id LIMIT 500 FOR UPDATE SKIP LOCKED
                                    """)
                            .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                            .list();
                    if (rows.isEmpty()) {
                        return 0;
                    }
                    List<CompletableFuture<?>> acks = new ArrayList<>();
                    for (Row row : rows) {
                        acks.add(send(row));
                    }
                    try {
                        CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("Kafka did not acknowledge the batch: " + e.getMessage(), e);
                    }
                    jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ANY(?)")
                            .param(rows.stream().map(Row::id).toArray(Long[]::new))
                            .update();
                    published.increment(rows.size());
                    return rows.size();
                });
            } while (sent != null && sent == 500);   // keep going while there's a backlog
        } catch (RuntimeException e) {
            log.debug("Outbox relay will retry: {}", e.getMessage());   // rows stay unpublished and are retried
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void measure() {
        jdbc.sql("""
                        SELECT count(*), coalesce(extract(epoch FROM now() - min(created_at)) * 1000, 0)::bigint
                        FROM outbox WHERE published_at IS NULL
                        """)
                .query((rs, n) -> {
                    pending.set(rs.getLong(1));
                    oldestAgeMs.set(rs.getLong(2));
                    return null;
                })
                .list();
        // The outbox is a queue, not an archive: delete what was published more than an hour ago.
        jdbc.sql("DELETE FROM outbox WHERE published_at < now() - interval '1 hour'").update();
    }
}
