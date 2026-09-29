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
    public void listen(OrderCreatedEvent event, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        UUID eventId = event.eventId();

        if (!idempotentConsumer.tryProcess(eventId)) {
            log.info("[DUPLICATE] event {} already processed — skipping", eventId);
            return;
        }

        try {
            failureInjector.maybeFail(eventId);
        } catch (RuntimeException fault) {
            // Un-record the eventId so the redelivery is processed as a first delivery.
            idempotentConsumer.forget(eventId);
            throw fault;
        }

        log.info("[PROCESSING] order {} amount {} (from {})", event.orderId(), event.amount(), topic);
        store.markFulfilled(event.orderId());
        log.info("[FULFILLED] order {}", event.orderId());
    }
}
