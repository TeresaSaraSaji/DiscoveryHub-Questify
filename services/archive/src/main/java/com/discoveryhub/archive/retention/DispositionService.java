package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.config.RetentionProperties;
import com.discoveryhub.archive.domain.AttachmentEntity;
import com.discoveryhub.archive.domain.DispositionItemEntity;
import com.discoveryhub.archive.domain.DispositionOutcome;
import com.discoveryhub.archive.domain.DispositionRunEntity;
import com.discoveryhub.archive.domain.DispositionStatus;
import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.archive.repository.AttachmentRepository;
import com.discoveryhub.archive.repository.DispositionItemRepository;
import com.discoveryhub.archive.repository.DispositionRunRepository;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.archive.storage.AttachmentStore;
import com.discoveryhub.contracts.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Retention and disposition (FR-5). Finds messages past their type-specific retention, asks P4
 * synchronously whether each is held, deletes the ones that are not, and records every outcome
 * (deleted / skipped-hold) so a run is auditable after the fact (FR-5.3).
 *
 * <p>Fail-closed: if P4 cannot be reached for a given message, that message is skipped as if held
 * and the run continues — one unreachable check must not abort the whole sweep, and it must never
 * let a held message be deleted. If P4 is down for the entire run the run still completes, having
 * skipped everything; that is the correct, safe behaviour.
 */
@Service
public class DispositionService {

    private static final Logger log = LoggerFactory.getLogger(DispositionService.class);

    private final MessageRepository messages;
    private final AttachmentRepository attachments;
    private final DispositionRunRepository runs;
    private final DispositionItemRepository items;
    private final RetentionProperties retention;
    private final HoldCheckClient holdCheck;
    private final ArchiveKafkaPublisher publisher;
    private final AuditEvents audit;
    private final AttachmentStore storage;

    public DispositionService(MessageRepository messages, AttachmentRepository attachments,
                              DispositionRunRepository runs, DispositionItemRepository items,
                              RetentionProperties retention, HoldCheckClient holdCheck,
                              ArchiveKafkaPublisher publisher, AuditEvents audit,
                              AttachmentStore storage) {
        this.messages = messages;
        this.attachments = attachments;
        this.runs = runs;
        this.items = items;
        this.retention = retention;
        this.holdCheck = holdCheck;
        this.publisher = publisher;
        this.audit = audit;
        this.storage = storage;
    }

    /** Run a full disposition sweep. Returns the persisted run record (already COMPLETED/FAILED). */
    @Transactional
    public DispositionRunEntity runOnce() {
        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        DispositionRunEntity run = new DispositionRunEntity(runId, startedAt, DispositionStatus.RUNNING);
        runs.save(run);

        Instant now = Instant.now();
        Instant emailCutoff = now.minus(retention.period(MessageType.EMAIL));
        Instant chatCutoff = now.minus(retention.period(MessageType.CHAT));
        List<MessageEntity> eligible = messages.findDispositionEligible(
                MessageType.EMAIL, MessageType.CHAT, emailCutoff, chatCutoff);

        log.info("disposition run {} started: {} eligible candidates (email cutoff {}, chat cutoff {})",
                runId, eligible.size(), emailCutoff, chatCutoff);

        int deleted = 0;
        int skippedHold = 0;
        List<DispositionItemEntity> itemRecords = new ArrayList<>();

        try {
            for (MessageEntity m : eligible) {
                // The local on_hold flag is a fast-path skip; the authoritative check is the P4 call.
                if (m.isOnHold()) {
                    itemRecords.add(skip(runId, m, "local hold flag set"));
                    skippedHold++;
                    continue;
                }
                if (holdCheck.isHeld(m.getMessageId())) {
                    itemRecords.add(skip(runId, m, "P4 hold check returned held"));
                    skippedHold++;
                    publisher.publishAudit(audit.dispositionRefused(runId, m.getMessageId(), "held"));
                    continue;
                }
                // Drop the attachment blobs (local file + optional S3 offload copy) before removing
                // the rows, so a successful disposition does not leave orphaned bytes behind.
                for (AttachmentEntity a : attachments.findByMessageIdOrderByOrdinalAsc(m.getMessageId())) {
                    storage.delete(a);
                }
                attachments.deleteByMessageId(m.getMessageId());
                messages.delete(m);
                messages.flush();
                itemRecords.add(delete(runId, m));
                deleted++;
                publisher.publishAudit(audit.dispositionDeleted(runId, m.getMessageId(),
                        m.getExternalId(), m.getCustodianId()));
            }

            run.setStatus(DispositionStatus.COMPLETED);
            run.setFinishedAt(Instant.now());
            run.setDeletedCount(deleted);
            run.setSkippedHoldCount(skippedHold);
            runs.save(run);
            if (!itemRecords.isEmpty()) {
                items.saveAll(itemRecords);
            }
            log.info("disposition run {} completed: deleted={}, skippedHold={}", runId, deleted, skippedHold);
            return run;
        } catch (Exception ex) {
            log.error("disposition run {} failed: {}", runId, ex.toString(), ex);
            run.setStatus(DispositionStatus.FAILED);
            run.setFinishedAt(Instant.now());
            run.setDeletedCount(deleted);
            run.setSkippedHoldCount(skippedHold);
            run.setError(truncate(ex.toString()));
            runs.save(run);
            if (!itemRecords.isEmpty()) {
                items.saveAll(itemRecords);
            }
            publisher.publishAudit(audit.dispositionRunFailed(runId, ex.toString()));
            return run;
        }
    }

    private DispositionItemEntity skip(String runId, MessageEntity m, String reason) {
        return new DispositionItemEntity(runId, m.getMessageId(), m.getExternalId(),
                m.getCustodianId(), DispositionOutcome.SKIPPED_HOLD, reason, Instant.now());
    }

    private DispositionItemEntity delete(String runId, MessageEntity m) {
        return new DispositionItemEntity(runId, m.getMessageId(), m.getExternalId(),
                m.getCustodianId(), DispositionOutcome.DELETED, "past retention", Instant.now());
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() <= 1000 ? s : s.substring(0, 1000) + "…");
    }
}
