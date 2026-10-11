package dev.quickdrop.core.config;

import java.time.Duration;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.redis.GuardedRedisTemplate;
import dev.quickdrop.core.redis.RedisUnavailable;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    @Bean
    CircuitBreakerRegistry circuitBreakers(MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean
    CircuitBreaker redisCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("redis", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(RedisUnavailable::is)   // only "Redis is unreachable" counts, not e.g. a bad key
                .build());
    }

    /** Replaces Spring Boot's StringRedisTemplate, so every Redis user goes through the breaker. */
    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connections, CircuitBreaker redisCircuitBreaker,
                                            ToggleRegistry toggles) {
        return new GuardedRedisTemplate(connections, redisCircuitBreaker, toggles);
    }

    /** Pub/sub listener container. It connects only once a listener is added (two-tier product cache). */
    @Bean
    RedisMessageListenerContainer redisListeners(RedisConnectionFactory connections) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        return container;
    }
}
