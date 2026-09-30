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

    /**
     * Throws when a fault is armed for this event; returns normally otherwise.
     *
     * Deliberate shape: {@code throw fault(...)} rather than calling a helper that
     * throws. JaCoCo places probes at control-flow merge points, so a call whose
     * execution always ends in an exception never reaches its follow-up probe and
     * is reported as uncovered even though it runs. Throwing the returned
     * exception directly makes every executed line probeable.
     */
    public void maybeFail(UUID eventId) {
        FaultType current = fault;
        if (current == FaultType.ALWAYS) {
            throw fault(eventId, "always-fail fault armed");
        } else if (current == FaultType.ONCE_PER_EVENT && failedOnce.add(eventId)) {
            throw fault(eventId, "once-per-event fault armed");
        }
        /* FaultType.NONE: no-op */
    }

    /** Logs and counts the injected fault, returning the exception to throw. */
    private IllegalStateException fault(UUID eventId, String reason) {
        injectedFailures++;
        log.error("[FAULT-INJECTED] failing processing of event {} ({})", eventId, reason);
        return new IllegalStateException("Injected failure for event " + eventId + " (" + reason + ")");
    }
}
