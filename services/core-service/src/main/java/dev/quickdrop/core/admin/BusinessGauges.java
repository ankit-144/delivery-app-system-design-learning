package dev.quickdrop.core.admin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Exports {@code quickdrop_riders{status=...}} so dashboards can show the rider pool draining. */
@Component
public class BusinessGauges {

    private static final List<String> STATUSES = List.of("AVAILABLE", "BUSY", "OFFLINE");

    private final JdbcClient jdbc;
    private final Map<String, AtomicLong> riders = new ConcurrentHashMap<>();

    public BusinessGauges(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        for (String status : STATUSES) {
            AtomicLong value = new AtomicLong();
            riders.put(status, value);
            Gauge.builder("quickdrop.riders", value, AtomicLong::get).tag("status", status).register(meters);
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 3000)
    public void refresh() {
        Map<String, Long> counts = new ConcurrentHashMap<>();
        jdbc.sql("SELECT status, count(*) FROM riders GROUP BY status")
                .query((rs, n) -> counts.put(rs.getString(1), rs.getLong(2)))
                .list();
        STATUSES.forEach(status -> riders.get(status).set(counts.getOrDefault(status, 0L)));
    }
}
