package com.discoveryhub.export.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One export request and what became of it (FR-6). {@code requestedScope} is the JSON the caller
 * submitted, kept verbatim so a job's origin is reconstructable without a second table. Only
 * {@code objectKey} in the packages bucket is ever the download target — a job that has not
 * reached {@code COMPLETED} has no object there yet, per the staging/packages split (FR-6.6).
 */
@Entity
@Table(name = "export_jobs")
public class ExportJobEntity {

    @Id
    @Column(name = "job_id", length = 36)
    private String jobId;

    @Column(name = "case_id", length = 255)
    private String caseId;

    @Column(name = "requested_scope", nullable = false)
    private String requestedScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ExportStatus status;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "object_key", length = 255)
    private String objectKey;

    @Column(name = "package_sha256", length = 64)
    private String packageSha256;

    @Column(name = "package_size_bytes")
    private Long packageSizeBytes;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error")
    private String error;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ExportJobEntity() {
        // JPA
    }

    public ExportJobEntity(String jobId, String caseId, String requestedScope, Instant queuedAt) {
        this.jobId = jobId;
        this.caseId = caseId;
        this.requestedScope = requestedScope;
        this.status = ExportStatus.QUEUED;
        this.queuedAt = queuedAt;
        this.attempts = 0;
        this.itemCount = 0;
    }

    public String getJobId() { return jobId; }

    public String getCaseId() { return caseId; }

    public String getRequestedScope() { return requestedScope; }

    public ExportStatus getStatus() { return status; }
    public void setStatus(ExportStatus status) { this.status = status; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getPackageSha256() { return packageSha256; }
    public void setPackageSha256(String packageSha256) { this.packageSha256 = packageSha256; }

    public Long getPackageSizeBytes() { return packageSizeBytes; }
    public void setPackageSizeBytes(Long packageSizeBytes) { this.packageSizeBytes = packageSizeBytes; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Instant getQueuedAt() { return queuedAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
