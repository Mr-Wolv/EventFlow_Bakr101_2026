package com.eventflow.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes OrderCreated events to the orders topic.
 *
 * Records are keyed by orderId so all events for the same order land on the same
 * partition (per-order ordering). The send callback logs delivery confirmation or
 * failure — publishing is fire-and-forget from the client's perspective, which is
 * exactly the eventual-consistency tradeoff documented in docs/distributed-systems.md.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    private final String topic;

    public EventPublisher(KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate,
                          @Value("${eventflow.topics.orders:orders}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(OrderCreatedEvent event) {
        kafkaTemplate.send(topic, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[PUBLISH-FAILED] event {} for order {}: {}",
                                event.eventId(), event.orderId(), ex.getMessage());
                    } else {
                        log.info("[PUBLISHED] event {} for order {} -> {}-p{}@{}",
                                event.eventId(), event.orderId(), topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
