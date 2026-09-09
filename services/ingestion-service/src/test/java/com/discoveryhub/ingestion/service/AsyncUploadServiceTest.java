package com.discoveryhub.ingestion.service;

import com.discoveryhub.ingestion.api.RetentionMode;
import com.discoveryhub.ingestion.api.UploadJob;
import com.discoveryhub.ingestion.api.UploadResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The one thing this service exists for — running an upload off the request thread and reporting
 * its outcome against a job id — plus the two bugs code review found: an unbounded queue behind
 * the "bounded by rejection" claim, and a temp file leak when the initial copy itself fails.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncUploadServiceTest {

    @Mock UploadService uploadService;

    private final Clock clock = Clock.fixed(Instant.parse("2024-05-11T21:37:00Z"), ZoneOffset.UTC);
    private UploadJobStore jobs;
    private AsyncUploadService service;

    @BeforeEach
    void setUp() {
        jobs = new UploadJobStore(clock, 200, Duration.ofHours(2));
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private AsyncUploadService serviceWithWorkers(int workers) {
        service = new AsyncUploadService(uploadService, jobs, clock, workers);
        return service;
    }

    private InputStream content() {
        return new ByteArrayInputStream("{}".getBytes());
    }

    @Test
    void submitSpoolsTheStreamAndReturnsARunningJob() throws Exception {
        when(uploadService.ingest(anyString(), any(), any(RetentionMode.class), any())).thenReturn(
                new UploadResponse("f.json", 1, 1, 0, 0, 0, List.of(), false));

        UploadJob job = serviceWithWorkers(2).submit("f.json", content());

        assertThat(job.status()).isEqualTo(UploadJob.Status.RUNNING);
        assertThat(job.filename()).isEqualTo("f.json");
    }

    @Test
    void aSuccessfulJobIsRecordedAsCompletedAndTheSpooledFileIsDeleted() throws Exception {
        UploadResponse result = new UploadResponse("f.json", 3, 3, 0, 0, 0, List.of(), false);
        when(uploadService.ingest(anyString(), any(), any(RetentionMode.class), any())).thenReturn(result);

        UploadJob job = serviceWithWorkers(2).submit("f.json", content());
        UploadJob finished = awaitFinished(job.jobId());

        assertThat(finished.status()).isEqualTo(UploadJob.Status.COMPLETED);
        assertThat(finished.result()).isEqualTo(result);
        assertNoSpooledFilesLeftBehind();
    }

    @Test
    void aFailedJobIsRecordedAsFailedAndTheSpooledFileIsStillDeleted() throws Exception {
        when(uploadService.ingest(anyString(), any(), any(RetentionMode.class), any()))
                .thenThrow(new IOException("archive unreachable"));

        UploadJob job = serviceWithWorkers(2).submit("f.json", content());
        UploadJob finished = awaitFinished(job.jobId());

        assertThat(finished.status()).isEqualTo(UploadJob.Status.FAILED);
        assertThat(finished.error()).contains("archive unreachable");
        assertNoSpooledFilesLeftBehind();
    }

    @Test
    void submitDeletesTheSpooledFileWhenTheInitialCopyFails() throws IOException {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("client disconnected");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        assertThatThrownBy(() -> serviceWithWorkers(2).submit("f.json", broken))
                .isInstanceOf(IOException.class);

        // M2 regression: a temp file was created by Files.createTempFile before the copy that
        // failed; it must not be left behind with no job ever tracking it.
        assertNoSpooledFilesLeftBehind();
    }

    @Test
    void aFullQueueRejectsRatherThanGrowingWithoutBound() throws Exception {
        // M1 regression: Executors.newFixedThreadPool backs its pool with an unbounded queue, so
        // this used to accept every submission no matter how many were already queued. With 1
        // worker and a small bounded queue, saturating both must throw RejectedExecutionException.
        CountDownLatch blockWorker = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(uploadService.ingest(anyString(), any(), any(RetentionMode.class), any())).thenAnswer(inv -> {
            blockWorker.countDown();
            release.await();
            return new UploadResponse("f.json", 1, 1, 0, 0, 0, List.of(), false);
        });

        AsyncUploadService svc = serviceWithWorkers(1);
        // One job occupies the single worker thread.
        svc.submit("blocking.json", content());
        assertThat(blockWorker.await(5, TimeUnit.SECONDS)).isTrue();

        // Fill the bounded queue (capacity = workers * 4 = 4) with jobs that will never run
        // because the worker is stuck.
        assertThatThrownBy(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    svc.submit("queued-" + i + ".json", content());
                } catch (IOException impossible) {
                    throw new UncheckedIOException(impossible);
                }
            }
        }).isInstanceOf(RejectedExecutionException.class);

        release.countDown();
    }

    private UploadJob awaitFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<UploadJob> found = jobs.find(jobId);
            if (found.isPresent() && found.get().status() != UploadJob.Status.RUNNING) {
                return found.get();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("job " + jobId + " never finished");
    }

    private void assertNoSpooledFilesLeftBehind() throws IOException {
        String tmpDir = System.getProperty("java.io.tmpdir");
        try (var stream = Files.list(Path.of(tmpDir))) {
            List<Path> leaked = stream
                    .filter(p -> p.getFileName().toString().startsWith("dh-upload-"))
                    .toList();
            assertThat(leaked).isEmpty();
        }
    }
}
