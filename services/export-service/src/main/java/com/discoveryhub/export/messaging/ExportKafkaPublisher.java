package com.discoveryhub.export.messaging;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The two things P5 publishes: a request onto {@code export.jobs} so the async worker path picks
 * up the job (FR-6.2), and {@code audit.events} for its own chain-of-custody entries. Same String
 * serde approach as storage-service's {@code ArchiveKafkaPublisher}, for the same Jackson 2 vs. 3
 * reason. Both block on delivery — see {@link #publishAudit} for why the audit publish does too,
 * unlike some of this codebase's other publishers.
 */
@Component
public class ExportKafkaPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public ExportKafkaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publishJobRequested(String jobId) {
        // Blocking on the ack here, unlike most fire-and-forget publishers in this codebase: a
        // job that is never actually queued would sit at QUEUED forever with nobody working on
        // it, which is worse than the caller waiting an extra few milliseconds for the send to land.
        kafka.send(Topics.EXPORT_JOBS, jobId, toJson(new ExportJobRequested(jobId))).join();
    }

    /**
     * Also blocking, unlike {@code publishAudit} on some of this codebase's other Kafka
     * publishers: this is the chain-of-custody record for exports and downloads (FR-7), and
     * {@code export.requested}/{@code export.completed}/{@code export.downloaded} are exactly the
     * events that prove an export happened. A fire-and-forget send here means a broker hiccup at
     * the wrong moment leaves an export with zero audit trail and no caller ever finding out.
     */
    public void publishAudit(AuditEvent event) {
        kafka.send(Topics.AUDIT_EVENTS, event.eventId(), toJson(event)).join();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialise Kafka payload: " + value, ex);
        }
    }
}
