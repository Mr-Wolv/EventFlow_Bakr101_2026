package com.eventflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Order aggregate. Immutable; state lives in the in-memory {@link OrderRepository}
 * (deliberate scope decision — no database in this project).
 */
public class Order {

    private final UUID id;
    private final UUID customerId;
    private final BigDecimal amount;
    private final Instant createdAt;

    public Order(UUID customerId, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.amount = amount;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "Order[id=%s, customerId=%s, amount=%s, createdAt=%s]".formatted(id, customerId, amount, createdAt);
    }
}
