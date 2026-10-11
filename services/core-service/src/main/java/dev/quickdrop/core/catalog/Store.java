package dev.quickdrop.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A dark store: the small warehouse an order is packed in. */
@Entity
@Table(name = "stores")
public class Store {

    @Id
    private Long id;
    @Column(name = "city_id")
    private short cityId;
    private String name;
    private double lat;
    private double lon;

    protected Store() {
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

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }
}
