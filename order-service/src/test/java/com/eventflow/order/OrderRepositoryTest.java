package com.eventflow.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRepositoryTest {

    @Test
    void savesAndFindsOrderById() {
        OrderRepository repository = new OrderRepository();
        Order order = new Order(UUID.randomUUID(), new BigDecimal("9.99"));

        assertThat(repository.save(order)).isSameAs(order);
        assertThat(repository.findById(order.getId())).isSameAs(order);
        assertThat(repository.findById(UUID.randomUUID())).isNull();
    }
}