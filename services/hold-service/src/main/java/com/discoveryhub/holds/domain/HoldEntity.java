package com.discoveryhub.holds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Persistent form of a legal hold. The scope is stored as scalar columns — custodians as a
 * comma-separated string (corpus custodian ids contain no commas), the date range and search terms
 * as their own columns — so the holds table stays queryable without a JSON parser.
 *
 * <p>{@link #toScope()} rebuilds the {@link HoldScope} value object; {@link #applyScope(HoldScope)}
 * writes the columns. The two are the only bridge between the entity's columns and the value
 * object, so the rest of the service deals only in {@code HoldScope}.
 */
@Entity
@Table(name = "holds")
public class HoldEntity {

    @Id
    @Column(name = "hold_id", length = 36)
    private String holdId;

    @Column(name = "case_id", nullable = false, length = 36)
    private String caseId;

    /** Comma-separated custodian ids. */
    @Column(name = "custodians", nullable = false)
    private String custodians;

    @Column(name = "date_from")
    private Instant dateFrom;

    @Column(name = "date_to")
    private Instant dateTo;

    @Column(name = "search_terms")
    private String searchTerms;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private HoldStatus status;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_reason")
    private String releasedReason;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "error")
    private String error;

    protected HoldEntity() {
        // JPA
    }

    public HoldEntity(String holdId, String caseId, HoldStatus status, Instant placedAt) {
        this.holdId = holdId;
        this.caseId = caseId;
        this.status = status;
        this.placedAt = placedAt;
    }

    /** Write the scope columns from the value object. */
    public void applyScope(HoldScope scope) {
        this.custodians = scope.custodians().isEmpty() ? "" : String.join(",", scope.custodians());
        this.dateFrom = scope.dateFrom();
        this.dateTo = scope.dateTo();
        this.searchTerms = scope.searchTerms();
    }

    /** Rebuild the immutable scope value object from the columns. */
    public HoldScope toScope() {
        return new HoldScope(custodianList(), dateFrom, dateTo, searchTerms);
    }

    public List<String> custodianList() {
        if (custodians == null || custodians.isBlank()) {
            return List.of();
        }
        return Arrays.stream(custodians.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String getHoldId() { return holdId; }
    public String getCaseId() { return caseId; }
    public Instant getDateFrom() { return dateFrom; }
    public Instant getDateTo() { return dateTo; }
    public String getSearchTerms() { return searchTerms; }
    public HoldStatus getStatus() { return status; }
    public void setStatus(HoldStatus status) { this.status = status; }
    public Instant getPlacedAt() { return placedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
    public String getReleasedReason() { return releasedReason; }
    public void setReleasedReason(String releasedReason) { this.releasedReason = releasedReason; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
