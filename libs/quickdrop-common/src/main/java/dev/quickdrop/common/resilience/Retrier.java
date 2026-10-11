package dev.quickdrop.common.resilience;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Retries for one downstream (scenario 8.3). Three knobs, each a toggle in the calling service:
 * <ul>
 *   <li><b>retries</b>: how many extra attempts after the first.</li>
 *   <li><b>backoff</b>: {@code none} retries at once; {@code exponential-jitter} waits a random time between 0 and
 *       100 ms × 2^attempt (capped at 2 s) — "full jitter", so retries from many callers don't arrive in waves.</li>
 *   <li><b>budget</b>: retries may add at most ~10% to the load. Every call earns 0.1 token, every retry spends 1;
 *       no token, no retry. When the downstream is overloaded, retries stop instead of multiplying the load.</li>
 * </ul>
 * Metric {@code quickdrop.retry.attempts{client, kind=first|retry|denied}}: retries ÷ first = amplification.
 */
public class Retrier {

    private static final double TOKENS_PER_CALL = 0.1;
    private static final double MAX_TOKENS = 20;

    private final Counter first;
    private final Counter retried;
    private final Counter denied;
    private double tokens = MAX_TOKENS;

    public Retrier(String client, MeterRegistry meters) {
        this.first = meters.counter("quickdrop.retry.attempts", "client", client, "kind", "first");
        this.retried = meters.counter("quickdrop.retry.attempts", "client", client, "kind", "retry");
        this.denied = meters.counter("quickdrop.retry.attempts", "client", client, "kind", "denied");
    }

    public <T> T call(Supplier<T> attempt, Predicate<RuntimeException> retryable, int retries, boolean jitter, boolean budget) {
        first.increment();
        deposit();
        for (int n = 0; ; n++) {
            try {
                return attempt.get();
            } catch (RuntimeException e) {
                if (n >= retries || !retryable.test(e)) {
                    throw e;
                }
                if (budget && !withdraw()) {
                    denied.increment();
                    throw e;   // out of retry budget: fail now rather than add load to a struggling downstream
                }
                if (jitter) {
                    long cap = Math.min(2000, 100L << Math.min(n, 10));
                    sleep(ThreadLocalRandom.current().nextLong(cap + 1));
                }
                retried.increment();
            }
        }
    }

    private synchronized void deposit() {
        tokens = Math.min(MAX_TOKENS, tokens + TOKENS_PER_CALL);
    }

    private synchronized boolean withdraw() {
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
