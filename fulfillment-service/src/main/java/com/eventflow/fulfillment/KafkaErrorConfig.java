package com.eventflow.fulfillment;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Retry and dead-letter configuration for the Kafka listener.
 *
 * - Retry: 1 initial delivery plus up to 3 retries, with 1s, 2s, and 4s backoff (~7s total).
 * - Exhausted records are published to "&lt;topic&gt;.DLT" with the original record and
 *   failure headers added by DeadLetterPublishingRecoverer; the failed partition's
 *   offset is then committed so processing continues.
 * - Deserialization failures are not retried (they can never succeed) and go straight
 *   to the DLT via the default exception classification.
 *
 * Found during end-to-end testing: the recoverer must get its own producer template.
 * A template built from the consumer factory serializes with the consumer's value
 * serializer (String), which fails on event records — the DLT publish then throws and
 * the record is re-seeked instead of dead-lettered. The dedicated template below uses
 * a JSON value serializer, matching the order service's wire format.
 */
@Configuration
public class KafkaErrorConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorConfig.class);

    @Bean
    public KafkaTemplate<String, Object> deadLetterTemplate(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        producerProps.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> deadLetterTemplate) {
        // Same partition in the DLT (topic-partition naming) to preserve ordering.
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(deadLetterTemplate);

        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxElapsedTime(10_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setRetryListeners((record, ex, attempt) ->
                log.warn("[RETRY] attempt {} for order-topic record {} failed: {}",
                        attempt, record.key(), ex.getMessage()));
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
