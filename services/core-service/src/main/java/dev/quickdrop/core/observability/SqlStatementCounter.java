package dev.quickdrop.core.observability;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate calls this for every SQL statement it prepares. We count them per request thread so
 * {@link SqlStatementInterceptor} can record "JPA statements per request" — the N+1 detector for
 * scenario 2.5. (Statements issued through JdbcClient are not counted.)
 */
public class SqlStatementCounter implements StatementInspector {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }

    public static void reset() {
        COUNT.get()[0] = 0;
    }

    public static int current() {
        return COUNT.get()[0];
    }
}
