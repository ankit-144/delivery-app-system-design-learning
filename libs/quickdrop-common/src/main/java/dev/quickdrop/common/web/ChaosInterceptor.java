package dev.quickdrop.common.web;

import java.util.concurrent.ThreadLocalRandom;

import dev.quickdrop.common.toggles.CommonToggles;
import dev.quickdrop.common.toggles.ToggleRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The {@code error-percent} chaos knob (Phase 8): fails a share of /api requests with HTTP 500. It's an
 * interceptor rather than a filter so the request has already been matched to its route: metrics and traces
 * keep the real {@code uri} ("/api/orders"), not "UNKNOWN", and alert rules that select by uri see the errors.
 */
public class ChaosInterceptor implements HandlerInterceptor {

    private final ToggleRegistry toggles;

    public ChaosInterceptor(ToggleRegistry toggles) {
        this.toggles = toggles;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long percent = toggles.getLong(CommonToggles.ERROR_PERCENT);
        if (percent <= 0 || !request.getRequestURI().startsWith("/api/")) {
            return true;
        }
        boolean checkoutOnly = toggles.is(CommonToggles.ERROR_SCOPE, "checkout");
        if (checkoutOnly && !(request.getMethod().equals("POST") && request.getRequestURI().equals("/api/orders"))) {
            return true;
        }
        if (ThreadLocalRandom.current().nextInt(100) < percent) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "injected failure (error-percent=" + percent + ")");
        }
        return true;
    }
}
