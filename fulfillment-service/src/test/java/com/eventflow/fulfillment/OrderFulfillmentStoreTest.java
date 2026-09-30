package com.eventflow.fulfillment;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderFulfillmentStoreTest {

    @Test
    void unknownOrdersArePendingAndFulfilledOrdersAreCounted() {
        OrderFulfillmentStore store = new OrderFulfillmentStore();
        UUID firstOrderId = UUID.randomUUID();
        UUID secondOrderId = UUID.randomUUID();

        assertThat(store.getStatus(firstOrderId)).isEqualTo(OrderStatus.PENDING);

        store.markFulfilled(firstOrderId);
        store.markFulfilled(secondOrderId);

        assertThat(store.getStatus(firstOrderId)).isEqualTo(OrderStatus.FULFILLED);
        assertThat(store.all()).containsEntry(firstOrderId, OrderStatus.FULFILLED)
                .containsEntry(secondOrderId, OrderStatus.FULFILLED);
        assertThat(store.fulfilledCount()).isEqualTo(2);
    }
}