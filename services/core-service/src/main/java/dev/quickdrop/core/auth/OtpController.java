package dev.quickdrop.core.auth;

import java.security.SecureRandom;
import java.util.Map;

import dev.quickdrop.core.notification.NotificationService;
import dev.quickdrop.core.ratelimit.RateLimitService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sends a login code by SMS. Every call costs real money at the vendor, which makes it the
 * favourite endpoint of bots (scenario 3.5) and the most urgent message type (scenario 6.8).
 */
@RestController
public class OtpController {

    private final SecureRandom random = new SecureRandom();
    private final NotificationService notifications;
    private final RateLimitService rateLimits;

    public OtpController(NotificationService notifications, RateLimitService rateLimits) {
        this.notifications = notifications;
        this.rateLimits = rateLimits;
    }

    public record OtpRequest(@NotBlank String phone) {
    }

    @PostMapping("/api/auth/otp")
    public ResponseEntity<Map<String, String>> sendOtp(@RequestBody OtpRequest request) {
        rateLimits.check(RateLimitService.Endpoint.OTP, request.phone());
        String code = "%06d".formatted(random.nextInt(1_000_000));
        notifications.sendOtp(request.phone(), code);
        return ResponseEntity.accepted().body(Map.of("status", "sent"));
    }
}
