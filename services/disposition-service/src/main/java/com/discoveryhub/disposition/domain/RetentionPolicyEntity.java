package com.discoveryhub.disposition.domain;

import com.discoveryhub.contracts.MessageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * Retention period for one communication type (FR-5.1).
 *
 * <p>Stored as seconds rather than an ISO-8601 string so the eligibility query can do arithmetic
 * on it in SQL, and so "7 years" and "2 minutes" are the same column rather than two code paths.
 * {@link #period()} converts back at the edges.
 */
@Entity
@Table(name = "retention_policies")
public class RetentionPolicyEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 16)
    private MessageType messageType;

    @Column(name = "period_seconds", nullable = false)
    private long periodSeconds;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 255)
    private String updatedBy;

    protected RetentionPolicyEntity() {
        // JPA
    }

    public RetentionPolicyEntity(MessageType messageType, Duration period, String updatedBy) {
        this.messageType = messageType;
        setPeriod(period);
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }

    /** The configured retention as a {@link Duration}. */
    public Duration period() {
        return Duration.ofSeconds(periodSeconds);
    }

    /**
     * @throws IllegalArgumentException if the period is null, zero or negative — that would make
     *         the entire corpus eligible for deletion on the next sweep. Mirrors the
     *         {@code ck_retention_period_positive} constraint so the failure is a 400 at the API
     *         rather than a constraint violation at flush time.
     */
    public void setPeriod(Duration period) {
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("retention period must be positive, got: " + period);
        }
        this.periodSeconds = period.toSeconds();
    }

    public MessageType getMessageType() { return messageType; }

    public long getPeriodSeconds() { return periodSeconds; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
