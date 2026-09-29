package com.eventflow.fulfillment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotentConsumerTest {

    private IdempotentConsumer idempotentConsumer;

    @BeforeEach
    void setUp() {
        idempotentConsumer = new IdempotentConsumer();
    }

    @Test
    @DisplayName("first delivery of an eventId is accepted, second is a duplicate")
    void firstDeliveryAcceptedSecondRejected() {
        UUID eventId = UUID.randomUUID();

        assertThat(idempotentConsumer.tryProcess(eventId)).isTrue();
        assertThat(idempotentConsumer.tryProcess(eventId)).isFalse();
        assertThat(idempotentConsumer.processedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("different eventIds are independent")
    void distinctEventIdsAreIndependent() {
        assertThat(idempotentConsumer.tryProcess(UUID.randomUUID())).isTrue();
        assertThat(idempotentConsumer.tryProcess(UUID.randomUUID())).isTrue();
        assertThat(idempotentConsumer.processedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("forget re-arms an eventId so a redelivery is treated as first delivery")
    void forgetReArmsEventId() {
        UUID eventId = UUID.randomUUID();

        assertThat(idempotentConsumer.tryProcess(eventId)).isTrue();
        idempotentConsumer.forget(eventId);
        assertThat(idempotentConsumer.tryProcess(eventId)).isTrue();
        assertThat(idempotentConsumer.processedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent duplicate deliveries result in exactly one acceptance")
    void concurrentDuplicatesProcessedOnce() throws InterruptedException {
        UUID eventId = UUID.randomUUID();
        int threads = 16;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    start.await();
                    if (idempotentConsumer.tryProcess(eventId)) {
                        accepted.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(idempotentConsumer.processedCount()).isEqualTo(1);
    }
}
