package com.discoveryhub.export.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row of the chain of custody (FR-7). {@code eventId} is the primary key rather than a
 * generated one: every emitter derives it deterministically (see {@code AuditEvents} in P1/P2 and
 * {@link com.discoveryhub.export.audit.AuditEventListener}), so a Kafka replay produces the same
 * id and the unique constraint silently absorbs it instead of duplicating the row.
 *
 * <p>There is deliberately no setter here for anything but the fields JPA needs at construction.
 * FR-7.3 requires that an audit entry can never be updated or deleted through any API — enforced
 * simply by {@link com.discoveryhub.export.api.AuditController} exposing no such endpoint, backed
 * by this entity offering no such mutation.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "service", nullable = false, length = 32)
    private String service;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "subject_type", nullable = false, length = 64)
    private String subjectType;

    @Column(name = "subject_id", length = 255)
    private String subjectId;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** JSON-encoded {@code Map<String,String>} — the frozen contract keeps this deliberately loose. */
    @Column(name = "detail", nullable = false)
    private String detail;

    protected AuditLogEntity() {
        // JPA
    }

    public AuditLogEntity(String eventId, Instant occurredAt, String service, String action,
                          String outcome, String subjectType, String subjectId, String actor,
                          String correlationId, String detail) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.service = service;
        this.action = action;
        this.outcome = outcome;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.actor = actor;
        this.correlationId = correlationId;
        this.detail = detail;
    }

    public String getEventId() { return eventId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getService() { return service; }
    public String getAction() { return action; }
    public String getOutcome() { return outcome; }
    public String getSubjectType() { return subjectType; }
    public String getSubjectId() { return subjectId; }
    public String getActor() { return actor; }
    public String getCorrelationId() { return correlationId; }
    public String getDetail() { return detail; }
}
