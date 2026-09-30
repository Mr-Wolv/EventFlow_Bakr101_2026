package com.eventflow.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    @Test
    void createOrderReturnsCreatedOrderAndLocation() {
        OrderController controller = new OrderController(orderService);
        UUID customerId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("125.50");
        Order order = new Order(customerId, amount);
        when(orderService.createOrder(customerId, amount)).thenReturn(order);

        ResponseEntity<Order> response = controller.createOrder(new OrderRequest(customerId, amount));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create("/orders/" + order.getId()));
        assertThat(response.getBody()).isSameAs(order);
        verify(orderService).createOrder(customerId, amount);
    }

    @Test
    void getOrderReturnsFoundOrder() {
        OrderController controller = new OrderController(orderService);
        Order order = new Order(UUID.randomUUID(), BigDecimal.ONE);
        when(orderService.getOrder(order.getId())).thenReturn(order);

        ResponseEntity<Order> response = controller.getOrder(order.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(order);
    }

    @Test
    void getOrderReturnsNotFoundWhenAbsent() {
        OrderController controller = new OrderController(orderService);
        UUID orderId = UUID.randomUUID();
        when(orderService.getOrder(orderId)).thenReturn(null);

        ResponseEntity<Order> response = controller.getOrder(orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }
}