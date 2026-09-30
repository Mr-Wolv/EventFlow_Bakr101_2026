package com.eventflow.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    @DisplayName("a new order gets a generated id, PRESERVED customer/amount and a creation timestamp")
    void newOrderGetsGeneratedIdentity() {
        UUID customerId = UUID.randomUUID();
        Order order = new Order(customerId, new BigDecimal("19.99"));

        assertThat(order.getId()).isNotNull();
        assertThat(order.getCustomerId()).isEqualTo(customerId);
        assertThat(order.getAmount()).isEqualByComparingTo("19.99");
        assertThat(order.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("two orders never share an id")
    void orderIdsAreUnique() {
        Order a = new Order(UUID.randomUUID(), BigDecimal.ONE);
        Order b = new Order(UUID.randomUUID(), BigDecimal.TEN);
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    @Test
    @DisplayName("toString renders id, customer, amount and timestamp for log lines")
    void toStringRendersAllFields() {
        Order order = new Order(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), new BigDecimal("125.50"));

        assertThat(order.toString())
                .startsWith("Order[id=")
                .contains("customerId=550e8400-e29b-41d4-a716-446655440000")
                .contains("amount=125.50")
                .contains("createdAt=");
    }
}
