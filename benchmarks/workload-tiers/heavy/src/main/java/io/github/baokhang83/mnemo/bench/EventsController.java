package io.github.baokhang83.mnemo.bench;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Optional broker interactions; startup does not require a running broker. */
@RestController
class EventsController {
    private final KafkaTemplate<String, String> kafka;
    private final RabbitTemplate rabbit;

    EventsController(KafkaTemplate<String, String> kafka, RabbitTemplate rabbit) {
        this.kafka = kafka;
        this.rabbit = rabbit;
    }

    @PostMapping("/events/kafka")
    void kafka(@RequestBody String event) {
        kafka.send("bench-events", event);
    }

    @PostMapping("/events/rabbit")
    void rabbit(@RequestBody String event) {
        rabbit.convertAndSend("", "bench-events", event);
    }
}
