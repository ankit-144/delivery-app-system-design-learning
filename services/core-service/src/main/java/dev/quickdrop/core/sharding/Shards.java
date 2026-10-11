package dev.quickdrop.core.sharding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The shard databases of the sharding lab (Phase 5), one small connection pool each, configured with
 * SHARD_URLS. Empty means sharding is off (before Phase 5).
 */
@Component
public class Shards implements AutoCloseable {

    public record Shard(int index, String url, HikariDataSource pool, JdbcClient jdbc) {
    }

    private final List<Shard> shards = new ArrayList<>();

    public Shards(@Value("${quickdrop.shards.urls:}") String urls, @Value("${spring.datasource.username}") String user,
                  @Value("${spring.datasource.password}") String password) {
        List<String> list = Arrays.stream(urls.split(",")).map(String::trim).filter(u -> !u.isEmpty()).toList();
        for (int i = 0; i < list.size(); i++) {
            HikariConfig config = new HikariConfig();
            config.setPoolName("shard-" + i);
            config.setJdbcUrl(list.get(i));
            config.setUsername(user);
            config.setPassword(password);
            config.setMaximumPoolSize(5);
            config.setConnectionTimeout(5000);
            config.setInitializationFailTimeout(-1);
            HikariDataSource pool = new HikariDataSource(config);
            shards.add(new Shard(i, list.get(i), pool, JdbcClient.create(pool)));
        }
    }

    public boolean enabled() {
        return !shards.isEmpty();
    }

    public int count() {
        return shards.size();
    }

    public Shard get(int index) {
        return shards.get(index);
    }

    public List<Shard> all() {
        return shards;
    }

    public void requireEnabled() {
        if (!enabled()) {
            throw new IllegalStateException("Sharding is off: start Phase 5 with 'make up PHASE=5' (SHARD_URLS is empty)");
        }
    }

    @Override
    public void close() {
        shards.forEach(s -> s.pool().close());
    }
}
