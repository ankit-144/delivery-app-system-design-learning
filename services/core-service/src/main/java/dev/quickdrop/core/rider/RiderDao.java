package dev.quickdrop.core.rider;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RiderDao {

    private final JdbcClient jdbc;

    public RiderDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> status(long riderId) {
        return jdbc.sql("SELECT status FROM riders WHERE id = ?").param(riderId).query(String.class).optional();
    }

    /** Unconditional write: marks the rider busy no matter what happened since we looked. */
    public void markBusy(long riderId, long orderId) {
        jdbc.sql("UPDATE riders SET status = 'BUSY', current_order_id = ?, version = version + 1 WHERE id = ?")
                .params(orderId, riderId)
                .update();
    }

    /** Conditional write: only succeeds if the rider is still available. Exactly one caller can win. */
    public boolean markBusyIfAvailable(long riderId, long orderId) {
        return jdbc.sql("""
                        UPDATE riders SET status = 'BUSY', current_order_id = ?, version = version + 1
                        WHERE id = ? AND status = 'AVAILABLE'
                        """)
                .params(orderId, riderId)
                .update() == 1;
    }

    public record PingResult(short cityId, boolean available) {
    }

    public Optional<PingResult> updateLocation(long riderId, double lat, double lon) {
        return jdbc.sql("UPDATE riders SET lat = ?, lon = ?, location_updated_at = now() WHERE id = ? RETURNING city_id, status")
                .params(lat, lon, riderId)
                .query((rs, n) -> new PingResult(rs.getShort(1), "AVAILABLE".equals(rs.getString(2))))
                .optional();
    }

    /** Going online only works for an idle rider; going offline only when not on a delivery. */
    public boolean setAvailability(long riderId, boolean online) {
        String sql = online
                ? "UPDATE riders SET status = 'AVAILABLE' WHERE id = ? AND status = 'OFFLINE'"
                : "UPDATE riders SET status = 'OFFLINE' WHERE id = ? AND status = 'AVAILABLE'";
        return jdbc.sql(sql).param(riderId).update() == 1;
    }

    /** Keeps the candidates (in their order) whose rider is AVAILABLE right now. */
    public List<RiderCandidate> keepAvailable(List<RiderCandidate> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        java.util.Set<Long> available = new java.util.HashSet<>(jdbc.sql("SELECT id FROM riders WHERE id = ANY(?) AND status = 'AVAILABLE'")
                .param(candidates.stream().map(RiderCandidate::riderId).toArray(Long[]::new))
                .query(Long.class)
                .list());
        return candidates.stream().filter(c -> available.contains(c.riderId())).toList();
    }

    public record Position(long riderId, short cityId, double lat, double lon) {
    }

    public List<Position> availablePositions() {
        return jdbc.sql("SELECT id, city_id, lat, lon FROM riders WHERE status = 'AVAILABLE'")
                .query((rs, n) -> new Position(rs.getLong(1), rs.getShort(2), rs.getDouble(3), rs.getDouble(4)))
                .list();
    }

    public List<RiderCandidate> nearestAvailable(short cityId, double lat, double lon, double radiusKm, int limit) {
        double dLat = radiusKm / 111.0;
        double dLon = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)));
        return jdbc.sql("""
                        SELECT id, lat, lon FROM riders
                        WHERE city_id = :city AND status = 'AVAILABLE'
                          AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon
                        ORDER BY (lat - :lat) ^ 2 + ((lon - :lon) * cos(radians(:lat))) ^ 2
                        LIMIT :limit
                        """)
                .param("city", cityId)
                .param("minLat", lat - dLat).param("maxLat", lat + dLat)
                .param("minLon", lon - dLon).param("maxLon", lon + dLon)
                .param("lat", lat).param("lon", lon)
                .param("limit", limit)
                .query((rs, n) -> new RiderCandidate(rs.getLong("id"), Geo.haversineKm(lat, lon, rs.getDouble("lat"), rs.getDouble("lon"))))
                .list();
    }
}
