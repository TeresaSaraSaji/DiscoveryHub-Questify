package com.discoveryhub.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A message added to a case as evidence (FR-2.4). The case-service stores only the reference
 * ({@code messageId}); the message body and attachments live in P2 and are fetched there at export
 * time. The pair (caseId, messageId) is unique: adding the same message twice is a no-op.
 *
 * <p>{@code source} records whether the item was picked manually or added as part of a saved-search
 * result set; {@code searchRef} carries the saved-search identifier when {@code source == SEARCH},
 * so the UI can show "added from search X" and an auditor can re-run that search.
 */
@Entity
@Table(name = "evidence_items", uniqueConstraints = @UniqueConstraint(columnNames = {"case_id", "message_id"}))
public class EvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "case_id", nullable = false, length = 36)
    private String caseId;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private EvidenceSource source;

    @Column(name = "search_ref", length = 255)
    private String searchRef;

    @Column(name = "added_by", length = 255)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected EvidenceEntity() {
        // JPA
    }

    public EvidenceEntity(String caseId, String messageId, EvidenceSource source,
                          String searchRef, String addedBy, Instant addedAt) {
        this.caseId = caseId;
        this.messageId = messageId;
        this.source = source;
        this.searchRef = searchRef;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    public Long getId() { return id; }
    public String getCaseId() { return caseId; }
    public String getMessageId() { return messageId; }
    public EvidenceSource getSource() { return source; }
    public String getSearchRef() { return searchRef; }
    public String getAddedBy() { return addedBy; }
    public Instant getAddedAt() { return addedAt; }
}
