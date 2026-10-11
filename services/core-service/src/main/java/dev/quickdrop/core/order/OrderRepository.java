package dev.quickdrop.core.order;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Without an index on customer_id this is a sequential scan of the whole table (scenario 2.4). */
    List<Order> findByCustomerIdOrderByCreatedAtDesc(long customerId, Limit limit);

    @Query("select o.id from Order o where o.customerId = :customerId order by o.createdAt desc")
    List<Long> findIdsByCustomer(@Param("customerId") long customerId, Limit limit);

    @Query("select distinct o from Order o left join fetch o.items i left join fetch i.product where o.id in :ids order by o.createdAt desc")
    List<Order> findWithItems(@Param("ids") Collection<Long> ids);
}
