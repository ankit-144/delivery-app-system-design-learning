package dev.quickdrop.core.rider;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only mapping used by the scan-everything locator. All rider writes go through {@link RiderDao}. */
@Entity
@Table(name = "riders")
public class Rider {

    @Id
    private Long id;
    @Column(name = "city_id")
    private short cityId;
    private String name;
    private String phone;
    private String status;
    private double lat;
    private double lon;
    @Column(name = "current_order_id")
    private Long currentOrderId;
    @Column(name = "location_updated_at")
    private Instant locationUpdatedAt;

    protected Rider() {
    }

    public Long getId() {
        return id;
    }

    public short getCityId() {
        return cityId;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public Long getCurrentOrderId() {
        return currentOrderId;
    }
}
