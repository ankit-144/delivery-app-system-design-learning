package dev.quickdrop.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param instanceName name shown in logs, the {@code X-Served-By} header and Grafana annotations
 * @param grafana      where toggle changes are posted as annotations; leave url empty to disable
 */
@ConfigurationProperties("quickdrop")
public record QuickdropProperties(String instanceName, Grafana grafana) {

    public QuickdropProperties {
        if (instanceName == null || instanceName.isBlank()) {
            instanceName = System.getenv().getOrDefault("HOSTNAME", "local");
        }
        if (grafana == null) {
            grafana = new Grafana(null, "admin", "admin");
        }
    }

    public record Grafana(String url, String user, String password) {

        public Grafana {
            user = user == null ? "admin" : user;
            password = password == null ? "admin" : password;
        }
    }
}
