package io.github.baokhang83.mnemo.example;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "hello", "java", System.getProperty("java.version"));
    }

    /** Used as the readiness probe; Spring Boot's actuator is not needed for an example. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
