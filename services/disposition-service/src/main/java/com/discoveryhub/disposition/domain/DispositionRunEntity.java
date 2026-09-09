package com.discoveryhub.disposition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One sweep of the disposition job (FR-5.3): what was deleted, what was skipped because a hold was
 * in force, what failed, and when. Per-item detail lives in {@link DispositionItemEntity}.
 *
 * <p>The counts are denormalised onto the run rather than derived from the items on every read.
 * A sweep over the full 10,000-message corpus writes 10,000 item rows, and the dashboard asks for
 * the summary far more often than the detail.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 16)
    private TriggerSource triggerSource;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "deleted_count", nullable = false)
    private int deletedCount;

    @Column(name = "skipped_hold_count", nullable = false)
    private int skippedHoldCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "active_hold_count", nullable = false)
    private int activeHoldCount;

    /**
     * False when P4 could not be asked for the hold scope. A run with this false that deleted
     * nothing was failing closed, not idle — and the ledger has to be able to say which.
     */
    @Column(name = "hold_scope_available", nullable = false)
    private boolean holdScopeAvailable = true;

    @Column(name = "error")
    private String error;

    protected DispositionRunEntity() {
        // JPA
    }

    public DispositionRunEntity(String runId, Instant startedAt, TriggerSource triggerSource, boolean dryRun) {
        this.runId = runId;
        this.startedAt = startedAt;
        this.status = DispositionStatus.RUNNING;
        this.triggerSource = triggerSource;
        this.dryRun = dryRun;
    }

    public String getRunId() { return runId; }

    public Instant getStartedAt() { return startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public DispositionStatus getStatus() { return status; }
    public void setStatus(DispositionStatus status) { this.status = status; }

    public TriggerSource getTriggerSource() { return triggerSource; }

    public boolean isDryRun() { return dryRun; }

    public int getCandidateCount() { return candidateCount; }
    public void setCandidateCount(int candidateCount) { this.candidateCount = candidateCount; }

    public int getDeletedCount() { return deletedCount; }
    public void setDeletedCount(int deletedCount) { this.deletedCount = deletedCount; }

    public int getSkippedHoldCount() { return skippedHoldCount; }
    public void setSkippedHoldCount(int skippedHoldCount) { this.skippedHoldCount = skippedHoldCount; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public int getActiveHoldCount() { return activeHoldCount; }
    public void setActiveHoldCount(int activeHoldCount) { this.activeHoldCount = activeHoldCount; }

    public boolean isHoldScopeAvailable() { return holdScopeAvailable; }
    public void setHoldScopeAvailable(boolean holdScopeAvailable) { this.holdScopeAvailable = holdScopeAvailable; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
