package com.discoveryhub.ingestion.service;

import com.discoveryhub.ingestion.api.UploadJob;
import com.discoveryhub.ingestion.api.UploadResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Runs an upload in the background and reports progress against a job id.
 *
 * <p>Exists because the synchronous endpoint stops being honest at scale: the 12,000-message
 * corpus takes about 75 seconds, which is past what a browser or a proxy will wait for, and a
 * timed-out request leaves the caller unable to tell whether anything was ingested.
 *
 * <p><b>The file is copied to disk before the request returns.</b> A {@code MultipartFile}'s
 * stream is only valid for the life of the request — Spring cleans up its temporary storage on
 * completion — so handing that stream to a background thread yields a closed-stream failure some
 * seconds later, on an upload the caller was already told had been accepted. The copy is deleted
 * in a finally block whatever the outcome.
 *
 * <p>The pool is small and its queue is bounded by rejection rather than by growth: concurrent
 * uploads each hold a chunk of messages in memory, and an unbounded queue would accept work it
 * cannot run and then run out of heap. Refusing a request outright is the honest failure.
 */
@Service
public class AsyncUploadService {

    private static final Logger log = LoggerFactory.getLogger(AsyncUploadService.class);

    private final UploadService uploadService;
    private final UploadJobStore jobs;
    private final Clock clock;
    private final ExecutorService executor;

    public AsyncUploadService(UploadService uploadService, UploadJobStore jobs, Clock clock,
                              @Value("${discoveryhub.ingestion.upload.workers:2}") int workers) {
        this.uploadService = uploadService;
        this.jobs = jobs;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(workers, Thread.ofPlatform()
                .name("upload-", 0).daemon(true).factory());
    }

    /**
     * @param filename for reporting only
     * @param in       read to completion and copied to disk before this returns
     * @return the accepted job, already RUNNING
     */
    public UploadJob submit(String filename, InputStream in) throws IOException {
        Path spooled = Files.createTempFile("dh-upload-", ".json");
        Files.copy(in, spooled, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        String jobId = UUID.randomUUID().toString();
        UploadJob job = UploadJob.running(jobId, filename, clock.instant());
        jobs.put(job);

        try {
            executor.submit(() -> run(jobId, filename, spooled));
        } catch (RejectedExecutionException e) {
            Files.deleteIfExists(spooled);
            jobs.put(job.failed("ingestion is busy; retry shortly", clock.instant()));
            throw e;
        }
        return job;
    }

    private void run(String jobId, String filename, Path spooled) {
        try (InputStream in = Files.newInputStream(spooled)) {
            UploadResponse result = uploadService.ingest(filename, in,
                    processed -> jobs.find(jobId)
                            .ifPresent(j -> jobs.put(j.withProgress(processed))));
            jobs.find(jobId).ifPresent(j -> jobs.put(j.completed(result, clock.instant())));
        } catch (IOException | RuntimeException e) {
            // RuntimeException already covers TooManyMessagesException and any malformed-JSON
            // failure from the parser. Nothing here should escape onto the pool thread: an
            // uncaught one would leave the job stuck at RUNNING forever with nobody working on it.
            log.warn("upload job {} ({}) failed", jobId, filename, e);
            jobs.find(jobId).ifPresent(j -> jobs.put(j.failed(e.getMessage(), clock.instant())));
        } finally {
            try {
                Files.deleteIfExists(spooled);
            } catch (IOException e) {
                log.warn("could not delete spooled upload {}", spooled, e);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            // Give a running upload a chance to finish rather than leaving a job stuck at RUNNING
            // with no thread behind it.
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
