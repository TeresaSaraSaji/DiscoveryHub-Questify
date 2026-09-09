package com.discoveryhub.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka listener container for the search service's two consumers ({@code messages.archived} and
 * {@code holds.events}).
 *
 * <p>The error handler does not retry: a malformed record is already caught and skipped in the
 * listeners, and a transient store failure is not something a retry in the same consumer will fix.
 * With {@link FixedBackOff}{@code (0, 0)} the default recoverer logs the record and the consumer
 * seeks past it, so one bad message cannot wedge the group — the same "log and skip" philosophy the
 * listeners use at the JSON-parsing boundary.
 */
@Configuration
public class KafkaConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> searchKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(2);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0, 0)));
        return factory;
    }
}
