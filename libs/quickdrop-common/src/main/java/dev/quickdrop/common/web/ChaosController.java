package dev.quickdrop.common.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stops this instance on purpose (scenario 3.7). Docker's restart policy brings it back.
 * <pre>
 * curl -X POST 'localhost:8083/admin/chaos/exit?mode=crash'      # like kill -9 or a kernel OOM kill
 * curl -X POST 'localhost:8083/admin/chaos/exit?mode=graceful'   # like a normal deploy (SIGTERM)
 * </pre>
 * Graceful: Spring stops accepting new requests, lets in-flight ones finish (server.shutdown=graceful),
 * then exits. Crash: the process vanishes mid-request.
 * <pre>
 * curl -X POST 'localhost:8091/admin/chaos/cpu?seconds=90&amp;threads=1'   # a busy batch job (scenario 8.2)
 * </pre>
 */
@RestController
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    private final ApplicationContext context;

    public ChaosController(ApplicationContext context) {
        this.context = context;
    }

    @PostMapping("/admin/chaos/exit")
    public Map<String, String> exit(@RequestParam(defaultValue = "graceful") String mode) {
        Thread.ofPlatform().name("chaos-exit").start(() -> {
            try {
                Thread.sleep(200);   // let this response go out first
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (mode.equals("crash")) {
                log.error("CHAOS: crashing now (Runtime.halt)");
                Runtime.getRuntime().halt(137);
            }
            log.warn("CHAOS: graceful shutdown requested");
            System.exit(SpringApplication.exit(context, () -> 0));
        });
        return Map.of("mode", mode, "status", "exiting");
    }

    /** Spins {@code threads} platform threads for {@code seconds}: a CPU-hungry batch job, nothing more. */
    @PostMapping("/admin/chaos/cpu")
    public Map<String, Object> burnCpu(@RequestParam(defaultValue = "60") int seconds, @RequestParam(defaultValue = "1") int threads) {
        long until = System.nanoTime() + seconds * 1_000_000_000L;
        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().name("chaos-cpu-" + i).daemon().start(() -> {
                long x = 0;
                while (System.nanoTime() < until) {
                    x += Long.numberOfTrailingZeros(x * 31 + 7);   // busy work the JIT can't remove
                }
                if (x == 42) {
                    log.trace("unlikely");
                }
            });
        }
        log.warn("CHAOS: burning CPU with {} thread(s) for {} s", threads, seconds);
        return Map.of("threads", threads, "seconds", seconds);
    }
}
