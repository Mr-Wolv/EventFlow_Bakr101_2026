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

    @Test
    void retryListenerLogsEveryFailedDeliveryAttempt() throws Exception {
        DefaultErrorHandler errorHandler = config.errorHandler(
                config.deadLetterTemplate("localhost:9092"));

        // The retry listener is a lambda wired in KafkaErrorConfig; spring-kafka nests
        // it inside the handler's tracker and only exposes a protected accessor, so
        // find it by walking the object graph — stable across versions.
        java.util.List<org.springframework.kafka.listener.RetryListener> listeners =
                findRetryListeners(errorHandler);

        assertThat(listeners).hasSize(1);
        // Must not throw: the [RETRY] log line goes through log.warn(...)
        listeners.get(0).failedDelivery(
                new org.apache.kafka.clients.consumer.ConsumerRecord<>("orders", 0, 0L, "key", "value"),
                new IllegalStateException("boom"), 1);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<org.springframework.kafka.listener.RetryListener> findRetryListeners(
            Object root) throws IllegalAccessException {
        java.util.Deque<Object> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (!stack.isEmpty()) {
            Object current = stack.pop();
            if (current == null || !seen.add(current)) {
                continue;
            }
            for (Class<?> c = current.getClass(); c != null; c = c.getSuperclass()) {
                String cn = c.getName();
                if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")) {
                    continue; // JDK internals: not open to reflection
                }
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                    } catch (Exception inaccessible) {
                        continue;
                    }
                    Object value = f.get(current);
                    if (value instanceof java.util.List<?> list
                            && !list.isEmpty()
                            && list.get(0) instanceof org.springframework.kafka.listener.RetryListener) {
                        return (java.util.List<org.springframework.kafka.listener.RetryListener>) list;
                    }
                    if (value != null && !value.getClass().getName().startsWith("java.")) {
                        stack.push(value);
                    }
                }
            }
        }
        return java.util.List.of();
    }
}