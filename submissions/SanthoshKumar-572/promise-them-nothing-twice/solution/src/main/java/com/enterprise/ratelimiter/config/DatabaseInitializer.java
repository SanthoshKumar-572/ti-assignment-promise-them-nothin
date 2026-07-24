package com.enterprise.ratelimiter.config;

import com.enterprise.ratelimiter.model.Customer;
import com.enterprise.ratelimiter.repository.CustomerRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DatabaseInitializer implements CommandLineRunner {

    private final CustomerRepository customerRepository;

    public DatabaseInitializer(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        if (customerRepository.count() == 0) {
            customerRepository.saveAll(List.of(
                    new Customer("starter-company", "STARTER", 60, null, null),
                    new Customer("growth-company", "GROWTH", 300, null, null),
                    new Customer("northwind", "ENTERPRISE", 300, 1200, "11:00-14:00"),
                    new Customer("test-user", "STARTER", 2, null, null)
            ));
        }
    }
}
