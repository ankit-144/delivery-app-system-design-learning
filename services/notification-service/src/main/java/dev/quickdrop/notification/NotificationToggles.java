package dev.quickdrop.notification;

import static dev.quickdrop.common.toggles.ToggleDefinition.choice;
import static dev.quickdrop.common.toggles.ToggleDefinition.number;

import java.util.List;

import dev.quickdrop.common.toggles.ToggleCatalog;
import dev.quickdrop.common.toggles.ToggleDefinition;
import org.springframework.stereotype.Component;

@Component
public class NotificationToggles implements ToggleCatalog {

    public static final ToggleDefinition DELIVERY_MODE = choice("delivery-mode", "sync",
            "What POST /notifications does: send before answering (sync), queue in memory (async-memory), "
                    + "or store in Postgres for workers to send (db-queue). Kafka messages are always consumed (scenarios 6.1-6.4).",
            "sync", "async-memory", "db-queue");

    public static final ToggleDefinition WORKERS = number("workers", 4,
            "Sender threads for async-memory and db-queue.");

    public static final ToggleDefinition DB_POLL_MS = number("db-poll-ms", 100,
            "How often an idle db-queue worker asks Postgres for new work (scenario 6.4).");

    public static final ToggleDefinition VENDOR_TIMEOUT_MS = number("vendor-timeout-ms", 3000,
            "Read timeout for the SMS vendor.");

    public static final ToggleDefinition VENDOR_RETRIES = number("vendor-retries", 0,
            "Extra attempts when a send to the SMS vendor fails or times out (scenario 8.3).");

    public static final ToggleDefinition VENDOR_RETRY_BACKOFF = choice("vendor-retry-backoff", "none",
            "Wait between vendor retries: none, or exponential backoff with full jitter (scenario 8.3).",
            "none", "exponential-jitter");

    public static final ToggleDefinition VENDOR_RETRY_BUDGET = choice("vendor-retry-budget", "off",
            "Cap vendor retries at ~10% of sends (scenario 8.3).", "off", "on");

    public static final ToggleDefinition VENDOR_RATE_LIMIT = choice("vendor-rate-limit", "off",
            "Throttle sends to the vendor: not at all, per instance, or shared by all instances through Redis (scenario 6.7).",
            "off", "local", "redis");

    public static final ToggleDefinition VENDOR_RATE_LIMIT_RPS = number("vendor-rate-limit-rps", 90,
            "Sends per second allowed by vendor-rate-limit (per instance for local, in total for redis).");

    public static final ToggleDefinition POISON_HANDLING = choice("poison-handling", "skip",
            "What the Kafka consumers do with a message they can't process (K4): retry forever (the partition is stuck), "
                    + "skip it after 3 tries (it's gone, only a log line remains), or move it to notifications.DLT.",
            "retry-forever", "skip", "dlq");

    @Override
    public List<ToggleDefinition> toggles() {
        return List.of(DELIVERY_MODE, WORKERS, DB_POLL_MS, VENDOR_TIMEOUT_MS, VENDOR_RETRIES, VENDOR_RETRY_BACKOFF, VENDOR_RETRY_BUDGET, VENDOR_RATE_LIMIT, VENDOR_RATE_LIMIT_RPS, POISON_HANDLING);
    }
}
