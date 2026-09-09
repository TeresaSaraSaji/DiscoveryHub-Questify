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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Retention and disposition (FR-5). Finds messages past their type-specific retention, asks P4
 * synchronously whether each is held, deletes the ones that are not, and records every outcome
 * (deleted / skipped-hold) so a run is auditable after the fact (FR-5.3).
 *
 * <p>Fail-closed: if P4 cannot be reached for a given message, that message is skipped as if held
 * and the run continues — one unreachable check must not abort the whole sweep, and it must never
 * let a held message be deleted. If P4 is down for the entire run the run still completes, having
 * skipped everything; that is the correct, safe behaviour.
 *
 * <p>Each candidate is re-loaded with {@link MessageRepository#findByIdForUpdate} right before
 * its P4 check, taking a row lock for the rest of this transaction. Without it, a concurrent
 * {@code HoldsEventListener} update could apply a hold to a message between "P4 said not held"
 * and the delete a few lines later; the lock makes that update wait instead.
 *
 * <p>{@link #running} prevents two sweeps from overlapping <i>within this JVM</i> — a manual
 * {@code POST /disposition/runs} firing while the scheduled cron tick is mid-sweep would otherwise
 * process the same candidates twice, doubling deletes and audit rows. It does not prevent two
 * separate instances of this service from both running a sweep at once; that would need a
 * database-level advisory lock, which is a deliberate follow-up rather than done here.
 */
@Service
public class DispositionService {

    private static final Logger log = LoggerFactory.getLogger(DispositionService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

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

    /**
     * Run a full disposition sweep. Returns the persisted run record (already COMPLETED/FAILED).
     *
     * <p>The running-guard is checked and released around the transactional body rather than in a
     * separate method: {@code @Transactional} on a method only takes effect through Spring's
     * proxy, and a self-invoked call (this class calling its own method) bypasses the proxy
     * entirely, silently dropping the transaction. Keeping everything in this one proxied method
     * avoids that trap.
     *
     * @throws IllegalStateException if a sweep is already running in this JVM (see class javadoc)
     */
    @Transactional
    public DispositionRunEntity runOnce() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "a disposition sweep is already running; refusing to start a second one");
        }
        try {
            return doRunOnce();
        } finally {
            running.set(false);
        }
    }

    private DispositionRunEntity doRunOnce() {
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
            for (MessageEntity candidate : eligible) {
                // Re-fetch under a row lock rather than trust the snapshot from
                // findDispositionEligible: without the lock, a concurrent HoldsEventListener could
                // place a hold on this exact message between the P4 check below and the delete
                // that follows it, and this transaction would never see it. The lock makes that
                // update block until this transaction is done.
                Optional<MessageEntity> locked = messages.findByIdForUpdate(candidate.getMessageId());
                if (locked.isEmpty()) {
                    // Already deleted by something else (e.g. DELETE /messages/{id}) since the
                    // candidate list was built.
                    continue;
                }
                MessageEntity m = locked.get();
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
                // Rows first, blobs only once this sweep's transaction commits. The whole sweep is
                // one transaction, so deleting bytes inline would mean a failure on a later message
                // rolls back the rows for the earlier ones whose bytes are already destroyed —
                // metadata claiming attachments that no longer exist, and no way back.
                List<AttachmentEntity> atts = attachments.findByMessageIdOrderByOrdinalAsc(m.getMessageId());
                attachments.deleteByMessageId(m.getMessageId());
                messages.delete(m);
                messages.flush();
                storage.deleteAfterCommit(atts);
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
