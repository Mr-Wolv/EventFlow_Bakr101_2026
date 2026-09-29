package com.eventflow.fulfillment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentConsumerTest {

    private IdempotentConsumer idempotentConsumer;
    private OrderFulfillmentStore store;
    private FailureInjector failureInjector;
    private FulfillmentConsumer consumer;

    @BeforeEach
    void setUp() {
        idempotentConsumer = mock(IdempotentConsumer.class);
        store = mock(OrderFulfillmentStore.class);
        failureInjector = new FailureInjector();
        consumer = new FulfillmentConsumer(idempotentConsumer, store, failureInjector);
    }

    private OrderCreatedEvent event() {
        return new OrderCreatedEvent(
                UUID.randomUUID(), "OrderCreated", UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("42.00"), "2026-09-29T10:00:00Z");
    }

    @Test
    @DisplayName("first delivery marks the order fulfilled")
    void firstDeliveryFulfillsOrder() {
        OrderCreatedEvent e = event();
        when(idempotentConsumer.tryProcess(e.eventId())).thenReturn(true);

        consumer.listen(e, "orders");

        verify(store).markFulfilled(e.orderId());
    }

    @Test
    @DisplayName("duplicate delivery is skipped and never reaches the store")
    void duplicateDeliveryIsSkipped() {
        OrderCreatedEvent e = event();
        when(idempotentConsumer.tryProcess(e.eventId())).thenReturn(false);

        consumer.listen(e, "orders");

        verify(store, never()).markFulfilled(any());
    }

    @Test
    @DisplayName("injected failure propagates so the error handler retries, and the idempotency record is rolled back")
    void injectedFailureRollsBackIdempotencyAndRethrows() {
        OrderCreatedEvent e = event();
        when(idempotentConsumer.tryProcess(e.eventId())).thenReturn(true);
        failureInjector.setFault(FailureInjector.FaultType.ALWAYS);

        assertThatThrownBy(() -> consumer.listen(e, "orders"))
                .isInstanceOf(IllegalStateException.class);

        verify(store, never()).markFulfilled(any());
        verify(idempotentConsumer).forget(e.eventId());
    }

    @Test
    @DisplayName("ONCE_PER_EVENT fault fails the first delivery attempt and succeeds on redelivery")
    void oncePerEventFaultRecoversOnRedelivery() {
        OrderCreatedEvent e = event();
        when(idempotentConsumer.tryProcess(e.eventId())).thenReturn(true, true);

        failureInjector.setFault(FailureInjector.FaultType.ONCE_PER_EVENT);

        // first delivery — fails
        assertThatThrownBy(() -> consumer.listen(e, "orders"))
                .isInstanceOf(IllegalStateException.class);
        verify(store, never()).markFulfilled(any());

        // redelivery — succeeds
        consumer.listen(e, "orders");
        verify(store).markFulfilled(e.orderId());
        assertThat(failureInjector.injectedFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("with no fault armed, processing completes normally")
    void noFaultProcessesNormally() {
        OrderCreatedEvent e = event();
        when(idempotentConsumer.tryProcess(e.eventId())).thenReturn(true);

        consumer.listen(e, "orders");

        verify(store).markFulfilled(e.orderId());
        assertThat(failureInjector.injectedFailures()).isZero();
    }
}
