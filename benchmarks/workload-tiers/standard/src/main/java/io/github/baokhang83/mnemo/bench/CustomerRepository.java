package io.github.baokhang83.mnemo.bench;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    List<Customer> findByNameStartingWith(String prefix);
}
