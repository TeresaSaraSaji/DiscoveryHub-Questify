package com.discoveryhub.archive.domain;

import com.discoveryhub.contracts.MessageType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * P2's slim, own-Postgres bookkeeping row for one archived message: just enough to run the
 * legal-hold guard and the retention/disposition sweep without touching Mongo. The message
 * content itself lives in {@link ArchivedMessageDocument}.
 *
 * <p>{@code onHold} / {@code holdCount} mirror P4's hold state (FR-4.5: {@code holdCount} supports
 * overlapping holds). This flag is an optimisation, not the guarantee: the disposition job and
 * the delete guard still ask P4 synchronously before deleting anything, because a stale flag here
 * would destroy evidence (architecture decision 4).
 *
 * <p>{@code UNIQUE (external_id)} is P2's own idempotency backstop (FR-1.6), same role as it
 * played on the old {@code messages} table — P1's {@code message_id_map} is the first line of
 * defence, this is the second.
 */
@Entity
@Table(name = "message_hold_status")
public class MessageHoldStatus {

    @Id
    @Column(name = "message_id", length = 36)
    private String messageId;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "custodian_id", nullable = false, length = 255)
    private String custodianId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private MessageType type;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "on_hold", nullable = false)
    private boolean onHold;

    @Column(name = "hold_count", nullable = false)
    private int holdCount;

    @Column(name = "archived_at", nullable = false)
    private Instant archivedAt;

    /**
     * Set only when P1 tagged this message with {@code RetentionLabels.DEMO_RETENTION} at
     * ingestion: the absolute instant at which this one message becomes disposition-eligible,
     * overriding the type-based cutoff. {@code null} for every ordinary message. See
     * {@code V4__retention_override.sql}.
     */
    @Column(name = "retention_override_at")
    private Instant retentionOverrideAt;

    protected MessageHoldStatus() {
        // JPA
    }

    public MessageHoldStatus(String messageId, String externalId, String custodianId,
                             MessageType type, Instant sentAt, Instant archivedAt,
                             Instant retentionOverrideAt) {
        this.messageId = messageId;
        this.externalId = externalId;
        this.custodianId = custodianId;
        this.type = type;
        this.sentAt = sentAt;
        this.onHold = false;
        this.holdCount = 0;
        this.archivedAt = archivedAt;
        this.retentionOverrideAt = retentionOverrideAt;
    }

    public String getMessageId() { return messageId; }

    public String getExternalId() { return externalId; }

    public String getCustodianId() { return custodianId; }

    public MessageType getType() { return type; }

    public Instant getSentAt() { return sentAt; }

    public boolean isOnHold() { return onHold; }
    public void setOnHold(boolean onHold) { this.onHold = onHold; }

    public int getHoldCount() { return holdCount; }
    public void setHoldCount(int holdCount) { this.holdCount = holdCount; }

    public Instant getArchivedAt() { return archivedAt; }

    public Instant getRetentionOverrideAt() { return retentionOverrideAt; }
}
