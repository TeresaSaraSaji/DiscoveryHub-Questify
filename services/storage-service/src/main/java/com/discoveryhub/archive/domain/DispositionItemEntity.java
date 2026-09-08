package com.discoveryhub.archive.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One message's fate within a disposition run: deleted, or skipped because it was held. */
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
    @Column(name = "outcome", nullable = false, length = 16)
    private DispositionOutcome outcome;

    @Column(name = "reason")
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected DispositionItemEntity() {
        // JPA
    }

    public DispositionItemEntity(String runId, String messageId, String externalId, String custodianId,
                                 DispositionOutcome outcome, String reason, Instant occurredAt) {
        this.runId = runId;
        this.messageId = messageId;
        this.externalId = externalId;
        this.custodianId = custodianId;
        this.outcome = outcome;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getCustodianId() { return custodianId; }
    public void setCustodianId(String custodianId) { this.custodianId = custodianId; }

    public DispositionOutcome getOutcome() { return outcome; }
    public void setOutcome(DispositionOutcome outcome) { this.outcome = outcome; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
