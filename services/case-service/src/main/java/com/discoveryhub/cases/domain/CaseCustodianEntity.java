package com.discoveryhub.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A custodian (employee) whose communications are in scope for a case (FR-2.3). The pair
 * (caseId, custodianId) is unique: attaching the same custodian twice is a no-op, not an error.
 * A hold scoped by a case's custodians freezes those custodians' messages.
 */
@Entity
@Table(name = "case_custodians", uniqueConstraints = @UniqueConstraint(columnNames = {"case_id", "custodian_id"}))
public class CaseCustodianEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "case_id", nullable = false, length = 36)
    private String caseId;

    @Column(name = "custodian_id", nullable = false, length = 255)
    private String custodianId;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected CaseCustodianEntity() {
        // JPA
    }

    public CaseCustodianEntity(String caseId, String custodianId, Instant addedAt) {
        this.caseId = caseId;
        this.custodianId = custodianId;
        this.addedAt = addedAt;
    }

    public Long getId() { return id; }
    public String getCaseId() { return caseId; }
    public String getCustodianId() { return custodianId; }
    public Instant getAddedAt() { return addedAt; }
}
