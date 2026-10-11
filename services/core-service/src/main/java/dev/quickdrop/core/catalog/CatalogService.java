package dev.quickdrop.core.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import dev.quickdrop.core.web.NotFoundException;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CatalogService {

    private final ProductRepository products;
    private final StoreRepository stores;
    private final JdbcClient jdbc;
    private final ProductCache cache;
    private final EntityManager entityManager;
    private final TransactionTemplate tx;

    public CatalogService(ProductRepository products, StoreRepository stores, JdbcClient jdbc, ProductCache cache,
                          EntityManager entityManager, TransactionTemplate tx) {
        this.products = products;
        this.stores = stores;
        this.jdbc = jdbc;
        this.cache = cache;
        this.entityManager = entityManager;
        this.tx = tx;
    }

    public Store store(long storeId) {
        return stores.findById(storeId).orElseThrow(() -> new NotFoundException("store", storeId));
    }

    public ProductSnapshot product(long productId) {
        return products(List.of(productId)).get(productId);
    }

    /** Through the product cache (scenario 3.3): with a stale cache, orders are charged a stale price. */
    public Map<Long, ProductSnapshot> products(Collection<Long> ids) {
        Map<Long, ProductSnapshot> found = cache.getAll(ids);
        for (Long id : ids) {
            if (!found.containsKey(id)) {
                throw new NotFoundException("product", id);
            }
        }
        return found;
    }

    /** A reference for foreign keys (order_items.product_id) without loading the product. */
    public Product productRef(long productId) {
        return entityManager.getReference(Product.class, productId);
    }

    public record StoreProduct(long productId, String name, String category, int pricePaise, int quantity) {
    }

    public List<StoreProduct> storeProducts(long storeId, String category, int limit) {
        return jdbc.sql("""
                        SELECT p.id, p.name, p.category, p.price_paise, i.quantity
                        FROM inventory i JOIN products p ON p.id = i.product_id
                        WHERE i.store_id = :store AND (CAST(:category AS TEXT) IS NULL OR p.category = :category)
                        ORDER BY p.id
                        LIMIT :limit
                        """)
                .param("store", storeId)
                .param("category", category)
                .param("limit", limit)
                .query((rs, n) -> new StoreProduct(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)))
                .list();
    }

    /** Updates the price, then — only after the commit — evicts it from the cache. */
    public ProductSnapshot updatePrice(long productId, int pricePaise) {
        ProductSnapshot updated = tx.execute(status -> {
            Product product = products.findById(productId).orElseThrow(() -> new NotFoundException("product", productId));
            product.setPricePaise(pricePaise);
            return ProductSnapshot.of(product);
        });
        cache.evict(productId);
        return updated;
    }
}
