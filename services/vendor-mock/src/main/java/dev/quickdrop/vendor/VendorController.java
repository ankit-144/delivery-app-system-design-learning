package dev.quickdrop.vendor;

import java.util.Map;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.quickdrop.common.toggles.ToggleRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pretends to be an SMS / push provider:
 * <pre>
 * POST /v1/sms   {"messageId": "...", "type": "OTP", "to": "+91...", "body": "..."}
 * POST /v1/push  same shape
 * GET  /v1/stats what the vendor has seen so far
 * GET  /v1/stats/ids?prefix=order-   message ids received (reconciliation)
 * POST /v1/stats/reset
 * </pre>
 * Make it misbehave with {@code PUT /admin/toggles/latency-ms?value=1000} and friends.
 */
@RestController
public class VendorController {

    private final ToggleRegistry toggles;
    private final MeterRegistry meters;
    private final DuplicateDetector duplicates = new DuplicateDetector();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();
    private final Timer queueWait;
    private volatile Semaphore workers = new Semaphore(1, true);
    private volatile long workersSize = 1;
    private final AtomicLong windowSecond = new AtomicLong();
    private final AtomicLong windowCount = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public VendorController(ToggleRegistry toggles, MeterRegistry meters) {
        this.toggles = toggles;
        this.meters = meters;
        Gauge.builder("vendor.in.flight", inFlight, AtomicInteger::get)
                .description("Sends currently being processed")
                .register(meters);
        Gauge.builder("vendor.queue.length", waiting, AtomicInteger::get)
                .description("Requests waiting for a worker (overload-mode=queue)")
                .register(meters);
        this.queueWait = Timer.builder("vendor.queue.wait").description("Time a request waited before a worker picked it up")
                .publishPercentileHistogram().register(meters);
    }

    public record Message(String messageId, String type, String to, String body) {
    }

    @PostMapping("/v1/{channel:sms|push}")
    public ResponseEntity<Map<String, Object>> send(@PathVariable String channel, @RequestBody Message message) {
        String type = message.type() == null ? "UNKNOWN" : message.type();

        if (!withinRateLimit()) {
            return reject(channel, type, HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        long maxConcurrency = toggles.getLong(VendorToggles.MAX_CONCURRENCY);
        if (maxConcurrency > 0 && toggles.is(VendorToggles.OVERLOAD_MODE, "queue")) {
            // Wait in line for one of maxConcurrency workers (FIFO). Nobody here knows whether the caller is still
            // waiting: a request whose client timed out a second ago is processed exactly like a fresh one.
            Semaphore pool = workers(maxConcurrency);
            waiting.incrementAndGet();
            long queuedAt = System.nanoTime();
            pool.acquireUninterruptibly();
            waiting.decrementAndGet();
            queueWait.record(Duration.ofNanos(System.nanoTime() - queuedAt));
            try {
                return process(channel, type, message, Long.MAX_VALUE);
            } finally {
                pool.release();
            }
        }
        return process(channel, type, message, maxConcurrency);
    }

    private ResponseEntity<Map<String, Object>> process(String channel, String type, Message message, long maxConcurrency) {
        int current = inFlight.incrementAndGet();
        try {
            if (maxConcurrency > 0 && current > maxConcurrency) {
                return reject(channel, type, HttpStatus.SERVICE_UNAVAILABLE, "overloaded");
            }
            sleep(latency());
            if (ThreadLocalRandom.current().nextInt(100) < toggles.getLong(VendorToggles.ERROR_RATE_PERCENT)) {
                return reject(channel, type, HttpStatus.INTERNAL_SERVER_ERROR, "vendor_error");
            }
            boolean duplicate = message.messageId() != null && duplicates.isDuplicate(message.messageId());
            if (duplicate) {
                duplicateCount.incrementAndGet();
                Counter.builder("vendor.duplicate.messages")
                        .description("Messages received more than once (the customer got it twice)")
                        .tag("channel", channel).tag("type", type)
                        .register(meters).increment();
            }
            accepted.incrementAndGet();
            count(channel, type, "accepted");
            return ResponseEntity.accepted().body(Map.of("status", "accepted", "duplicate", duplicate));
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @GetMapping("/v1/stats")
    public Map<String, Object> stats() {
        return Map.of("accepted", accepted.get(), "duplicates", duplicateCount.get(), "rejected", rejected.get(),
                "inFlight", inFlight.get());
    }

    @GetMapping("/v1/stats/ids")
    public java.util.List<String> ids(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String prefix) {
        return duplicates.idsStartingWith(prefix);
    }

    @PostMapping("/v1/stats/reset")
    public Map<String, Object> resetStats() {
        accepted.set(0);
        duplicateCount.set(0);
        rejected.set(0);
        duplicates.clear();
        return stats();
    }

    private synchronized Semaphore workers(long size) {
        if (size != workersSize) {
            workers = new Semaphore((int) size, true);
            workersSize = size;
        }
        return workers;
    }

    /** Fixed one-second window shared by all callers, like most vendor quotas. */
    private boolean withinRateLimit() {
        long limit = toggles.getLong(VendorToggles.RATE_LIMIT_RPS);
        if (limit <= 0) {
            return true;
        }
        long nowSecond = System.currentTimeMillis() / 1000;
        if (windowSecond.getAndSet(nowSecond) != nowSecond) {
            windowCount.set(0);
        }
        return windowCount.incrementAndGet() <= limit;
    }

    private ResponseEntity<Map<String, Object>> reject(String channel, String type, HttpStatus status, String reason) {
        rejected.incrementAndGet();
        count(channel, type, reason);
        return ResponseEntity.status(status).body(Map.of("status", reason));
    }

    private void count(String channel, String type, String result) {
        Counter.builder("vendor.messages")
                .tag("channel", channel).tag("type", type).tag("result", result)
                .register(meters).increment();
    }

    private long latency() {
        if (ThreadLocalRandom.current().nextInt(100) < toggles.getLong(VendorToggles.SLOW_PERCENT)) {
            return toggles.getLong(VendorToggles.SLOW_MS);
        }
        return toggles.getLong(VendorToggles.LATENCY_MS) + jitter();
    }

    private long jitter() {
        long jitter = toggles.getLong(VendorToggles.JITTER_MS);
        return jitter > 0 ? ThreadLocalRandom.current().nextLong(jitter + 1) : 0;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
