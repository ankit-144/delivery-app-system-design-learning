package dev.quickdrop.core.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private Long id;
    @Column(name = "city_id")
    private short cityId;
    private String name;
    // The phone number is read through CustomerDirectory (scenario 4.4 renames its column at runtime).

    protected Customer() {
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
}
