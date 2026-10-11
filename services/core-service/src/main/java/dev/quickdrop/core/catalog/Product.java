package dev.quickdrop.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private Long id;
    private String sku;
    private String name;
    private String category;
    @Column(name = "price_paise")
    private int pricePaise;

    protected Product() {
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public int getPricePaise() {
        return pricePaise;
    }

    public void setPricePaise(int pricePaise) {
        this.pricePaise = pricePaise;
    }
}
