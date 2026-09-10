package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.ContentHash;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.ingestion.api.IngestResponse;
import com.discoveryhub.ingestion.api.IngestResult;
import com.discoveryhub.ingestion.api.MessageBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final String SERVICE = "ingestion";
    private static final String ACTOR = "system:ingestion";
    private static final String SUBJECT_TYPE = "message";

    private final DedupeStore dedupe;
    private final EventPublisher publisher;
    private final MessageIdMappingStore idMapping;
    private final Clock clock;

    // Per-instance and reset on restart, which is exactly what they claim to be. P1 stores no
    // messages, so it has no basis for a durable total — the archive's count is P2's to report.
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final Instant startedAt;

    public IngestService(DedupeStore dedupe, EventPublisher publisher,
                         MessageIdMappingStore idMapping, Clock clock) {
        this.dedupe = dedupe;
        this.publisher = publisher;
        this.idMapping = idMapping;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /** Whether an {@code externalId} has already been ingested by this deployment. */
    public boolean hasIngested(String externalId) {
        return dedupe.isClaimed(DedupeStore.EXTERNAL_ID, externalId);
    }

    public IngestStats stats() {
        return new IngestStats(startedAt, acceptedCount.get(), duplicateCount.get(),
                rejectedCount.get(), failedCount.get());
    }

    /**
     * A batch is not atomic. Each message succeeds, dedupes or fails on its own, so one bad or
     * repeated entry cannot reject the other 199. Every audit event from one call shares a
     * correlation id so P5 can reconstruct the batch.
     */
    public IngestResponse ingest(MessageBatch batch) {
        String correlationId = UUID.randomUUID().toString();
        List<IngestResult> results = new ArrayList<>(batch.size());
        for (MessageBatch.Entry entry : batch.entries()) {
            // An element that never decoded is rejected exactly like one that failed validation:
            // it is that message's problem, and the rest of the batch carries on.
            results.add(entry.rejection() == null
                    ? ingestOne(entry.message(), correlationId)
                    : reject(entry.externalId(), entry.rejection(), correlationId));
        }
        IngestResponse response = IngestResponse.of(results);
        acceptedCount.addAndGet(response.accepted());
        duplicateCount.addAndGet(response.duplicates());
        rejectedCount.addAndGet(response.rejected());
        failedCount.addAndGet(response.failed());
        return response;
    }

    /** Convenience for callers that already hold decoded messages, such as tests. */
    public IngestResponse ingest(List<Message> batch) {
        return ingest(MessageBatch.of(batch));
    }

    private IngestResult ingestOne(Message incoming, String correlationId) {
        String rejection = validate(incoming);
        if (rejection != null) {
            return reject(incoming == null ? null : incoming.externalId(), rejection, correlationId);
        }

        Message message = MessageIds.withDerivedIds(incoming);
        String externalId = message.externalId();
        String messageId = message.messageId();
        String contentHash = ContentHash.of(message);

        // Two independent keys. externalId catches a source system re-sending the same record;
        // contentHash catches the same message arriving under a different source key, which a
        // re-export or a second connector on one mailbox will produce. Neither collapses the same
        // conversation captured from two custodians — see ContentHash.
        if (!dedupe.claim(DedupeStore.EXTERNAL_ID, externalId)) {
            // A source system re-sending is normal, not a client fault. Dropping the copy is an
            // audit event and a 2xx outcome (FR-1.6).
            audit("message.deduped", messageId, AuditEvent.Outcome.REFUSED, correlationId,
                    Map.of("externalId", externalId, "matchedOn", DedupeStore.EXTERNAL_ID));
            return IngestResult.duplicate(externalId, messageId, "externalId already ingested");
        }

        if (!dedupe.claim(DedupeStore.CONTENT_HASH, contentHash)) {
            // The externalId claim stays. This key genuinely has been seen now, and releasing it
            // would only mean re-deriving the same duplicate verdict on the next re-send.
            audit("message.deduped", messageId, AuditEvent.Outcome.REFUSED, correlationId,
                    Map.of("externalId", externalId,
                            "contentHash", contentHash,
                            "matchedOn", DedupeStore.CONTENT_HASH));
            return IngestResult.duplicate(externalId, messageId,
                    "identical message already ingested under a different externalId");
        }

        // The durable guarantee (FR-1.6): Redis above is the fast path and fails open, so
        // message_id_map's PK on external_id is what actually prevents a re-submit from being
        // treated as new. If it is already mapped — e.g. Redis was flushed since the first
        // ingestion — this is a duplicate, not a failure, and the Redis claims are released so a
        // legitimate later retry is not mistaken for a duplicate of nothing.
        if (!idMapping.claim(externalId, messageId)) {
            dedupe.release(DedupeStore.EXTERNAL_ID, externalId);
            dedupe.release(DedupeStore.CONTENT_HASH, contentHash);
            audit("message.deduped", messageId, AuditEvent.Outcome.REFUSED, correlationId,
                    Map.of("externalId", externalId, "matchedOn", "message_id_map"));
            return IngestResult.duplicate(externalId, messageId, "externalId already ingested");
        }

        try {
            publisher.publishIngested(message);
        } catch (RuntimeException e) {
            // Release both claims so a retry is not mistaken for a duplicate and silently dropped.
            dedupe.release(DedupeStore.EXTERNAL_ID, externalId);
            dedupe.release(DedupeStore.CONTENT_HASH, contentHash);
            idMapping.release(externalId);
            log.error("publish failed for externalId={}", externalId, e);
            audit("message.ingest_failed", messageId, AuditEvent.Outcome.FAILURE, correlationId,
                    Map.of("externalId", externalId, "error", String.valueOf(e.getMessage())));
            return IngestResult.failed(externalId, messageId, "publish failed");
        }

        audit("message.ingested", messageId, AuditEvent.Outcome.SUCCESS, correlationId,
                Map.of("externalId", externalId,
                        "contentHash", contentHash,
                        "custodianId", message.custodianId(),
                        "type", message.type().name(),
                        "attachmentCount", String.valueOf(message.attachments().size())));
        return IngestResult.accepted(externalId, messageId);
    }

    private IngestResult reject(String externalId, String rejection, String correlationId) {
        audit("message.rejected", externalId, AuditEvent.Outcome.REFUSED, correlationId,
                Map.of("reason", rejection));
        return IngestResult.rejected(externalId, rejection);
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
        return AttachmentIntegrity.check(m);
    }

    /**
     * The shared {@code Outcome} enum has no DUPLICATE value, so a deduped message and a rejected
     * one are both REFUSED and are told apart by {@code action}.
     */
    private void audit(String action, String subjectId, AuditEvent.Outcome outcome,
                       String correlationId, Map<String, String> detail) {
        try {
            publisher.publishAudit(new AuditEvent(
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    SERVICE,
                    action,
                    outcome,
                    SUBJECT_TYPE,
                    subjectId,
                    ACTOR,
                    correlationId,
                    detail));
        } catch (RuntimeException e) {
            // Never let the audit path fail an ingestion that already succeeded; the event is
            // buffered by Kafka and P5 catches up (NFR-2).
            log.warn("could not publish audit event action={} subject={}", action, subjectId, e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
