package com.eventflow.fulfillment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deliberate failure injection for demonstrating Kafka retry + dead-letter behavior.
 *
 * Fault types (set via the admin endpoint on POST /__admin/failure):
 *   NONE          — normal processing
 *   ALWAYS        — processing throws on every delivery; the record is retried, then
 *                   sent to the DLT after retries are exhausted
 *   ONCE_PER_EVENT — processing throws the first time an eventId is seen and succeeds
 *                   on redelivery; demonstrates that the retry path recovers
 *
 * The fault fires after the event is received but before the fulfillment state is
 * updated — a partial failure inside the processing step.
 */
@Component
public class FailureInjector {

    private static final Logger log = LoggerFactory.getLogger(FailureInjector.class);

    public enum FaultType { NONE, ALWAYS, ONCE_PER_EVENT }

    private volatile FaultType fault = FaultType.NONE;
    private final Set<UUID> failedOnce = ConcurrentHashMap.newKeySet();
    private volatile int injectedFailures = 0;

    public void setFault(FaultType faultType) {
        this.fault = faultType;
        log.warn("[FAULT] failure injection set to {}", faultType);
    }

    public FaultType currentFault() {
        return fault;
    }

    public int injectedFailures() {
        return injectedFailures;
    }

    /** Throws when a fault is armed for this event; returns normally otherwise. */
    public void maybeFail(UUID eventId) {
        FaultType current = fault;
        switch (current) {
            case ALWAYS -> inject(eventId, "always-fail fault armed");
            case ONCE_PER_EVENT -> {
                if (failedOnce.add(eventId)) {
                    inject(eventId, "once-per-event fault armed");
                }
            }
            case NONE -> { /* no-op */ }
        }
    }

    private void inject(UUID eventId, String reason) {
        injectedFailures++;
        log.error("[FAULT-INJECTED] failing processing of event {} ({})", eventId, reason);
        throw new IllegalStateException("Injected failure for event " + eventId + " (" + reason + ")");
    }
}
