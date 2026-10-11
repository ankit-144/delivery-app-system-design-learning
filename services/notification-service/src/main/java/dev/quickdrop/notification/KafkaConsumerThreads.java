package dev.quickdrop.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/**
 * Kafka consumers run on platform threads, even though this service enables virtual threads.
 *
 * <p>With {@code spring.threads.virtual.enabled=true}, Spring Boot also runs the listener containers on virtual
 * threads. On JDK 21 that deadlocked notification-2 (found in Phase 8, see docs/FAILURE_TRACKER.md): each
 * {@code KafkaConsumer.poll()} blocks in epoll inside a {@code synchronized} block, which pins its carrier thread.
 * Four pinned consumers took all four carriers; one of them was waiting for Logback's lock, held by a virtual thread
 * that could never get a carrier back. Tomcat's request threads (also virtual) never ran again: HTTP, health checks
 * and Prometheus scrapes all hung while the container stayed "Up".
 *
 * <p>A consumer is one long-lived thread blocked in poll() — the case where virtual threads buy nothing. HTTP
 * requests keep their virtual threads. (JDK 24+, JEP 491, removes the pinning on {@code synchronized}.)
 */
@Configuration
public class KafkaConsumerThreads {

    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> platformThreadConsumers() {
        return container -> container.getContainerProperties()
                .setListenerTaskExecutor(new SimpleAsyncTaskExecutor(container.getListenerId() + "-consumer-"));
    }
}
