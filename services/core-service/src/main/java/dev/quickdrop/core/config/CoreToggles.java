package dev.quickdrop.core.config;

import static dev.quickdrop.common.toggles.ToggleDefinition.choice;
import static dev.quickdrop.common.toggles.ToggleDefinition.number;

import java.util.List;

import dev.quickdrop.common.toggles.ToggleCatalog;
import dev.quickdrop.common.toggles.ToggleDefinition;
import org.springframework.stereotype.Component;

/**
 * Every behaviour switch of core-service. Defaults are what a first version of the product would
 * ship — which is often the wrong choice. Each scenario sets the toggles it needs explicitly.
 */
@Component
public class CoreToggles implements ToggleCatalog {

    // Phase 1 — correctness under concurrency

    public static final ToggleDefinition INVENTORY_RESERVATION = choice("inventory-reservation", "naive",
            "How stock is reserved for an order (scenarios 1.1, 1.5, 3.1).",
            "naive", "synchronized-in-tx", "synchronized", "pessimistic", "optimistic", "atomic", "redis-lock");

    public static final ToggleDefinition INVENTORY_LOCK_ORDER = choice("inventory-lock-order", "as-given",
            "Order in which a cart's stock rows are locked: as the customer listed them, or sorted by product id (scenario 1.4).",
            "as-given", "sorted");

    public static final ToggleDefinition OPTIMISTIC_MAX_RETRIES = number("optimistic-max-retries", 5,
            "How many times the optimistic strategy retries a version conflict before giving up (scenario 1.5).");

    public static final ToggleDefinition RIDER_ASSIGNMENT = choice("rider-assignment", "naive",
            "How a rider is claimed for an order (scenario 1.2).",
            "naive", "conditional");

    public static final ToggleDefinition ORDER_IDEMPOTENCY = choice("order-idempotency", "off",
            "Whether the Idempotency-Key header is honoured (scenario 1.3).",
            "off", "on");

    public static final ToggleDefinition RACE_WINDOW_MS = number("race-window-ms", 0,
            "Artificial pause between reading and writing in the naive strategies. Production has a tiny window and huge traffic; "
                    + "we have small traffic, so we widen the window to make races visible.");

    public static final ToggleDefinition PER_LINE_DELAY_MS = number("per-line-delay-ms", 0,
            "Artificial work per cart line while its stock row is locked (think: pricing or promo checks). "
                    + "Widens the window in which two carts can lock rows in opposite order (scenario 1.4).");

    // Phase 2 — one box hits the wall

    public static final ToggleDefinition NOTIFICATION_MODE = choice("notification-mode", "inline",
            "How SMS are sent: from core directly (Phase 2), via notification-service over HTTP, or via Kafka (Phase 6).",
            "none", "inline", "inline-in-tx", "async-memory", "http", "kafka-direct", "kafka-in-tx", "outbox");

    public static final ToggleDefinition ASYNC_QUEUE_CAPACITY = number("async-queue-capacity", 0,
            "Queue limit for notification-mode=async-memory. 0 means unbounded (scenario 6.3).");

    public static final ToggleDefinition VENDOR_TIMEOUT_MS = number("vendor-timeout-ms", 0,
            "Read timeout for calls to the SMS vendor. 0 means wait forever (scenario 2.7).");

    public static final ToggleDefinition RIDER_LOCATOR = choice("rider-locator", "db-nearby",
            "How nearby available riders are found (scenarios 2.6, 3.4).",
            "db-scan", "db-nearby", "memory", "redis-geo");

    public static final ToggleDefinition ORDER_HISTORY_QUERY = choice("order-history-query", "naive",
            "How a customer's order history is loaded (scenario 2.5).",
            "naive", "two-step");

    // Phase 3 — scale out, Redis

    public static final ToggleDefinition REDIS_LOCK_TTL_MS = number("redis-lock-ttl-ms", 2000,
            "How long a Redis stock lock lives if its holder never releases it (scenario 3.2).");

    public static final ToggleDefinition REDIS_LOCK_WAIT_MS = number("redis-lock-wait-ms", 5000,
            "How long to keep retrying to get a busy Redis lock before giving up with lock_unavailable.");

    public static final ToggleDefinition REDIS_LOCK_FENCING = choice("redis-lock-fencing", "off",
            "Check the lock's fencing token when writing stock, so a holder whose lock expired cannot write (scenario 3.2).",
            "off", "on");

    public static final ToggleDefinition LOCK_HOLDER_PAUSE_PERCENT = number("lock-holder-pause-percent", 0,
            "Percentage of Redis lock holders that freeze between reading and writing stock — a GC pause (scenario 3.2).");

    public static final ToggleDefinition LOCK_HOLDER_PAUSE_MS = number("lock-holder-pause-ms", 3000,
            "How long those holders freeze. Longer than redis-lock-ttl-ms means the lock expires under them.");

    public static final ToggleDefinition SCHEDULER_LOCK = choice("scheduler-lock", "none",
            "How the pending-rider job avoids running on every instance at once (scenario 3.6).",
            "none", "redis", "skip-locked");

    public static final ToggleDefinition RATE_LIMITER = choice("rate-limiter", "off",
            "Rate limiting algorithm for OTPs and orders (scenario 3.5).",
            "off", "local", "redis-fixed", "redis-sliding", "redis-token-bucket");

    public static final ToggleDefinition RATE_LIMIT_OTP_PER_MINUTE = number("rate-limit-otp-per-minute", 5,
            "OTPs allowed per phone number per minute.");

    public static final ToggleDefinition RATE_LIMIT_ORDERS_PER_MINUTE = number("rate-limit-orders-per-minute", 30,
            "Orders allowed per customer per minute.");

    public static final ToggleDefinition PRODUCT_CACHE = choice("product-cache", "none",
            "Where product details (names, prices) are cached (scenario 3.3).",
            "none", "local", "redis", "two-tier");

    public static final ToggleDefinition PRODUCT_CACHE_TTL_S = number("product-cache-ttl-s", 300,
            "How long a cached product lives.");

    public static final ToggleDefinition BESTSELLERS_CACHE = choice("bestsellers-cache", "none",
            "Caching of the expensive best-sellers query (scenario 3.8).",
            "none", "cache-aside", "single-flight");

    public static final ToggleDefinition BESTSELLERS_TTL_S = number("bestsellers-ttl-s", 10,
            "How long cached best-sellers live.");

    public static final ToggleDefinition REDIS_FAILURE_MODE = choice("redis-failure-mode", "fail",
            "What Redis-backed features do when Redis is unreachable: fail the request, or degrade (scenario 3.9).",
            "fail", "degrade");

    public static final ToggleDefinition BESTSELLERS_FALLBACK = choice("bestsellers-fallback", "compute",
            "When Redis is down: run the expensive query for every request, or serve the last copy this instance saw (scenario 3.9).",
            "compute", "last-known");

    public static final ToggleDefinition REDIS_CIRCUIT_BREAKER = choice("redis-circuit-breaker", "off",
            "Stop calling Redis for a while after repeated failures, so fallbacks happen instantly instead of after a timeout (scenario 3.9).",
            "off", "on");

    // Phase 4 — live schema changes

    public static final ToggleDefinition CUSTOMER_PHONE_SCHEMA = choice("customer-phone-schema", "v1",
            "Which app version this instance pretends to run while customers.phone is renamed (scenario 4.4).",
            "v1", "v1.5", "v2", "v3");

    // Phase 5 — scaling the database

    public static final ToggleDefinition READ_ROUTING = choice("read-routing", "primary",
            "Where customers' order-history reads go: primary, replica, or replica with read-your-writes (scenario 5.1).",
            "primary", "replica", "replica-ryw");

    public static final ToggleDefinition RYW_WINDOW_S = number("ryw-window-s", 5,
            "How long after a write a customer's reads stay on the primary (replica-ryw).");

    public static final ToggleDefinition SHARD_KEY = choice("shard-key", "city",
            "Which shard owns a sharded order: by the store's city, or by a hash of the customer (scenario 5.2).",
            "city", "customer");

    public static final ToggleDefinition ID_STRATEGY = choice("id-strategy", "sequence",
            "Order ids in the sharding lab: each shard's own sequence, or Snowflake ids (scenario 5.4).",
            "sequence", "snowflake");

    public static final ToggleDefinition CROSS_SHARD_CRASH_PERCENT = number("cross-shard-crash-percent", 0,
            "Percentage of cross-shard moves that crash between the insert and the delete (scenario 5.7).");

    // Phase 6 — notifications

    public static final ToggleDefinition NOTIFY_TIMEOUT_MS = number("notify-timeout-ms", 0,
            "Read timeout for calls to notification-service in http mode. 0 = wait forever (scenarios 6.1, 6.2).");

    public static final ToggleDefinition NOTIFY_CIRCUIT_BREAKER = choice("notify-circuit-breaker", "off",
            "Stop calling notification-service for a while after repeated failures (scenario 6.2).", "off", "on");

    public static final ToggleDefinition NOTIFY_BULKHEAD = number("notify-bulkhead", 0,
            "Most concurrent calls to notification-service; extra calls are dropped at once. 0 = no limit (scenario 6.2).");

    public static final ToggleDefinition NOTIFY_RETRIES = number("notify-retries", 0,
            "Extra attempts when a call to notification-service fails or times out (scenario 8.3).");

    public static final ToggleDefinition NOTIFY_RETRY_BACKOFF = choice("notify-retry-backoff", "none",
            "Wait between retries to notification-service: none, or exponential backoff with full jitter (scenario 8.3).",
            "none", "exponential-jitter");

    public static final ToggleDefinition NOTIFY_RETRY_BUDGET = choice("notify-retry-budget", "off",
            "Cap retries to notification-service at ~10% of calls (scenario 8.3).", "off", "on");

    public static final ToggleDefinition LOAD_SHEDDING = choice("load-shedding", "off",
            "Under overload, reject browsing and order history early (503) so checkout keeps its database connections "
                    + "and CPU (scenario 8.4).", "off", "priority");

    public static final ToggleDefinition SHED_BROWSE_LIMIT = number("shed-browse-limit", 4,
            "With load-shedding=priority: sheddable requests allowed in flight per instance; the rest get 503 at once.");

    public static final ToggleDefinition OTP_TOPIC = choice("otp-topic", "shared",
            "Kafka topic for OTPs: the shared notifications topic, or a separate priority topic (scenario 6.8).",
            "shared", "priority");

    public static final ToggleDefinition OUTBOX_TRACE_CONTEXT = choice("outbox-trace-context", "drop",
            "Store the request's trace context with each outbox row so the relay continues the same trace, or drop it "
                    + "and every Kafka message starts a new, orphaned trace (scenario 8.1).",
            "drop", "propagate");

    public static final ToggleDefinition CRASH_AFTER_COMMIT_PERCENT = number("crash-after-commit-percent", 0,
            "kafka-direct: percentage of orders where the process 'dies' after commit, before publishing (scenario 6.6).");

    public static final ToggleDefinition ROLLBACK_AFTER_PUBLISH_PERCENT = number("rollback-after-publish-percent", 0,
            "kafka-in-tx: percentage of orders that roll back after the event was already published (scenario 6.6).");

    // Runtime infrastructure knobs (applied live by RuntimeKnobs; start values come from env vars)

    public static final ToggleDefinition TOMCAT_MAX_THREADS = number("tomcat-max-threads", 50,
            "Request threads. Ignored when virtual threads are on (scenarios 2.1, 2.8).");

    public static final ToggleDefinition DB_POOL_SIZE = number("db-pool-size", 10,
            "Maximum database connections in this instance's pool (scenarios 2.2, 2.3).");

    public static final ToggleDefinition DB_POOL_TIMEOUT_MS = number("db-pool-timeout-ms", 5000,
            "How long a request waits for a free connection before failing with db_pool_exhausted.");

    // Background jobs

    public static final ToggleDefinition AUTO_DELIVER_AFTER_MS = number("auto-deliver-after-ms", 20000,
            "Simulated riders deliver an order this long after assignment, freeing the rider. 0 disables it.");

    public static final ToggleDefinition PENDING_RIDER_JOB = choice("pending-rider-job", "on",
            "Background job that retries rider assignment for orders still waiting for a rider (scenario 3.6).",
            "off", "on");

    @Override
    public List<ToggleDefinition> toggles() {
        return List.of(INVENTORY_RESERVATION, INVENTORY_LOCK_ORDER, OPTIMISTIC_MAX_RETRIES, RIDER_ASSIGNMENT,
                ORDER_IDEMPOTENCY, RACE_WINDOW_MS, PER_LINE_DELAY_MS, NOTIFICATION_MODE, ASYNC_QUEUE_CAPACITY, VENDOR_TIMEOUT_MS,
                RIDER_LOCATOR, ORDER_HISTORY_QUERY, REDIS_LOCK_TTL_MS, REDIS_LOCK_WAIT_MS, REDIS_LOCK_FENCING,
                LOCK_HOLDER_PAUSE_PERCENT, LOCK_HOLDER_PAUSE_MS, SCHEDULER_LOCK, RATE_LIMITER, RATE_LIMIT_OTP_PER_MINUTE,
                RATE_LIMIT_ORDERS_PER_MINUTE, PRODUCT_CACHE, PRODUCT_CACHE_TTL_S, BESTSELLERS_CACHE, BESTSELLERS_TTL_S,
                REDIS_FAILURE_MODE, BESTSELLERS_FALLBACK, REDIS_CIRCUIT_BREAKER, CUSTOMER_PHONE_SCHEMA, READ_ROUTING, RYW_WINDOW_S, SHARD_KEY, ID_STRATEGY,
                CROSS_SHARD_CRASH_PERCENT, NOTIFY_TIMEOUT_MS, NOTIFY_CIRCUIT_BREAKER, NOTIFY_BULKHEAD, NOTIFY_RETRIES, NOTIFY_RETRY_BACKOFF, NOTIFY_RETRY_BUDGET, LOAD_SHEDDING, SHED_BROWSE_LIMIT, OTP_TOPIC,
                OUTBOX_TRACE_CONTEXT, CRASH_AFTER_COMMIT_PERCENT, ROLLBACK_AFTER_PUBLISH_PERCENT, TOMCAT_MAX_THREADS, DB_POOL_SIZE, DB_POOL_TIMEOUT_MS,
                AUTO_DELIVER_AFTER_MS, PENDING_RIDER_JOB);
    }
}
