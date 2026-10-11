package dev.quickdrop.common.observability;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.quickdrop.common.QuickdropProperties;
import dev.quickdrop.common.toggles.ToggleChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Posts every toggle change to Grafana as an annotation, so each dashboard shows a vertical line
 * at the moment you flipped a strategy. Best effort: failures are logged and ignored.
 */
public class GrafanaAnnotator {

    private static final Logger log = LoggerFactory.getLogger(GrafanaAnnotator.class);

    private final RestClient client;

    public GrafanaAnnotator(QuickdropProperties.Grafana grafana) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        this.client = RestClient.builder()
                .baseUrl(grafana.url())
                .requestFactory(requestFactory)
                .defaultHeaders(h -> h.setBasicAuth(grafana.user(), grafana.password()))
                .build();
    }

    @EventListener
    public void onChange(ToggleChangedEvent event) {
        String text = "%s: %s %s → %s".formatted(event.instance(), event.toggle(), event.oldValue(), event.newValue());
        Map<String, Object> body = Map.of(
                "time", System.currentTimeMillis(),
                "tags", List.of("toggle", event.toggle(), event.instance()),
                "text", text);
        Thread.ofVirtual().name("grafana-annotation").start(() -> {
            try {
                client.post().uri("/api/annotations").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
            } catch (Exception e) {
                log.debug("Could not post Grafana annotation: {}", e.getMessage());
            }
        });
    }
}
