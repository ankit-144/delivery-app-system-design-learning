package dev.quickdrop.core.inventory;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL for the inventory table. Concurrency bugs live in the gap between statements, so the
 * statements are written out explicitly instead of hiding behind an ORM.
 */
@Repository
public class InventoryDao {

    private final JdbcClient jdbc;

    public InventoryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Versioned(int quantity, long version) {
    }

    /** Plain read. Another transaction can change the row right after we look at it. */
    public Optional<Integer> quantity(long storeId, long productId) {
        return jdbc.sql("SELECT quantity FROM inventory WHERE store_id = ? AND product_id = ?")
                .params(storeId, productId)
                .query(Integer.class)
                .optional();
    }

    /** Read and lock the row until our transaction ends. Others wanting it wait in line. */
    public Optional<Integer> quantityForUpdate(long storeId, long productId) {
        return jdbc.sql("SELECT quantity FROM inventory WHERE store_id = ? AND product_id = ? FOR UPDATE")
                .params(storeId, productId)
                .query(Integer.class)
                .optional();
    }

    public Optional<Versioned> versioned(long storeId, long productId) {
        return jdbc.sql("SELECT quantity, version FROM inventory WHERE store_id = ? AND product_id = ?")
                .params(storeId, productId)
                .query((rs, n) -> new Versioned(rs.getInt(1), rs.getLong(2)))
                .optional();
    }

    /** Writes an absolute value computed from an earlier read: whatever happened in between is overwritten. */
    public void setQuantity(long storeId, long productId, int quantity) {
        jdbc.sql("UPDATE inventory SET quantity = ?, updated_at = now() WHERE store_id = ? AND product_id = ?")
                .params(quantity, storeId, productId)
                .update();
    }

    /**
     * Writes only if our fencing token is newer than the last one that wrote this row (scenario 3.2).
     * A holder whose lock expired has an older token than the holder that replaced it, so its write is refused.
     */
    public boolean setQuantityFenced(long storeId, long productId, int quantity, long fence) {
        return jdbc.sql("""
                        UPDATE inventory SET quantity = ?, fence_token = ?, updated_at = now()
                        WHERE store_id = ? AND product_id = ? AND fence_token < ?
                        """)
                .params(quantity, fence, storeId, productId, fence)
                .update() == 1;
    }

    /** Writes only if nobody changed the row since we read {@code expectedVersion}. */
    public boolean compareAndSet(long storeId, long productId, int quantity, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE inventory SET quantity = ?, version = version + 1, updated_at = now()
                        WHERE store_id = ? AND product_id = ? AND version = ?
                        """)
                .params(quantity, storeId, productId, expectedVersion)
                .update() == 1;
    }

    /** Check and decrement in one statement: the database does the read-modify-write atomically. */
    public boolean decrementIfAvailable(long storeId, long productId, int quantity) {
        return jdbc.sql("""
                        UPDATE inventory SET quantity = quantity - ?, version = version + 1, updated_at = now()
                        WHERE store_id = ? AND product_id = ? AND quantity >= ?
                        """)
                .params(quantity, storeId, productId, quantity)
                .update() == 1;
    }
}
