package com.discoveryhub.ingestion;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.DeserializationFeature;

import java.time.Clock;

@Configuration
public class IngestionConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Lets a primitive field be omitted rather than refusing the whole message.
     *
     * <p>{@code Attachment.sizeBytes} is a {@code long}, and Jackson binds a record's missing
     * component as null, which cannot be coerced into a primitive. Without this, an uploaded file
     * that leaves out {@code sizeBytes} is rejected with a Jackson internals message about
     * {@code FAIL_ON_NULL_FOR_PRIMITIVES} — for a field the uploader was never expected to supply,
     * since {@code AttachmentHydrator} computes it from the content.
     *
     * <p>Safe here because zero is not a meaningful attachment size: the hydrator treats it as
     * "unknown" and computes the real value, and {@code AttachmentIntegrity} still rejects any
     * size that disagrees with the bytes. An absent field therefore ends up correct rather than
     * silently wrong.
     */
    @Bean
    JsonMapperBuilderCustomizer allowOmittedPrimitives() {
        return builder -> builder.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
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
