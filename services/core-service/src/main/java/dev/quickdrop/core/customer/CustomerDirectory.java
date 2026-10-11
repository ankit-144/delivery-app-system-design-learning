package dev.quickdrop.core.customer;

import java.util.Optional;

import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.web.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads and writes customers' phone numbers — scenario 4.4, renaming {@code customers.phone} to
 * {@code phone_number} while the app is running. {@code customer-phone-schema} stands in for the app
 * version deployed on this instance:
 *
 * <pre>
 *  v1    read phone          write phone                   (before)
 *  v1.5  read phone          write phone + phone_number    (expand: the new column exists, keep both in sync)
 *  v2    read phone_number   write phone + phone_number    (switch reads; old instances still work)
 *  v3    read phone_number   write phone_number            (after; then the old column can be dropped)
 * </pre>
 */
@Component
public class CustomerDirectory {

    public record CustomerContact(long id, String phone) {
    }

    private final JdbcClient jdbc;
    private final ToggleRegistry toggles;

    public CustomerDirectory(JdbcClient jdbc, ToggleRegistry toggles) {
        this.jdbc = jdbc;
        this.toggles = toggles;
    }

    public CustomerContact find(long customerId) {
        String column = switch (version()) {
            case "v2", "v3" -> "phone_number";
            default -> "phone";
        };
        Optional<String> phone = jdbc.sql("SELECT " + column + " FROM customers WHERE id = ?")
                .param(customerId)
                .query(String.class)
                .optional();
        return new CustomerContact(customerId, phone.orElseThrow(() -> new NotFoundException("customer", customerId)));
    }

    public void updatePhone(long customerId, String phone) {
        String sql = switch (version()) {
            case "v1" -> "UPDATE customers SET phone = :phone WHERE id = :id";
            case "v3" -> "UPDATE customers SET phone_number = :phone WHERE id = :id";
            default -> "UPDATE customers SET phone = :phone, phone_number = :phone WHERE id = :id";
        };
        if (jdbc.sql(sql).param("phone", phone).param("id", customerId).update() == 0) {
            throw new NotFoundException("customer", customerId);
        }
    }

    private String version() {
        return toggles.get(CoreToggles.CUSTOMER_PHONE_SCHEMA);
    }
}
