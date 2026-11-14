package io.github.baokhang83.mnemo.tiers;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@SpringBootApplication
@RestController
public class MinimalApplication {
    public static void main(String[] args) { SpringApplication.run(MinimalApplication.class, args); }
    @GetMapping("/hello") String hello() { return "hello"; }
}
