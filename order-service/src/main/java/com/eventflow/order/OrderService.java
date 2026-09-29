package com.eventflow.order;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository repository;
    private final EventPublisher publisher;

    public OrderService(OrderRepository repository, EventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    /**
     * Creates and stores the order, then publishes an OrderCreated event to Kafka.
     * Publishing is asynchronous (acks=all, idempotent producer); delivery is confirmed
     * via the send callback logged by {@link EventPublisher}.
     */
    public Order createOrder(UUID customerId, BigDecimal amount) {
        Order order = new Order(customerId, amount);
        repository.save(order);
        publisher.publish(OrderCreatedEvent.forOrder(order));
        return order;
    }

    public Order getOrder(UUID id) {
        return repository.findById(id);
    }
}
