package dev.quickdrop.common.web;

import java.io.IOException;

import dev.quickdrop.common.toggles.CommonToggles;
import dev.quickdrop.common.toggles.ToggleRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds {@code X-Served-By: <instance>} to every response (so you can see the load balancer at work)
 * and applies the {@code request-delay-ms} chaos knob to {@code /api/**} requests.
 */
public class InstanceFilter extends OncePerRequestFilter {

    private final ToggleRegistry toggles;

    public InstanceFilter(ToggleRegistry toggles) {
        this.toggles = toggles;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Served-By", toggles.instance());
        long delay = toggles.getLong(CommonToggles.REQUEST_DELAY_MS);
        if (delay > 0 && request.getRequestURI().startsWith("/api/")) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        chain.doFilter(request, response);
    }
}
