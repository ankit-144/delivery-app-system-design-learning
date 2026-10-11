package dev.quickdrop.core.admin;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Compares two systems that should agree — scenario 6.6. Orders in our database since the scenario started
 * vs order confirmations the SMS vendor actually received (their message ids are "order-{id}").
 * <pre>
 * GET /admin/reconcile/sms
 * </pre>
 * Real companies run jobs like this nightly between payments, orders, ledgers and partners.
 */
@RestController
public class ReconcileController {

    private final JdbcClient jdbc;
    private final RestClient vendor;

    public ReconcileController(JdbcClient jdbc, RestClient.Builder builder, @Value("${quickdrop.vendor.base-url}") String vendorUrl) {
        this.jdbc = jdbc;
        this.vendor = builder.clone().baseUrl(vendorUrl).build();
    }

    @GetMapping("/admin/reconcile/sms")
    public Map<String, Object> reconcileSms() {
        Set<Long> orders = new HashSet<>(jdbc.sql("""
                SELECT id FROM orders WHERE created_at >= (SELECT started_at FROM scenario_marker)
                """).query(Long.class).list());
        @SuppressWarnings("unchecked")
        List<String> received = vendor.get().uri("/v1/stats/ids?prefix=order-").retrieve().body(List.class);
        Set<Long> smsOrders = new HashSet<>();
        for (String id : received) {
            smsOrders.add(Long.parseLong(id.substring("order-".length())));
        }
        long withoutSms = orders.stream().filter(id -> !smsOrders.contains(id)).count();
        Set<Long> ghosts = new HashSet<>(smsOrders);
        ghosts.removeAll(orders);
        // An SMS for an order that doesn't exist — unless it's an older order from before this scenario started.
        long ghostCount = ghosts.isEmpty() ? 0 : jdbc.sql("SELECT count(*) FROM orders WHERE id = ANY(?)")
                .param(ghosts.toArray(Long[]::new)).query(Long.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ordersPlaced", orders.size());
        out.put("orderSmsReceivedByVendor", smsOrders.size());
        out.put("ordersWithoutSms", withoutSms);
        out.put("smsForOrdersThatDoNotExist", ghosts.size() - ghostCount);
        return out;
    }
}
