package com.discoveryhub.ingestion.service;

import com.discoveryhub.ingestion.api.UploadJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks in-flight and recently finished uploads.
 *
 * <p>In memory, and therefore <b>per instance and lost on restart</b>. That is a real limitation
 * and callers should know it: a job id is only meaningful to the instance that issued it, so this
 * does not survive a redeploy and would not work behind a load balancer without sticky routing.
 *
 * <p>It is nevertheless the right trade here. The alternative is a shared job table, and P1 owns
 * no database precisely so that it stays stateless and an Archive outage cannot lose messages. A
 * durable job store would buy that back for progress reporting on an operation that already
 * republishes safely on retry — the corpus dedupes, so re-running an upload is free.
 *
 * <p>Bounded and evicted by age so a long-running instance cannot accumulate job records
 * indefinitely; an unbounded map keyed on user input is a slow memory leak.
 */
@Component
public class UploadJobStore {

    private final Map<String, UploadJob> jobs = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxJobs;
    private final Duration retention;

    public UploadJobStore(Clock clock,
                          @Value("${discoveryhub.ingestion.upload.max-tracked-jobs:200}") int maxJobs,
                          @Value("${discoveryhub.ingestion.upload.job-retention:PT2H}") Duration retention) {
        this.clock = clock;
        this.maxJobs = maxJobs;
        this.retention = retention;
    }

    public void put(UploadJob job) {
        jobs.put(job.jobId(), job);
        evict();
    }

    public Optional<UploadJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public int size() {
        return jobs.size();
    }

    private void evict() {
        jobs.values().removeIf(job -> job.finishedAt() != null
                && job.finishedAt().isBefore(clock.instant().minus(retention)));

        // Never evict a RUNNING job to make room — dropping the record of work still in progress
        // would report "unknown job" for an upload that is actively ingesting.
        while (jobs.size() > maxJobs) {
            Optional<UploadJob> oldest = jobs.values().stream()
                    .filter(j -> j.status() != UploadJob.Status.RUNNING)
                    .min(Comparator.comparing(UploadJob::startedAt));
            if (oldest.isEmpty()) {
                return;
            }
            jobs.remove(oldest.get().jobId());
        }
    }
}
