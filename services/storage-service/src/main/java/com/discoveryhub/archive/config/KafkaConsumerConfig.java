package com.discoveryhub.archive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer resiliency (checkpoint 6): ingestion during a downstream outage must not lose
 * messages; they must be processed once the service recovers.
 *
 * <p>Spring Kafka's default {@link DefaultErrorHandler} retries a handful of times and then skips
 * the record — which would silently drop a message whose processing failed because the database
 * (or S3, or any other downstream) was temporarily down. That violates the resiliency NFR, so we
 * override the error handler with an unbounded {@link FixedBackOff}: a transient failure is
 * retried every {@code retry-interval-ms} (default 3s) until it succeeds. The consumer stays parked
 * on the failing record — it does not commit its offset and does not skip ahead — so the broker's
 * retained messages are processed in order once Storage is healthy again.
 *
 * <p>This handler is only reached for genuine transient faults. Two classes of "expected" records
 * are handled inside the listener and never reach it:
 * <ul>
 *   <li><b>Malformed JSON</b> — {@code MessageIngestedListener} / {@code HoldsEventListener} catch
 *       the {@code JacksonException} and skip the record (one bad message cannot wedge the
 *       consumer; the schema is the contract, not the broker).</li>
 *   <li><b>Duplicates</b> — {@code DataIntegrityViolationException} from the
 *       {@code UNIQUE(external_id)} constraint is caught and recorded as a dedupe.</li>
 * </ul>
 * Everything else (DB connection lost, S3 hiccup, constraint surprise, …) is a real fault we want
 * to keep retrying — so it propagates here and the unbounded backoff takes over.
 */
@Configuration
public class KafkaConsumerConfig {

    /** Retry interval between attempts while a downstream is unavailable. */
    private static final long RETRY_INTERVAL_MS = 3_000L;

    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        // FixedBackOff(interval, maxAttempts) — maxAttempts = Long.MAX_VALUE means retry forever.
        // The consumer blocks on the failing record until it succeeds; the offset is not committed,
        // so on restart (or recovery) Kafka redelivers the pending records in order.
        return new DefaultErrorHandler(new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
