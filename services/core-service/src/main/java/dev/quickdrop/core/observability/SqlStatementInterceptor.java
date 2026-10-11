package dev.quickdrop.core.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class SqlStatementInterceptor implements HandlerInterceptor {

    private final MeterRegistry meters;

    public SqlStatementInterceptor(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        SqlStatementCounter.reset();
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        DistributionSummary.builder("quickdrop.jpa.statements")
                .description("JPA statements executed per HTTP request")
                .tag("uri", pattern == null ? "UNKNOWN" : pattern.toString())
                .serviceLevelObjectives(1, 2, 5, 10, 20, 50, 100)
                .register(meters)
                .record(SqlStatementCounter.current());
    }
}
