package com.discoveryhub.disposition.domain;

import com.discoveryhub.contracts.MessageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One message's fate within a disposition run (FR-5.3).
 *
 * <p>The identifying fields are copied in rather than referenced, because for a deleted message
 * this row is the only remaining record that it ever existed. A foreign key to P2 would be both a
 * cross-service coupling and, after the delete, a dangling one.
 */
@Entity
@Table(name = "disposition_items")
public class DispositionItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "custodian_id", nullable = false, length = 255)
    private String custodianId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 16)
    private MessageType messageType;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 24)
    private DispositionOutcome outcome;

    @Column(name = "reason")
    private String reason;

    /**
     * The hold and case that stopped this delete, when one did. Copied in rather than referenced
     * for the same reason as the message fields: P4 may release the hold and close the case, and
     * this row still has to be able to prove what protected the message at the time.
     */
    @Column(name = "blocking_hold_id", length = 36)
    private String blockingHoldId;

    @Column(name = "blocking_case_id", length = 36)
    private String blockingCaseId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /**
     * When P2 confirmed what became of a {@code DELETE_REQUESTED} row, and null until it does.
     *
     * <p>Separate from {@code occurredAt} rather than overwriting it, because the gap between the
     * two is the thing worth measuring: {@code occurredAt} is when this service decided to destroy
     * the message, {@code settledAt} is when it actually happened. In {@code KAFKA} delete mode
     * they are minutes apart if P2 is down, and a row where {@code settledAt} is still null hours
     * later is the signal that P2 is not consuming.
     */
    @Column(name = "settled_at")
    private Instant settledAt;

    protected DispositionItemEntity() {
        // JPA
    }

    public DispositionItemEntity(String runId, ArchiveCandidate candidate,
                                 DispositionOutcome outcome, String reason) {
        this(runId, candidate, outcome, reason, null, null);
    }

    public DispositionItemEntity(String runId, ArchiveCandidate candidate,
                                 DispositionOutcome outcome, String reason,
                                 String blockingHoldId, String blockingCaseId) {
        this.runId = runId;
        this.blockingHoldId = blockingHoldId;
        this.blockingCaseId = blockingCaseId;
        this.messageId = candidate.messageId();
        this.externalId = candidate.externalId();
        this.custodianId = candidate.custodianId();
        this.messageType = candidate.type();
        this.sentAt = candidate.sentAt();
        this.outcome = outcome;
        this.reason = reason;
        this.occurredAt = Instant.now();
    }

    /**
     * Record what P2 finally did with a message this run asked it to delete.
     *
     * <p>The only mutation this entity permits, and it is confined to one transition:
     * {@link DispositionOutcome#DELETE_REQUESTED} to whatever P2 reported. A ledger row that could
     * be edited freely would be worthless as evidence, and the transition is guarded here rather
     * than in the listener so no future caller can rewrite a settled outcome — a replayed receipt
     * on an at-least-once topic must not turn a recorded refusal back into a deletion.
     *
     * @return true if this call settled the row; false if it was already settled and nothing
     *         changed, which is the normal answer to a redelivered receipt
     */
    public boolean settle(DispositionOutcome outcome, String reason, Instant settledAt) {
        if (this.outcome != DispositionOutcome.DELETE_REQUESTED) {
            return false;
        }
        this.outcome = outcome;
        this.reason = reason;
        this.settledAt = settledAt;
        return true;
    }

    public Long getId() { return id; }

    public String getRunId() { return runId; }

    public String getMessageId() { return messageId; }

    public String getExternalId() { return externalId; }

    public String getCustodianId() { return custodianId; }

    public MessageType getMessageType() { return messageType; }

    public Instant getSentAt() { return sentAt; }

    public DispositionOutcome getOutcome() { return outcome; }

    public String getReason() { return reason; }

    public String getBlockingHoldId() { return blockingHoldId; }

    public String getBlockingCaseId() { return blockingCaseId; }

    public Instant getOccurredAt() { return occurredAt; }

    public Instant getSettledAt() { return settledAt; }
}
