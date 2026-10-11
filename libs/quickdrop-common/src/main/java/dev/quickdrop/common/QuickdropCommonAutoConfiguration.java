package dev.quickdrop.common;

import java.util.List;

import dev.quickdrop.common.observability.GrafanaAnnotator;
import dev.quickdrop.common.observability.ToggleMetrics;
import dev.quickdrop.common.observability.TracingNoiseFilter;
import dev.quickdrop.common.toggles.CommonToggles;
import dev.quickdrop.common.toggles.ToggleCatalog;
import dev.quickdrop.common.toggles.ToggleController;
import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.common.web.ChaosController;
import dev.quickdrop.common.web.ChaosInterceptor;
import dev.quickdrop.common.web.InstanceFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@EnableConfigurationProperties(QuickdropProperties.class)
@Import({ToggleController.class, ChaosController.class})
public class QuickdropCommonAutoConfiguration {

    @Bean
    CommonToggles commonToggles() {
        return new CommonToggles();
    }

    @Bean
    ToggleRegistry toggleRegistry(List<ToggleCatalog> catalogs, Environment env, ApplicationEventPublisher events,
                                  QuickdropProperties properties) {
        return new ToggleRegistry(catalogs, env, events, properties.instanceName());
    }

    @Bean
    ToggleMetrics toggleMetrics(ToggleRegistry toggles, MeterRegistry meters) {
        return new ToggleMetrics(toggles, meters);
    }

    @Bean
    @ConditionalOnExpression("'${quickdrop.grafana.url:}' != ''")
    GrafanaAnnotator grafanaAnnotator(QuickdropProperties properties) {
        return new GrafanaAnnotator(properties.grafana());
    }

    @Bean
    InstanceFilter instanceFilter(ToggleRegistry toggles) {
        return new InstanceFilter(toggles);
    }

    @Bean
    WebMvcConfigurer chaosInterceptor(ToggleRegistry toggles) {
        ChaosInterceptor interceptor = new ChaosInterceptor(toggles);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    @Bean
    TracingNoiseFilter tracingNoiseFilter(@Value("${management.tracing.enabled:false}") boolean tracingEnabled) {
        return new TracingNoiseFilter(tracingEnabled);
    }
}
