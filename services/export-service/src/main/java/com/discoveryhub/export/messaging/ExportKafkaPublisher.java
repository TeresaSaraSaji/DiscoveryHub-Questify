package com.discoveryhub.export.messaging;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The two things P5 publishes: a request onto {@code export.jobs} so the async worker path picks
 * up the job (FR-6.2), and {@code audit.events} for its own chain-of-custody entries. Same String
 * serde approach as storage-service's {@code ArchiveKafkaPublisher}, for the same Jackson 2 vs. 3
 * reason.
 */
@Component
public class ExportKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExportKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public ExportKafkaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publishJobRequested(String jobId) {
        // Blocking on the ack here, unlike audit: a job that is never actually queued would sit
        // at QUEUED forever with nobody working on it, which is worse than the caller waiting an
        // extra few milliseconds for the send to land.
        kafka.send(Topics.EXPORT_JOBS, jobId, toJson(new ExportJobRequested(jobId))).join();
    }

    public void publishAudit(AuditEvent event) {
        send(Topics.AUDIT_EVENTS, event.eventId(), toJson(event));
    }

    private void send(String topic, String key, String value) {
        kafka.send(topic, key, value).whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("failed to publish to {} key={}: {}", topic, key, ex.toString());
            }
        });
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialise Kafka payload: " + value, ex);
        }
    }
}
