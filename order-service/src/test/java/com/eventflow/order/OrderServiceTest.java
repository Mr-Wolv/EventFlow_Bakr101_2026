package com.eventflow.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository repository;

    @Mock
    private EventPublisher publisher;

    @Test
    @DisplayName("createOrder stores the order and publishes an OrderCreated event for it")
    void createOrderStoresAndPublishes() {
        OrderService service = new OrderService(repository, publisher);
        UUID customerId = UUID.randomUUID();

        Order order = service.createOrder(customerId, new BigDecimal("125.50"));

        verify(repository).save(order);
        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(publisher).publish(captor.capture());

        OrderCreatedEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("OrderCreated");
        assertThat(event.orderId()).isEqualTo(order.getId());
        assertThat(event.customerId()).isEqualTo(customerId);
        assertThat(event.amount()).isEqualByComparingTo("125.50");
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotBlank();
    }

    @Test
    @DisplayName("two orders produce two distinct eventIds (events are individually identifiable)")
    void eventIdsAreUniquePerOrder() {
        OrderService service = new OrderService(repository, publisher);

        Order first = service.createOrder(UUID.randomUUID(), BigDecimal.ONE);
        Order second = service.createOrder(UUID.randomUUID(), BigDecimal.TEN);

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    @DisplayName("getOrder returns what the repository holds and null when absent")
    void getOrderDelegatesToRepository() {
        OrderService service = new OrderService(repository, publisher);
        UUID id = UUID.randomUUID();
        Order order = new Order(UUID.randomUUID(), BigDecimal.ONE);
        when(repository.findById(id)).thenReturn(order);

        assertThat(service.getOrder(id)).isEqualTo(order);
        assertThat(service.getOrder(UUID.randomUUID())).isNull();
    }

    @Test
    @DisplayName("if persisting fails, no event is published (no half-created orders)")
    void failedSaveMeansNoPublish() {
        OrderService service = new OrderService(repository, publisher);

        when(repository.save(any(Order.class))).thenThrow(new IllegalStateException("persist failed"));

        Throwable thrown = catchThrowable(() -> service.createOrder(UUID.randomUUID(), new BigDecimal("5")));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        verify(publisher, never()).publish(any());
    }
}
