package com.discoveryhub.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent form of a case. The {@code status} column is mirrored from {@link CaseStatus}; the
 * transition rules live in the State objects, and this entity is the durable record of the
 * resulting status. {@code closedAt} is set the moment the case transitions to CLOSED, and from
 * then on the case is read-only (FR-2.4): no new custodians, evidence, holds, or exports.
 */
@Entity
@Table(name = "cases")
public class CaseEntity {

    @Id
    @Column(name = "case_id", length = 36)
    private String caseId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "matter_type", nullable = false, length = 32)
    private MatterType matterType;

    @Column(name = "owner", nullable = false, length = 255)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CaseStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected CaseEntity() {
        // JPA
    }

    public CaseEntity(String caseId, String name, String description, MatterType matterType,
                      String owner, CaseStatus status, Instant createdAt) {
        this.caseId = caseId;
        this.name = name;
        this.description = description;
        this.matterType = matterType;
        this.owner = owner;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public MatterType getMatterType() { return matterType; }
    public void setMatterType(MatterType matterType) { this.matterType = matterType; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public CaseStatus getStatus() { return status; }
    public void setStatus(CaseStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
