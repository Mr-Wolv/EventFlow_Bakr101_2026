package com.eventflow.fulfillment;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory order fulfillment state. PENDING until an OrderCreated event is processed. */
@Component
public class OrderFulfillmentStore {

    private final Map<UUID, OrderStatus> states = new ConcurrentHashMap<>();

    public void markFulfilled(UUID orderId) {
        states.put(orderId, OrderStatus.FULFILLED);
    }

    /** A request for an unknown order is PENDING — it may still be in flight from Kafka. */
    public OrderStatus getStatus(UUID orderId) {
        return states.getOrDefault(orderId, OrderStatus.PENDING);
    }

    public Map<UUID, OrderStatus> all() {
        return Map.copyOf(states);
    }

    /**
     * Count of fulfilled orders. {@code markFulfilled} is the only mutator and
     * stores only {@code FULFILLED}, so every entry is a fulfilled order — the
     * size is the count. (A stream filter here would carry a permanently
     * unreached branch: no code path ever stores PENDING.)
     */
    public long fulfilledCount() {
        return states.size();
    }
}
