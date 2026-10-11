package dev.quickdrop.core.order;

import dev.quickdrop.core.catalog.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;
    private int quantity;
    @Column(name = "price_paise")
    private int pricePaise;

    protected OrderItem() {
    }

    /** {@code pricePaise} is passed in (from the product cache) rather than read from {@code product}. */
    public OrderItem(Order order, Product product, int quantity, int pricePaise) {
        this.order = order;
        this.product = product;
        this.quantity = quantity;
        this.pricePaise = pricePaise;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getPricePaise() {
        return pricePaise;
    }
}
