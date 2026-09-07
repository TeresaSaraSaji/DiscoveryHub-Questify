package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.ingestion.api.IngestResponse;
import com.discoveryhub.ingestion.api.IngestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final String ACTOR = "system:ingestion";

    private final DedupeStore dedupe;
    private final EventPublisher publisher;
    private final Clock clock;

    public IngestService(DedupeStore dedupe, EventPublisher publisher, Clock clock) {
        this.dedupe = dedupe;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * A batch is not atomic. Each message succeeds, dedupes or fails on its own, so one bad or
     * repeated entry cannot reject the other 199.
     */
    public IngestResponse ingest(List<Message> batch) {
        List<IngestResult> results = new ArrayList<>(batch.size());
        for (Message incoming : batch) {
            results.add(ingestOne(incoming));
        }
        return IngestResponse.of(results);
    }

    private IngestResult ingestOne(Message incoming) {
        String rejection = validate(incoming);
        if (rejection != null) {
            String externalId = incoming == null ? null : incoming.externalId();
            audit("message.rejected", externalId, AuditEvent.Outcome.REJECTED,
                    Map.of("reason", rejection));
            return IngestResult.rejected(externalId, rejection);
        }

        Message message = incoming.withDerivedIds();
        String externalId = message.externalId();
        String messageId = message.messageId();

        if (!dedupe.claim(externalId)) {
            // A source system re-sending is normal, not a client fault. Dropping the copy is an
            // audit event and a 2xx outcome (FR-1.6).
            audit("message.deduped", messageId, AuditEvent.Outcome.DUPLICATE,
                    Map.of("externalId", externalId));
            return IngestResult.duplicate(externalId, messageId);
        }

        try {
            publisher.publishIngested(message);
        } catch (RuntimeException e) {
            // Release the claim so a retry is not mistaken for a duplicate and silently dropped.
            dedupe.release(externalId);
            log.error("publish failed for externalId={}", externalId, e);
            audit("message.ingest_failed", messageId, AuditEvent.Outcome.FAILED,
                    Map.of("externalId", externalId, "error", String.valueOf(e.getMessage())));
            return IngestResult.failed(externalId, messageId, "publish failed");
        }

        audit("message.ingested", messageId, AuditEvent.Outcome.SUCCESS,
                Map.of("externalId", externalId,
                        "custodianId", message.custodianId(),
                        "type", message.type().name(),
                        "attachmentCount", message.attachments().size()));
        return IngestResult.accepted(externalId, messageId);
    }

    private String validate(Message m) {
        if (m == null) {
            return "message must not be null";
        }
        if (isBlank(m.externalId())) {
            return "externalId is required";
        }
        if (m.type() == null) {
            return "type is required";
        }
        if (isBlank(m.source())) {
            return "source is required";
        }
        if (isBlank(m.custodianId())) {
            return "custodianId is required";
        }
        if (isBlank(m.from())) {
            return "from is required";
        }
        if (isBlank(m.body())) {
            return "body is required";
        }
        if (m.sentAt() == null) {
            return "sentAt is required";
        }
        if (isBlank(m.threadId())) {
            return "threadId is required";
        }
        if (m.type() == MessageType.EMAIL && isBlank(m.subject())) {
            return "subject is required on EMAIL";
        }
        return null;
    }

    private void audit(String action, String targetId, AuditEvent.Outcome outcome, Map<String, Object> details) {
        try {
            publisher.publishAudit(new AuditEvent(
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    ACTOR,
                    action,
                    "message",
                    targetId,
                    outcome,
                    details));
        } catch (RuntimeException e) {
            // Never let the audit path fail an ingestion that already succeeded; the event is
            // buffered by Kafka and P5 catches up (NFR-2).
            log.warn("could not publish audit event action={} target={}", action, targetId, e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
