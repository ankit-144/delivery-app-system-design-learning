package dev.quickdrop.core.db;

import java.util.function.Supplier;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Sends a block of work to the read replica (Phase 5). The routing data source looks at this flag
 * when a connection is actually needed, so it must be set <em>before</em> the transaction starts.
 */
public final class ReadRouting {

    private static final ThreadLocal<Boolean> REPLICA = ThreadLocal.withInitial(() -> false);

    private ReadRouting() {
    }

    public static <T> T onReplica(Supplier<T> work) {
        REPLICA.set(true);
        try {
            return work.get();
        } finally {
            REPLICA.remove();
        }
    }

    /** Chooses the "replica" or "primary" pool for each new connection. */
    public static class RoutingDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            return REPLICA.get() ? "replica" : "primary";
        }
    }
}
