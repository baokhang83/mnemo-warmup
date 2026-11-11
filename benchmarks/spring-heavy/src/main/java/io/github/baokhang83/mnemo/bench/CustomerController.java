package io.github.baokhang83.mnemo.bench;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController {

    private final CustomerRepository customers;

    CustomerController(CustomerRepository customers) {
        this.customers = customers;
    }

    @GetMapping("/customers")
    @Cacheable("customers")
    public List<Customer> byPrefix(@RequestParam(defaultValue = "customer-1") String prefix) {
        return customers.findByNameStartingWith(prefix);
    }

    @PostMapping("/customers")
    public Customer create(@Valid @RequestBody Customer customer) {
        return customers.save(customer);
    }
}
