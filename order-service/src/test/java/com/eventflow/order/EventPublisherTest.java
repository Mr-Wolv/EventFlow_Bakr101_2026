package com.eventflow.order;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    @Mock
    private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    @Test
    void publishSendsToConfiguredTopicWithOrderKey() {
        String topic = "orders-test";
        OrderCreatedEvent event = event();
        CompletableFuture<SendResult<String, OrderCreatedEvent>> sendResult = new CompletableFuture<>();
        when(kafkaTemplate.send(topic, event.orderId().toString(), event)).thenReturn(sendResult);
        SendResult<String, OrderCreatedEvent> result = mock(SendResult.class);
        RecordMetadata metadata = mock(RecordMetadata.class);
        when(result.getRecordMetadata()).thenReturn(metadata);
        when(metadata.partition()).thenReturn(2);
        when(metadata.offset()).thenReturn(17L);

        new EventPublisher(kafkaTemplate, topic).publish(event);
        sendResult.complete(result);

        verify(kafkaTemplate).send(topic, event.orderId().toString(), event);
    }

    @Test
    void publishHandlesAsynchronousSendFailure() {
        String topic = "orders-test";
        OrderCreatedEvent event = event();
        when(kafkaTemplate.send(topic, event.orderId().toString(), event))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        assertThatCode(() -> new EventPublisher(kafkaTemplate, topic).publish(event))
                .doesNotThrowAnyException();
    }

    private static OrderCreatedEvent event() {
        return new OrderCreatedEvent(UUID.randomUUID(), "OrderCreated", UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("12.50"), "2026-09-30T00:00:00Z");
    }
}