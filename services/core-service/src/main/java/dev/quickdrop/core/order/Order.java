package dev.quickdrop.core.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id")
    private long customerId;
    @Column(name = "store_id")
    private long storeId;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    @Column(name = "total_paise")
    private int totalPaise;
    @Column(name = "rider_id")
    private Long riderId;
    @Column(name = "client_request_id")
    private String clientRequestId;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "assigned_at")
    private Instant assignedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(long customerId, long storeId, String clientRequestId) {
        this.customerId = customerId;
        this.storeId = storeId;
        this.clientRequestId = clientRequestId;
        this.status = OrderStatus.PENDING_RIDER;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        totalPaise += item.getPricePaise() * item.getQuantity();
    }

    public void assignRider(long riderId) {
        this.riderId = riderId;
        this.status = OrderStatus.RIDER_ASSIGNED;
        this.assignedAt = Instant.now();
        this.updatedAt = this.assignedAt;
    }

    public Long getId() {
        return id;
    }

    public long getCustomerId() {
        return customerId;
    }

    public long getStoreId() {
        return storeId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public int getTotalPaise() {
        return totalPaise;
    }

    public Long getRiderId() {
        return riderId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
