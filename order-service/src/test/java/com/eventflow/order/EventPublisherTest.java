package com.eventflow.order;

import org.slf4j.MDC;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class EventPublisherTest {

    @org.mockito.Mock
    private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    @Test
    void publishSendsToConfiguredTopicWithOrderKeyAndCorrelationIdHeader() {
        String topic = "orders-test";
        OrderCreatedEvent event = event();
        CompletableFuture<SendResult<String, OrderCreatedEvent>> sendResult = new CompletableFuture<>();
        when(kafkaTemplate.send(any(org.springframework.messaging.Message.class))).thenReturn(sendResult);
        SendResult<String, OrderCreatedEvent> result = mock(SendResult.class);
        RecordMetadata metadata = mock(RecordMetadata.class);
        when(result.getRecordMetadata()).thenReturn(metadata);
        when(metadata.partition()).thenReturn(2);
        when(metadata.offset()).thenReturn(17L);

        new EventPublisher(kafkaTemplate, topic).publish(event);
        sendResult.complete(result);

        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.any(org.springframework.messaging.Message.class));
        ArgumentCaptor<org.springframework.messaging.Message<?>> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.messaging.Message.class);
        verify(kafkaTemplate).send(messageCaptor.capture());

        org.springframework.messaging.Message<?> captured = messageCaptor.getValue();
        assertThat(captured.getPayload()).isSameAs(event);
        assertThat(captured.getHeaders().get("correlationId")).isNotNull();
        assertThat(captured.getHeaders().get(org.springframework.kafka.support.KafkaHeaders.KEY))
                .isEqualTo(event.orderId().toString());
    }

    @Test
    void publishHandlesAsynchronousSendFailure() {
        String topic = "orders-test";
        OrderCreatedEvent event = event();
        when(kafkaTemplate.send(any(org.springframework.messaging.Message.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        assertThatCode(() -> new EventPublisher(kafkaTemplate, topic).publish(event))
                .doesNotThrowAnyException();
    }

    @Test
    void publishPropagatesExistingCorrelationIdFromMdc() {
        String topic = "orders-test";
        OrderCreatedEvent event = event();
        CompletableFuture<SendResult<String, OrderCreatedEvent>> sendResult = new CompletableFuture<>();
        when(kafkaTemplate.send(any(org.springframework.messaging.Message.class))).thenReturn(sendResult);
        SendResult<String, OrderCreatedEvent> result = mock(SendResult.class);
        RecordMetadata metadata = mock(RecordMetadata.class);
        when(result.getRecordMetadata()).thenReturn(metadata);
        when(metadata.partition()).thenReturn(2);
        when(metadata.offset()).thenReturn(17L);

        MDC.put(CorrelationIdFilter.CORRELATION_ID_KEY, "existing-cid");
        try {
            new EventPublisher(kafkaTemplate, topic).publish(event);
            sendResult.complete(result);

            org.mockito.ArgumentCaptor<org.springframework.messaging.Message<?>> messageCaptor =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.messaging.Message.class);
            verify(kafkaTemplate).send(messageCaptor.capture());

            org.springframework.messaging.Message<?> captured = messageCaptor.getValue();
            assertThat(captured.getHeaders().get("correlationId")).isEqualTo("existing-cid");
        } finally {
            MDC.remove(CorrelationIdFilter.CORRELATION_ID_KEY);
        }
    }

    private static OrderCreatedEvent event() {
        return new OrderCreatedEvent(UUID.randomUUID(), "OrderCreated", 1, UUID.randomUUID(),
                        UUID.randomUUID(), new BigDecimal("12.50"), "2026-09-30T00:00:00Z");
    }
}