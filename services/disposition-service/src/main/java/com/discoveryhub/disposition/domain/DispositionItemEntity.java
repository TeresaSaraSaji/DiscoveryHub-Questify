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

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected DispositionItemEntity() {
        // JPA
    }

    public DispositionItemEntity(String runId, ArchiveCandidate candidate,
                                 DispositionOutcome outcome, String reason) {
        this.runId = runId;
        this.messageId = candidate.messageId();
        this.externalId = candidate.externalId();
        this.custodianId = candidate.custodianId();
        this.messageType = candidate.type();
        this.sentAt = candidate.sentAt();
        this.outcome = outcome;
        this.reason = reason;
        this.occurredAt = Instant.now();
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

    public Instant getOccurredAt() { return occurredAt; }
}
