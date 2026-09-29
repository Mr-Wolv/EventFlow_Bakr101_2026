package com.eventflow.order;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OrderRepository {
    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();

    public Order save(Order order) {
        orders.put(order.getId(), order);
        return order;
    }

    public Order findById(UUID id) {
        return orders.get(id);
    }
}
