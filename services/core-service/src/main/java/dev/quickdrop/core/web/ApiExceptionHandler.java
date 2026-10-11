package dev.quickdrop.core.web;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;

import dev.quickdrop.core.inventory.ConcurrentUpdateException;
import dev.quickdrop.core.inventory.LockLostException;
import dev.quickdrop.core.inventory.LockUnavailableException;
import dev.quickdrop.core.inventory.OutOfStockException;
import dev.quickdrop.core.notification.NotificationService;
import dev.quickdrop.core.ratelimit.RateLimitedException;
import dev.quickdrop.core.redis.RedisUnavailable;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns failures into HTTP responses with a stable {@code error} code, and counts them in
 * {@code quickdrop_api_errors_total{error="..."}} so each failure mode gets its own line on a graph.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Every code we can return. Registered at 0 on startup — see the comment in the constructor. */
    private static final java.util.List<String> CODES = java.util.List.of("out_of_stock", "too_many_conflicts", "not_found",
            "invalid_request", "deadlock", "lock_timeout", "db_pool_exhausted", "internal_error", "rate_limited",
            "lock_unavailable", "lock_lost", "redis_unavailable", "rolled_back_after_publish");

    private final MeterRegistry meters;

    public ApiExceptionHandler(MeterRegistry meters) {
        this.meters = meters;
        // A counter that is only created on its first increment has no earlier sample, so Prometheus'
        // rate()/increase() cannot see that first burst at all. Pre-registering at 0 fixes it.
        CODES.forEach(code -> meters.counter("quickdrop.api.errors", "error", code));
    }

    @ExceptionHandler(OutOfStockException.class)
    ResponseEntity<Map<String, Object>> outOfStock(OutOfStockException e) {
        return error(HttpStatus.CONFLICT, "out_of_stock", e.getMessage());
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    ResponseEntity<Map<String, Object>> tooManyConflicts(ConcurrentUpdateException e) {
        return error(HttpStatus.CONFLICT, "too_many_conflicts", e.getMessage());
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<Map<String, Object>> rateLimited(RateLimitedException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", e.getMessage());
    }

    @ExceptionHandler(LockUnavailableException.class)
    ResponseEntity<Map<String, Object>> lockUnavailable(LockUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "lock_unavailable", e.getMessage());
    }

    /** The fencing token stopped a write from a holder whose lock had expired (scenario 3.2). */
    @ExceptionHandler(LockLostException.class)
    ResponseEntity<Map<String, Object>> lockLost(LockLostException e) {
        return error(HttpStatus.CONFLICT, "lock_lost", e.getMessage());
    }

    /** kafka-in-tx chaos (6.6): the event is already on Kafka, the order is not in the database. */
    @ExceptionHandler(NotificationService.RolledBackAfterPublish.class)
    ResponseEntity<Map<String, Object>> rolledBack(NotificationService.RolledBackAfterPublish e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "rolled_back_after_publish", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", e.getBindingResult().getAllErrors().toString());
    }

    /** Deadlocks (40P01) and lock timeouts (55P03) both arrive as pessimistic locking failures. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<Map<String, Object>> lockFailure(PessimisticLockingFailureException e) {
        String code = "40P01".equals(sqlState(e)) ? "deadlock" : "lock_timeout";
        return error(HttpStatus.CONFLICT, code, rootMessage(e));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
        if (RedisUnavailable.is(e)) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "redis_unavailable", rootMessage(e));
        }
        if (hasCause(e, SQLTransientConnectionException.class)) {
            // HikariCP gave up waiting for a free connection: the pool is exhausted.
            return error(HttpStatus.SERVICE_UNAVAILABLE, "db_pool_exhausted", rootMessage(e));
        }
        log.error("Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", rootMessage(e));
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        meters.counter("quickdrop.api.errors", "error", code).increment();
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message == null ? "" : message));
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
