package com.eventflow.fulfillment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes OrderCreated events.
 *
 * Processing contract (see docs/audit-trail.md for the full walkthrough):
 *   1. Idempotency check — duplicates are skipped and the offset is committed normally.
 *   2. Failure injection point — a configured fault throws BEFORE any state changes.
 *   3. State change — markFulfilled is a safe-to-repeat state transition.
 *
 * On an uncaught exception the DefaultErrorHandler retries with exponential backoff
 * (1s, 2s, 4s) and then publishes the record to the orders.DLT topic; the offset is
 * only committed after the record is handled, so no event is silently lost.
 */
@Component
public class FulfillmentConsumer {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentConsumer.class);

    private final IdempotentConsumer idempotentConsumer;
    private final OrderFulfillmentStore store;
    private final FailureInjector failureInjector;

    public FulfillmentConsumer(IdempotentConsumer idempotentConsumer,
                               OrderFulfillmentStore store,
                               FailureInjector failureInjector) {
        this.idempotentConsumer = idempotentConsumer;
        this.store = store;
        this.failureInjector = failureInjector;
    }

    @KafkaListener(topics = "${eventflow.topics.orders:orders}", groupId = "fulfillment")
    public void listen(OrderCreatedEvent event,
                       @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                       @Header(KafkaHeaders.RECEIVED_PARTITION) Integer partition,
                       @Header(KafkaHeaders.OFFSET) Long offset) {
        validate(event);

        UUID eventId = event.eventId();

        if (!idempotentConsumer.tryProcess(eventId)) {
            log.info("[DUPLICATE] event {} for order {} — skipping ({} {}@{})",
                    eventId, event.orderId(), topic, partition, offset);
            return;
        }

        try {
            failureInjector.maybeFail(eventId);
        } catch (RuntimeException fault) {
            // Un-record the eventId so the redelivery is processed as a first delivery.
            idempotentConsumer.forget(eventId);
            throw fault;
        }

        log.info("[PROCESSING] event {} order {} amount {} (from {} {}@{})",
                eventId, event.orderId(), event.amount(), topic, partition, offset);
        store.markFulfilled(event.orderId());
        log.info("[FULFILLED] event {} order {} ({} {}@{})",
                eventId, event.orderId(), topic, partition, offset);
    }

    private void validate(OrderCreatedEvent event) {
        if (event == null) throw new IllegalArgumentException("event must not be null");
        if (event.eventId() == null) throw new IllegalArgumentException("eventId is required");
        if (!"OrderCreated".equals(event.eventType())) throw new IllegalArgumentException("unknown eventType: " + event.eventType());
        if (event.eventVersion() != 1) throw new IllegalArgumentException("unsupported eventVersion: " + event.eventVersion());
        if (event.orderId() == null) throw new IllegalArgumentException("orderId is required");
        if (event.customerId() == null) throw new IllegalArgumentException("customerId is required");
        if (event.amount() == null || event.amount().signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        try { java.time.Instant.parse(event.occurredAt()); }
        catch (Exception e) { throw new IllegalArgumentException("occurredAt must be ISO-8601", e); }
    }
}
