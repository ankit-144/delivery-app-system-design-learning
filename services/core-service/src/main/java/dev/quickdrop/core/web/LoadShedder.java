package dev.quickdrop.core.web;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Priority load shedding — scenario 8.4. Not every request is worth the same: during a flash sale, a failed
 * checkout is lost revenue, a failed "browse" is a customer pressing refresh.
 *
 * <p>With {@code load-shedding=priority}, sheddable requests (browsing, product pages, order history) may hold at
 * most {@code shed-browse-limit} slots per instance. Extra ones are refused at once with 503 and
 * {@code Retry-After}, before they take a database connection or CPU. Checkout, OTP and order tracking are never
 * shed. Rejecting fast is the point: a request that waits 10 s and then fails costs the same capacity as one that
 * succeeds, and the customer still sees an error.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LoadShedder extends OncePerRequestFilter {

    private final ToggleRegistry toggles;
    private final MeterRegistry meters;
    private final AtomicInteger sheddableInFlight = new AtomicInteger();

    public LoadShedder(ToggleRegistry toggles, MeterRegistry meters) {
        this.toggles = toggles;
        this.meters = meters;
        Gauge.builder("quickdrop.shedding.sheddable.in.flight", sheddableInFlight, AtomicInteger::get).register(meters);
    }

    /** Browsing and history can be retried by the customer; checkout, OTP and tracking can't wait. */
    static boolean sheddable(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!request.getMethod().equals("GET")) {
            return false;
        }
        return uri.startsWith("/api/stores/") || uri.startsWith("/api/products/")
                || (uri.startsWith("/api/customers/") && uri.endsWith("/orders"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!toggles.is(CoreToggles.LOAD_SHEDDING, "priority") || !sheddable(request)) {
            chain.doFilter(request, response);
            return;
        }
        int limit = (int) toggles.getLong(CoreToggles.SHED_BROWSE_LIMIT);
        if (sheddableInFlight.incrementAndGet() > limit) {
            sheddableInFlight.decrementAndGet();
            meters.counter("quickdrop.shedding.rejected").increment();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "2");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"busy, try again shortly\"}");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            sheddableInFlight.decrementAndGet();
        }
    }
}
