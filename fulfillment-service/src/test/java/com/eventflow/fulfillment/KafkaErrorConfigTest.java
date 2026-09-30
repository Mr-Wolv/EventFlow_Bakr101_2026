package com.eventflow.fulfillment;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaErrorConfigTest {

    private final KafkaErrorConfig config = new KafkaErrorConfig();

    @Test
    void deadLetterTemplateUsesDedicatedJsonProducerConfiguration() {
        KafkaTemplate<String, Object> template = config.deadLetterTemplate("localhost:9092");
        DefaultKafkaProducerFactory<String, Object> producerFactory =
                (DefaultKafkaProducerFactory<String, Object>) template.getProducerFactory();
        Map<String, Object> producerProperties = producerFactory.getConfigurationProperties();

        assertThat(producerProperties).containsEntry(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
                .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)
                .containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
    }

    @Test
    void errorHandlerCanBeCreatedWithoutConnectingToKafka() {
        DefaultErrorHandler errorHandler = config.errorHandler(
                config.deadLetterTemplate("localhost:9092"));

        assertThat(errorHandler).isNotNull();
    }

    @Test
    void listenerFactoryUsesProvidedConsumerFactory() {
        ConsumerFactory<String, Object> consumerFactory = mock(ConsumerFactory.class);
        DefaultErrorHandler errorHandler = config.errorHandler(
                config.deadLetterTemplate("localhost:9092"));

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.kafkaListenerContainerFactory(consumerFactory, errorHandler);

        assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);
    }
}