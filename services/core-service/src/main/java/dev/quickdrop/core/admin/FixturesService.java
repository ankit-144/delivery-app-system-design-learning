package dev.quickdrop.core.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.quickdrop.core.catalog.CatalogService;
import dev.quickdrop.core.catalog.Store;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Puts the world into a known state before a scenario run. Called from every k6 script's setup(). */
@Service
public class FixturesService {

    private final JdbcClient jdbc;
    private final CatalogService catalog;

    public FixturesService(JdbcClient jdbc, CatalogService catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    /**
     * @param stock        exact stock levels to set; these rows are then watched by the invariant checker
     * @param restockAll   give every product in every store this many units (0 = leave alone)
     * @param riders       only this many riders (nearest to the store) are available in the store's city; null = everyone
     * @param riderFleet   make sure a city has at least this many riders (extra ones are created and kept)
     */
    public record ResetRequest(List<Stock> stock, int restockAll, Riders riders, RiderFleet riderFleet) {
        public record Stock(long storeId, long productId, int quantity) {
        }

        public record Riders(long storeId, int available) {
        }

        public record RiderFleet(int cityId, int size) {
        }
    }

    /** Adds riders scattered around the city's stores until the city has {@code size} of them. */
    private int growFleet(ResetRequest.RiderFleet fleet) {
        return jdbc.sql("""
                        INSERT INTO riders (id, city_id, name, phone, status, lat, lon)
                        SELECT base.max_id + g, :city, 'Rider ' || (base.max_id + g), '+9181' || lpad((base.max_id + g)::text, 8, '0'),
                               'AVAILABLE', s.lat + (random() - 0.5) * 0.054, s.lon + (random() - 0.5) * 0.054
                        FROM (SELECT max(id) AS max_id FROM riders) base,
                             generate_series(1, greatest(0, :size - (SELECT count(*) FROM riders WHERE city_id = :city))) g
                        JOIN LATERAL (SELECT lat, lon FROM stores WHERE city_id = :city ORDER BY random() + g * 0 LIMIT 1) s ON true
                        """)
                .param("city", fleet.cityId())
                .param("size", fleet.size())
                .update();
    }

    @Transactional
    public Map<String, Object> reset(ResetRequest request) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (request.riderFleet() != null) {
            summary.put("ridersCreated", growFleet(request.riderFleet()));
        }
        summary.put("ordersClosed", jdbc.sql("""
                UPDATE orders SET status = CASE WHEN status = 'RIDER_ASSIGNED' THEN 'DELIVERED' ELSE 'CANCELLED' END,
                                  delivered_at = CASE WHEN status = 'RIDER_ASSIGNED' THEN now() END,
                                  updated_at = now()
                WHERE status IN ('RIDER_ASSIGNED', 'PENDING_RIDER')
                """).update());

        jdbc.sql("UPDATE riders SET status = 'AVAILABLE', current_order_id = NULL WHERE status <> 'AVAILABLE' OR current_order_id IS NOT NULL").update();
        if (request.riders() != null) {
            Store store = catalog.store(request.riders().storeId());
            jdbc.sql("UPDATE riders SET status = 'OFFLINE' WHERE city_id = ?").param(store.getCityId()).update();
            int available = jdbc.sql("""
                            UPDATE riders SET status = 'AVAILABLE'
                            WHERE id IN (SELECT id FROM riders WHERE city_id = :city
                                         ORDER BY (lat - :lat) ^ 2 + (lon - :lon) ^ 2 LIMIT :n)
                            """)
                    .param("city", store.getCityId()).param("lat", store.getLat()).param("lon", store.getLon())
                    .param("n", request.riders().available())
                    .update();
            summary.put("ridersAvailableNearStore", available);
        }

        if (request.restockAll() > 0) {
            summary.put("inventoryRowsRestocked", jdbc.sql("""
                    UPDATE inventory SET quantity = :q, total_stocked = :q, stock_reset_at = now(), version = 0, updated_at = now()
                    """).param("q", request.restockAll()).update());
        }
        if (request.stock() != null) {
            for (ResetRequest.Stock stock : request.stock()) {
                jdbc.sql("""
                                UPDATE inventory SET quantity = :q, total_stocked = :q, stock_reset_at = now(), version = 0, updated_at = now()
                                WHERE store_id = :store AND product_id = :product
                                """)
                        .param("q", stock.quantity()).param("store", stock.storeId()).param("product", stock.productId())
                        .update();
            }
            summary.put("stockRowsSet", request.stock().size());
        }

        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("ANALYZE riders").update();
        jdbc.sql("UPDATE scenario_marker SET started_at = now()").update();
        return summary;
    }
}
