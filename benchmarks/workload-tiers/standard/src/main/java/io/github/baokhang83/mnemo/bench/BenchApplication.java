package io.github.baokhang83.mnemo.bench;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
@SpringBootApplication
@EnableCaching
public class BenchApplication {
    public static void main(String[] args) { SpringApplication.run(BenchApplication.class, args); }
}
