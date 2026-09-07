package com.discoveryhub.ingestion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Clock;

@Configuration
public class IngestionConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Boot auto-configures a {@code KafkaTemplate<?, ?>}, which does not satisfy an injection
     * point typed {@code <String, Object>}. Serializers still come from application.yml; this only
     * narrows the generics.
     */
    @Bean
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, Object> stringKeyedKafkaTemplate(ProducerFactory<?, ?> producerFactory) {
        return new KafkaTemplate<>((ProducerFactory<String, Object>) producerFactory);
    }
}
