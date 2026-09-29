package com.eventflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The OrderCreated event published to the {@code orders} Kafka topic.
 *
 * {@code occurredAt} is an ISO-8601 string so the payload round-trips through
 * any JSON stack without requiring the Jackson java-time module on consumers.
 */
public record OrderCreatedEvent(
        UUID eventId,
        String eventType,
        UUID orderId,
        UUID customerId,
        BigDecimal amount,
        String occurredAt) {

    public static OrderCreatedEvent forOrder(Order order) {
        return new OrderCreatedEvent(
                UUID.randomUUID(),
                "OrderCreated",
                order.getId(),
                order.getCustomerId(),
                order.getAmount(),
                Instant.now().toString());
    }
}
