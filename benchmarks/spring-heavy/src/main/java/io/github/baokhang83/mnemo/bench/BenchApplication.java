package io.github.baokhang83.mnemo.bench;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableCaching
public class BenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BenchApplication.class, args);
    }

    /** A little startup work, as a real service has: seed a table through JPA. */
    @Bean
    CommandLineRunner seed(CustomerRepository customers) {
        return args -> {
            for (int i = 0; i < 200; i++) {
                customers.save(new Customer("customer-" + i, "customer" + i + "@example.com"));
            }
        };
    }
}
