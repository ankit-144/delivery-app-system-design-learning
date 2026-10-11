package dev.quickdrop.core.config;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import dev.quickdrop.common.toggles.ToggleChangedEvent;
import dev.quickdrop.common.toggles.ToggleRegistry;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Applies the infrastructure toggles while the app runs, so a scenario can resize the thread pool or
 * the connection pool without a restart:
 * <ul>
 *   <li>{@code tomcat-max-threads} → Tomcat's request thread pool</li>
 *   <li>{@code db-pool-size}, {@code db-pool-timeout-ms} → HikariCP, through its management bean</li>
 * </ul>
 * {@code GET /admin/runtime} shows what this JVM actually has.
 */
@Component
@RestController
public class RuntimeKnobs {

    private static final Logger log = LoggerFactory.getLogger(RuntimeKnobs.class);

    private final ToggleRegistry toggles;
    private final HikariDataSource dataSource;
    private final ServletWebServerApplicationContext context;
    private final boolean virtualThreads;

    public RuntimeKnobs(ToggleRegistry toggles, @org.springframework.beans.factory.annotation.Qualifier("primaryPool") HikariDataSource dataSource,
                        ServletWebServerApplicationContext context,
                        Environment env) {
        this.toggles = toggles;
        this.dataSource = dataSource;
        this.context = context;
        this.virtualThreads = env.getProperty("spring.threads.virtual.enabled", Boolean.class, false);
    }

    @EventListener
    public void onToggle(ToggleChangedEvent event) {
        switch (event.toggle()) {
            case "tomcat-max-threads" -> resizeTomcat((int) toggles.getLong(CoreToggles.TOMCAT_MAX_THREADS));
            case "db-pool-size" -> resizePool((int) toggles.getLong(CoreToggles.DB_POOL_SIZE));
            case "db-pool-timeout-ms" -> dataSource.getHikariConfigMXBean().setConnectionTimeout(toggles.getLong(CoreToggles.DB_POOL_TIMEOUT_MS));
            default -> {
            }
        }
    }

    private void resizeTomcat(int max) {
        if (virtualThreads) {
            log.warn("Virtual threads are on: there is no request thread pool to resize");
            return;
        }
        ProtocolHandler handler = protocolHandler();
        if (handler.getExecutor() instanceof ThreadPoolExecutor pool) {
            // Grow the maximum before the core size, shrink the core size before the maximum.
            if (max >= pool.getMaximumPoolSize()) {
                pool.setMaximumPoolSize(max);
                pool.setCorePoolSize(Math.min(pool.getCorePoolSize(), max));
            } else {
                pool.setCorePoolSize(Math.min(pool.getCorePoolSize(), max));
                pool.setMaximumPoolSize(max);
            }
        }
        if (handler instanceof AbstractProtocol<?> protocol) {
            protocol.setMaxThreads(max);
        }
        log.info("Tomcat max threads -> {}", max);
    }

    private void resizePool(int size) {
        HikariConfigMXBean config = dataSource.getHikariConfigMXBean();
        if (size < config.getMaximumPoolSize()) {
            config.setMinimumIdle(Math.min(config.getMinimumIdle(), size));
            config.setMaximumPoolSize(size);
            // Close idle connections above the new size now instead of waiting for them to time out.
            dataSource.getHikariPoolMXBean().softEvictConnections();
        } else {
            config.setMaximumPoolSize(size);
        }
        log.info("Hikari maximum pool size -> {}", size);
    }

    private ProtocolHandler protocolHandler() {
        return ((TomcatWebServer) context.getWebServer()).getTomcat().getConnector().getProtocolHandler();
    }

    @GetMapping("/admin/runtime")
    public Map<String, Object> runtime() {
        Runtime rt = Runtime.getRuntime();
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("instance", toggles.instance());
        info.put("java", System.getProperty("java.version"));
        info.put("cpusVisibleToJvm", rt.availableProcessors());
        info.put("maxHeapMb", rt.maxMemory() / (1024 * 1024));
        info.put("garbageCollectors", ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList());
        info.put("virtualThreads", virtualThreads);
        info.put("tomcatMaxThreads", virtualThreads ? "unlimited (virtual threads)" : ((AbstractProtocol<?>) protocolHandler()).getMaxThreads());
        info.put("tomcatBusyThreads", protocolHandler().getExecutor() instanceof ThreadPoolExecutor executor ? executor.getActiveCount() : -1);
        info.put("dbPool", Map.of("max", dataSource.getHikariConfigMXBean().getMaximumPoolSize(),
                "active", pool.getActiveConnections(), "idle", pool.getIdleConnections(), "waiting", pool.getThreadsAwaitingConnection(),
                "timeoutMs", dataSource.getHikariConfigMXBean().getConnectionTimeout()));
        return info;
    }
}
