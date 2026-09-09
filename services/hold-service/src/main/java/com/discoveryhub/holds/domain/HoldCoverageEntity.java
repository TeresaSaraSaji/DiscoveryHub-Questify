package com.discoveryhub.holds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per (hold, message) the hold protects. This table is the authoritative source for
 * {@code GET /holds/check} (FR-4.2): a message is held iff a row exists with an ACTIVE hold.
 *
 * <p>On placement the worker inserts the coverage for the resolved scope; on release the rows are
 * left in place (the hold flips to RELEASED, so the joined {@code status = 'ACTIVE'} check stops
 * matching) — keeping the history of what a hold covered rather than deleting it. Overlapping
 * holds (FR-4.5) are simply multiple rows for the same messageId under different holds; releasing
 * one flips its hold to RELEASED and the other still matches.
 */
@Entity
@Table(name = "hold_coverage")
@IdClass(HoldCoverageId.class)
public class HoldCoverageEntity {

    @Id
    @Column(name = "hold_id", length = 36)
    private String holdId;

    @Id
    @Column(name = "message_id", length = 36)
    private String messageId;

    @Column(name = "covered_at", nullable = false)
    private Instant coveredAt;

    protected HoldCoverageEntity() {
        // JPA
    }

    public HoldCoverageEntity(String holdId, String messageId, Instant coveredAt) {
        this.holdId = holdId;
        this.messageId = messageId;
        this.coveredAt = coveredAt;
    }

    public String getHoldId() { return holdId; }
    public String getMessageId() { return messageId; }
    public Instant getCoveredAt() { return coveredAt; }
}
