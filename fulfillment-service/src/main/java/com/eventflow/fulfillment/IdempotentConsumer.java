package com.eventflow.fulfillment;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency store.
 *
 * {@code Set.add()} is atomic and returns {@code false} when the element was already
 * present, which makes check-and-record a single atomic step (no TOCTOU race between
 * consumer threads).
 *
 * Limitation (deliberate scope decision): process-local. Lost on restart and not shared
 * across replicas. Marking an order FULFILLED is itself idempotent, so redelivery after a
 * restart remains safe — see docs/failure-scenarios.md. Production replacement: a durable
 * processed-events store (DB unique constraint, Redis SETNX) or a processed-events topic.
 */
@Component
public class IdempotentConsumer {

    private final Set<UUID> processed = ConcurrentHashMap.newKeySet();

    /** @return true if this eventId had not been seen before (and is now recorded). */
    public boolean tryProcess(UUID eventId) {
        return processed.add(eventId);
    }

    /**
     * Removes an eventId recorded by a delivery whose processing failed before any
     * state change, so the redelivery is treated as a first delivery.
     */
    public void forget(UUID eventId) {
        processed.remove(eventId);
    }

    public int processedCount() {
        return processed.size();
    }
}
