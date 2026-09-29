package com.eventflow.fulfillment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-side copy of the OrderCreated event.
 *
 * The wire format (JSON field names) must match the order-service event. The Java
 * package/class here is intentionally the fulfillment service's own — consumers own
 * their view of shared events; there is no shared library between services.
 */
public record OrderCreatedEvent(
        UUID eventId,
        String eventType,
        UUID orderId,
        UUID customerId,
        BigDecimal amount,
        String occurredAt) {
}
