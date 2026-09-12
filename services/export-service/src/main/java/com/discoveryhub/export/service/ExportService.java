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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final CaseClient cases;
    private final PackageBuilder packageBuilder;
    private final ObjectStorageClient storage;
    private final ExportKafkaPublisher publisher;
    private final ExportEvents events;
    private final ObjectMapper json;

    public ExportService(ExportJobRepository jobs, ArchiveClient archive, CaseClient cases,
                         PackageBuilder packageBuilder, ObjectStorageClient storage,
                         ExportKafkaPublisher publisher, ExportEvents events, ObjectMapper json) {
        this.jobs = jobs;
        this.archive = archive;
        this.cases = cases;
        this.packageBuilder = packageBuilder;
        this.storage = storage;
        this.publisher = publisher;
        this.events = events;
        this.json = json;
    }

    public ExportJobEntity submit(ExportRequest request) {
        if (!request.isExplicit() && !request.hasCase() && !request.hasCustodian()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "provide a caseId, messageIds or a custodianId to scope the export");
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
        // Set right before the irreversible step, not after it returns: promote() copies to the
        // packages bucket and then removes the staging copy, so even a promote() that throws
        // partway through (copy succeeded, remove failed) can have already made the package
        // downloadable. Any failure from this point on must also try to remove it from packages —
        // otherwise a FAILED job can still leave a fully downloadable package behind (FR-6.6).
        boolean promotionAttempted = false;
        try {
            ExportRequest request = readScope(job.getRequestedScope());
            List<String> messageIds = resolve(request);
            if (messageIds.isEmpty()) {
                throw new IllegalStateException("export scope resolved to zero messages");
            }

            PackageResult result = packageBuilder.build(jobId, job.getCaseId(), messageIds);
            storage.stage(key, result.zipBytes());
            promotionAttempted = true;
            storage.promote(key);

            job.setStatus(ExportStatus.COMPLETED);
            job.setFinishedAt(Instant.now());
            job.setObjectKey(key);
            job.setPackageSha256(result.packageSha256());
            job.setPackageSizeBytes((long) result.zipBytes().length);
            job.setItemCount(result.itemCount());
            job.setMissingCount(result.missingCount());
            jobs.save(job);
            publisher.publishAudit(events.completed(jobId, result.itemCount(), result.packageSha256()));
            log.info("export job {} completed: {} items, {} missing, {} bytes, sha256={}",
                    jobId, result.itemCount(), result.missingCount(), result.zipBytes().length,
                    result.packageSha256());
        } catch (Exception ex) {
            log.error("export job {} failed", jobId, ex);
            storage.discardStaged(key);
            if (promotionAttempted) {
                storage.discardPackage(key);
            }
            job.setStatus(ExportStatus.FAILED);
            job.setFinishedAt(Instant.now());
            job.setError(truncate(ex.toString()));
            jobs.save(job);
            publisher.publishAudit(events.failed(jobId, job.getError()));
        }
    }

    /**
     * Turns a requested scope into the concrete message ids to package (FR-6.1).
     *
     * <p>Three shapes, in precedence order:
     *
     * <ul>
     *   <li><b>Explicit ids</b> — exported as given, no resolution.</li>
     *   <li><b>A case</b> — P4's evidence list for that case, optionally narrowed to one
     *       custodian and/or a date range.</li>
     *   <li><b>A custodian alone</b> — that custodian's archived messages in the date range,
     *       which is what a hold's scope resolves to.</li>
     * </ul>
     */
    private List<String> resolve(ExportRequest request) {
        if (request.isExplicit()) {
            return request.messageIds();
        }
        if (request.hasCase()) {
            return resolveCaseScope(request);
        }
        return archive.resolveCustodianScope(request.custodianId(), request.from(), request.to());
    }

    /**
     * A case's evidence, narrowed by whatever else was asked for.
     *
     * <p>P4's evidence rows carry only a message id, so any narrowing has to be answered by P2.
     * Which way round depends on what was given, and the difference is not cosmetic on a case
     * holding several hundred items:
     *
     * <ul>
     *   <li><b>With a custodian</b>, P2 is asked once for that custodian's messages and the two
     *       sets are intersected — two or three paged calls, and the date range comes free
     *       because {@code resolveCustodianScope} already applies it.</li>
     *   <li><b>With only dates</b>, there is nothing to intersect against, so each evidence
     *       message is read to check its {@code sentAt}. One call per item, which is why it is
     *       not the path taken whenever a custodian could do the work instead.</li>
     * </ul>
     *
     * <p>An evidence item that is no longer in the archive is not this method's problem to report:
     * on the filtered paths it simply does not match, and on the unfiltered path it reaches
     * {@code PackageBuilder}, which records it in the manifest's {@code missing} list. Either way
     * the export proceeds with what survives — see that class for why refusing outright was the
     * wrong answer.
     */
    private List<String> resolveCaseScope(ExportRequest request) {
        List<String> evidence = cases.evidenceMessageIds(request.caseId());
        if (evidence.isEmpty()) {
            // Said here rather than left to the generic "scope resolved to zero messages", because
            // the two are different problems: an empty case needs evidence filing onto it, a
            // filter that matched nothing needs widening.
            throw new IllegalStateException(
                    "case " + request.caseId() + " has no evidence items to export");
        }
        if (request.hasCustodian()) {
            Set<String> custodianScope = new HashSet<>(
                    archive.resolveCustodianScope(request.custodianId(), request.from(), request.to()));
            return evidence.stream().filter(custodianScope::contains).toList();
        }
        if (request.from() == null && request.to() == null) {
            return evidence;
        }
        return evidence.stream()
                .filter(id -> archive.findMessage(id)
                        .filter(m -> inRange(m.sentAt(), request.from(), request.to()))
                        .isPresent())
                .toList();
    }

    private static boolean inRange(Instant sentAt, Instant from, Instant to) {
        if (from != null && sentAt.isBefore(from)) {
            return false;
        }
        return to == null || !sentAt.isAfter(to);
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
