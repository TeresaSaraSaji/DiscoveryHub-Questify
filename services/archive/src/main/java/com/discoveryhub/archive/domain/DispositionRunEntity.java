package com.discoveryhub.archive.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One sweep of the retention/disposition job (FR-5). Records what was deleted, what was skipped
 * because a hold was in force, and when. The per-item detail lives in {@link DispositionItemEntity}.
 */
@Entity
@Table(name = "disposition_runs")
public class DispositionRunEntity {

    @Id
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DispositionStatus status;

    @Column(name = "deleted_count", nullable = false)
    private int deletedCount;

    @Column(name = "skipped_hold_count", nullable = false)
    private int skippedHoldCount;

    @Column(name = "error")
    private String error;

    protected DispositionRunEntity() {
        // JPA
    }

    public DispositionRunEntity(String runId, Instant startedAt, DispositionStatus status) {
        this.runId = runId;
        this.startedAt = startedAt;
        this.status = status;
    }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public DispositionStatus getStatus() { return status; }
    public void setStatus(DispositionStatus status) { this.status = status; }

    public int getDeletedCount() { return deletedCount; }
    public void setDeletedCount(int deletedCount) { this.deletedCount = deletedCount; }

    public int getSkippedHoldCount() { return skippedHoldCount; }
    public void setSkippedHoldCount(int skippedHoldCount) { this.skippedHoldCount = skippedHoldCount; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
