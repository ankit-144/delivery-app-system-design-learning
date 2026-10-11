package dev.quickdrop.core.customer;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController {

    private final CustomerDirectory customers;

    public CustomerController(CustomerDirectory customers) {
        this.customers = customers;
    }

    @GetMapping("/api/customers/{id}")
    public CustomerDirectory.CustomerContact get(@PathVariable long id) {
        return customers.find(id);
    }

    @PutMapping("/api/customers/{id}/phone")
    public CustomerDirectory.CustomerContact updatePhone(@PathVariable long id, @RequestBody Map<String, String> body) {
        customers.updatePhone(id, body.get("phone"));
        return customers.find(id);
    }
}
