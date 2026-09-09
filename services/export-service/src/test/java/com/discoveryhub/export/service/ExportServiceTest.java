package com.discoveryhub.export.service;

import com.discoveryhub.export.domain.ExportJobEntity;
import com.discoveryhub.export.domain.ExportStatus;
import com.discoveryhub.export.messaging.ExportEvents;
import com.discoveryhub.export.messaging.ExportKafkaPublisher;
import com.discoveryhub.export.repository.ExportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock ExportJobRepository jobs;
    @Mock ArchiveClient archive;
    @Mock PackageBuilder packageBuilder;
    @Mock ObjectStorageClient storage;
    @Mock ExportKafkaPublisher publisher;
    @Mock ExportEvents events;

    private final ObjectMapper json = new ObjectMapper();
    private ExportService service;

    @BeforeEach
    void setUp() {
        service = new ExportService(jobs, archive, packageBuilder, storage, publisher, events, json);
    }

    @Test
    void submitRejectsARequestWithNoScope() {
        assertThatThrownBy(() -> service.submit(new ExportRequest("case-1", List.of(), null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(jobs, never()).save(any());
        verify(publisher, never()).publishJobRequested(anyString());
    }

    @Test
    void submitPersistsAQueuedJobAndPublishesTheWakeUp() {
        ExportRequest request = new ExportRequest("case-1", List.of("m-1", "m-2"), null, null, null);

        ExportJobEntity job = service.submit(request);

        assertThat(job.getStatus()).isEqualTo(ExportStatus.QUEUED);
        verify(jobs).save(job);
        verify(publisher).publishAudit(any());
        verify(publisher).publishJobRequested(job.getJobId());
    }

    @Test
    void processBuildsAndPromotesOnSuccess() throws Exception {
        ExportJobEntity job = queuedJob("job-1", new ExportRequest(null, List.of("m-1"), null, null, null));
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));
        PackageResult result = new PackageResult("zip-bytes".getBytes(), "deadbeef", 3);
        when(packageBuilder.build(eq("job-1"), any(), eq(List.of("m-1")))).thenReturn(result);

        service.process("job-1");

        verify(storage).stage("job-1.zip", result.zipBytes());
        verify(storage).promote("job-1.zip");
        assertThat(job.getStatus()).isEqualTo(ExportStatus.COMPLETED);
        assertThat(job.getObjectKey()).isEqualTo("job-1.zip");
        assertThat(job.getPackageSha256()).isEqualTo("deadbeef");
        assertThat(job.getItemCount()).isEqualTo(3);
        verify(publisher).publishAudit(any());
    }

    @Test
    void processMarksTheJobFailedAndDiscardsStagingWhenTheFailureIsBeforeStaging() throws Exception {
        ExportJobEntity job = queuedJob("job-2", new ExportRequest(null, List.of("m-1"), null, null, null));
        when(jobs.findById("job-2")).thenReturn(Optional.of(job));
        when(packageBuilder.build(anyString(), any(), any())).thenThrow(new IllegalStateException("archive unreachable"));

        service.process("job-2");

        assertThat(job.getStatus()).isEqualTo(ExportStatus.FAILED);
        assertThat(job.getError()).contains("archive unreachable");
        verify(storage, never()).promote(anyString());
        verify(storage).discardStaged("job-2.zip");
        // Nothing was ever promoted to the packages bucket, so there is nothing to discard there.
        verify(storage, never()).discardPackage(anyString());
        verify(publisher).publishAudit(any());
    }

    @Test
    void processDiscardsTheEnBucketPackageWhenPromoteItselfThrows() throws Exception {
        // M1 regression: promote() is copy-then-remove-staging; a throw from promote() can still
        // mean the copy into the packages bucket already succeeded. The failure path must attempt
        // to remove it there too, not just from staging.
        ExportJobEntity job = queuedJob("job-6", new ExportRequest(null, List.of("m-1"), null, null, null));
        when(jobs.findById("job-6")).thenReturn(Optional.of(job));
        PackageResult result = new PackageResult("zip-bytes".getBytes(), "deadbeef", 1);
        when(packageBuilder.build(eq("job-6"), any(), any())).thenReturn(result);
        org.mockito.Mockito.doThrow(new IllegalStateException("minio unreachable"))
                .when(storage).promote("job-6.zip");

        service.process("job-6");

        assertThat(job.getStatus()).isEqualTo(ExportStatus.FAILED);
        verify(storage).discardStaged("job-6.zip");
        verify(storage).discardPackage("job-6.zip");
    }

    @Test
    void processDiscardsTheBucketPackageWhenTheDbSaveAfterPromoteFails() throws Exception {
        // M1 regression: promote() itself can succeed and a later step (persisting COMPLETED,
        // publishing the completion audit) can still fail — the package must not be left behind
        // in the packages bucket for a job that ends up FAILED.
        ExportJobEntity job = queuedJob("job-7", new ExportRequest(null, List.of("m-1"), null, null, null));
        when(jobs.findById("job-7")).thenReturn(Optional.of(job));
        PackageResult result = new PackageResult("zip-bytes".getBytes(), "deadbeef", 1);
        when(packageBuilder.build(eq("job-7"), any(), any())).thenReturn(result);
        // The first save persists RUNNING (must succeed so process() reaches promote); the second
        // save persists COMPLETED and is where the failure happens; the third save (in the catch
        // block, persisting FAILED) must succeed so the test can observe the final state.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(jobs.save(job)).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 1) {
                throw new IllegalStateException("db unreachable");
            }
            return job;
        });

        service.process("job-7");

        assertThat(job.getStatus()).isEqualTo(ExportStatus.FAILED);
        verify(storage).promote("job-7.zip");
        verify(storage).discardPackage("job-7.zip");
    }

    @Test
    void processIgnoresAJobThatIsNotQueued() throws Exception {
        ExportJobEntity job = queuedJob("job-3", new ExportRequest(null, List.of("m-1"), null, null, null));
        job.setStatus(ExportStatus.RUNNING); // already being worked, or already finished
        when(jobs.findById("job-3")).thenReturn(Optional.of(job));

        service.process("job-3");

        verify(packageBuilder, never()).build(anyString(), any(), any());
        verify(jobs, times(0)).save(any());
    }

    @Test
    void retryOnlyAllowedForAFailedJob() {
        ExportJobEntity running = queuedJob("job-4", new ExportRequest(null, List.of("m-1"), null, null, null));
        running.setStatus(ExportStatus.RUNNING);
        when(jobs.findById("job-4")).thenReturn(Optional.of(running));

        assertThatThrownBy(() -> service.retry("job-4")).isInstanceOf(ResponseStatusException.class);
        verify(publisher, never()).publishJobRequested(anyString());
    }

    @Test
    void retryRequeuesAFailedJobAndBumpsAttempts() {
        ExportJobEntity failed = queuedJob("job-5", new ExportRequest(null, List.of("m-1"), null, null, null));
        failed.setStatus(ExportStatus.FAILED);
        failed.setError("boom");
        when(jobs.findById("job-5")).thenReturn(Optional.of(failed));

        ExportJobEntity result = service.retry("job-5");

        assertThat(result.getStatus()).isEqualTo(ExportStatus.QUEUED);
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(result.getError()).isNull();
        verify(publisher).publishJobRequested("job-5");
    }

    private ExportJobEntity queuedJob(String jobId, ExportRequest request) {
        try {
            ExportJobEntity job = new ExportJobEntity(jobId, request.caseId(),
                    json.writeValueAsString(request), java.time.Instant.now());
            return job;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
