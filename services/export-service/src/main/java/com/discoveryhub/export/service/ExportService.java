package com.discoveryhub.export.service;

import com.discoveryhub.export.domain.ExportJobEntity;
import com.discoveryhub.export.domain.ExportStatus;
import com.discoveryhub.export.messaging.ExportEvents;
import com.discoveryhub.export.messaging.ExportKafkaPublisher;
import com.discoveryhub.export.repository.ExportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates one export job end to end (FR-6). {@code submit} is the fast, synchronous half:
 * validate, persist QUEUED, hand off to Kafka, return a job id immediately. {@code process} is the
 * slow half, run on the {@code export.jobs} consumer thread: resolve the scope, build the package,
 * promote it from staging to packages, and record the outcome — never partially.
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportJobRepository jobs;
    private final ArchiveClient archive;
    private final PackageBuilder packageBuilder;
    private final ObjectStorageClient storage;
    private final ExportKafkaPublisher publisher;
    private final ExportEvents events;
    private final ObjectMapper json;

    public ExportService(ExportJobRepository jobs, ArchiveClient archive, PackageBuilder packageBuilder,
                         ObjectStorageClient storage, ExportKafkaPublisher publisher,
                         ExportEvents events, ObjectMapper json) {
        this.jobs = jobs;
        this.archive = archive;
        this.packageBuilder = packageBuilder;
        this.storage = storage;
        this.publisher = publisher;
        this.events = events;
        this.json = json;
    }

    public ExportJobEntity submit(ExportRequest request) {
        if (!request.isExplicit() && (request.custodianId() == null || request.custodianId().isBlank())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "provide either messageIds or a custodianId to scope the export");
        }
        String jobId = UUID.randomUUID().toString();
        ExportJobEntity job = new ExportJobEntity(jobId, request.caseId(), writeScope(request), Instant.now());
        jobs.save(job);

        int scopeSize = request.isExplicit() ? request.messageIds().size() : -1;
        publisher.publishAudit(events.requested(jobId, request.caseId(), scopeSize));
        publisher.publishJobRequested(jobId);
        return job;
    }

    /** Rebuilds a failed job from scratch. A job that has not failed cannot be retried — it is either still working or already done. */
    public ExportJobEntity retry(String jobId) {
        ExportJobEntity job = jobs.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "export job not found: " + jobId));
        if (job.getStatus() != ExportStatus.FAILED) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "only a FAILED job can be retried; this job is " + job.getStatus());
        }
        job.setStatus(ExportStatus.QUEUED);
        job.setAttempts(job.getAttempts() + 1);
        job.setError(null);
        jobs.save(job);
        publisher.publishJobRequested(jobId);
        return job;
    }

    /**
     * Does the actual work. Idempotent against redelivery: a job already past {@code QUEUED} is
     * left alone, so a duplicate {@code export.jobs} record (a rebalance, an at-least-once
     * redelivery) cannot rebuild a package that already exists or clobber one that is in flight.
     */
    public void process(String jobId) {
        Optional<ExportJobEntity> maybeJob = jobs.findById(jobId);
        if (maybeJob.isEmpty()) {
            log.warn("export job {} not found; nothing to process", jobId);
            return;
        }
        ExportJobEntity job = maybeJob.get();
        if (job.getStatus() != ExportStatus.QUEUED) {
            log.debug("export job {} is {} already; skipping duplicate work request", jobId, job.getStatus());
            return;
        }

        job.setStatus(ExportStatus.RUNNING);
        job.setStartedAt(Instant.now());
        jobs.save(job);

        String key = jobId + ".zip";
        try {
            ExportRequest request = readScope(job.getRequestedScope());
            List<String> messageIds = request.isExplicit()
                    ? request.messageIds()
                    : archive.resolveCustodianScope(request.custodianId(), request.from(), request.to());
            if (messageIds.isEmpty()) {
                throw new IllegalStateException("export scope resolved to zero messages");
            }

            PackageResult result = packageBuilder.build(jobId, job.getCaseId(), messageIds);
            storage.stage(key, result.zipBytes());
            storage.promote(key);

            job.setStatus(ExportStatus.COMPLETED);
            job.setFinishedAt(Instant.now());
            job.setObjectKey(key);
            job.setPackageSha256(result.packageSha256());
            job.setPackageSizeBytes((long) result.zipBytes().length);
            job.setItemCount(result.itemCount());
            jobs.save(job);
            publisher.publishAudit(events.completed(jobId, result.itemCount(), result.packageSha256()));
            log.info("export job {} completed: {} items, {} bytes, sha256={}",
                    jobId, result.itemCount(), result.zipBytes().length, result.packageSha256());
        } catch (Exception ex) {
            log.error("export job {} failed", jobId, ex);
            storage.discardStaged(key);
            job.setStatus(ExportStatus.FAILED);
            job.setFinishedAt(Instant.now());
            job.setError(truncate(ex.toString()));
            jobs.save(job);
            publisher.publishAudit(events.failed(jobId, job.getError()));
        }
    }

    public Optional<ExportJobEntity> find(String jobId) {
        return jobs.findById(jobId);
    }

    public List<ExportJobEntity> recent() {
        return jobs.findTop50ByOrderByQueuedAtDesc();
    }

    private String writeScope(ExportRequest request) {
        try {
            return json.writeValueAsString(request);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialise export scope", ex);
        }
    }

    private ExportRequest readScope(String scope) {
        try {
            return json.readValue(scope, ExportRequest.class);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse stored export scope", ex);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() <= 1000 ? s : s.substring(0, 1000) + "…");
    }
}
