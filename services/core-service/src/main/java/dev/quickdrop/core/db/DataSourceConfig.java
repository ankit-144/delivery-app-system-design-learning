package dev.quickdrop.core.db;

import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Two connection pools — the primary and the read replica — behind one routing DataSource (Phase 5).
 * Until Phase 5 the replica URL points at the primary, so nothing changes.
 *
 * <pre>
 *   JPA / JdbcClient ─▶ LazyConnectionDataSourceProxy ─▶ RoutingDataSource ─┬─▶ primary pool  (core-pool)
 *                                                                          └─▶ replica pool  (replica-pool)
 * </pre>
 * Flyway always uses the primary.
 */
@Configuration
public class DataSourceConfig {

    /**
     * Defining our own DataSource beans switches off Boot's, and with it the use of service connections
     * ({@code @ServiceConnection} in the integration test). So: if a {@link JdbcConnectionDetails} bean exists, it
     * wins over {@code spring.datasource.*}. (Missing this broke {@code make test} from Phase 5 until Phase 8.)
     */
    @Bean
    @FlywayDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource primaryPool(DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> serviceConnection) {
        JdbcConnectionDetails db = serviceConnection.getIfAvailable();
        if (db == null) {
            return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        }
        return DataSourceBuilder.create(properties.getClassLoader()).type(HikariDataSource.class)
                .url(db.getJdbcUrl()).username(db.getUsername()).password(db.getPassword()).driverClassName(db.getDriverClassName())
                .build();
    }

    @Bean
    HikariDataSource replicaPool(@Value("${quickdrop.replica.url}") String url, DataSourceProperties properties,
                                 ObjectProvider<JdbcConnectionDetails> serviceConnection) {
        JdbcConnectionDetails db = serviceConnection.getIfAvailable();
        HikariConfig config = new HikariConfig();
        config.setPoolName("replica-pool");
        config.setJdbcUrl(db != null ? db.getJdbcUrl() : url);   // tests have no replica: read from the primary
        config.setUsername(db != null ? db.getUsername() : properties.getUsername());
        config.setPassword(db != null ? db.getPassword() : properties.getPassword());
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(5000);
        config.setReadOnly(true);
        config.setInitializationFailTimeout(-1);   // start even if the replica isn't up yet
        return new HikariDataSource(config);
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("primaryPool") HikariDataSource primary, @Qualifier("replicaPool") HikariDataSource replica) {
        ReadRouting.RoutingDataSource routing = new ReadRouting.RoutingDataSource();
        routing.setTargetDataSources(Map.of("primary", primary, "replica", replica));
        routing.setDefaultTargetDataSource(primary);
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }
}
