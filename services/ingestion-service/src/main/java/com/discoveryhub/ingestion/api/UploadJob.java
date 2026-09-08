package com.discoveryhub.ingestion.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The state of one asynchronous upload.
 *
 * <p>{@code processed} advances as chunks are ingested, so a caller polling this sees progress
 * rather than a black box. It is not a percentage: the total is unknown until the file has been
 * read to the end, and inventing a denominator would be reporting a number we do not have.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadJob(
        String jobId,
        String filename,
        Status status,
        long processed,
        Instant startedAt,
        Instant finishedAt,
        UploadResponse result,
        String error) {

    public enum Status {
        RUNNING,
        COMPLETED,
        /** The file could not be read or parsed. Nothing further will be ingested from it. */
        FAILED
    }

    public static UploadJob running(String jobId, String filename, Instant startedAt) {
        return new UploadJob(jobId, filename, Status.RUNNING, 0, startedAt, null, null, null);
    }

    public UploadJob withProgress(long processed) {
        return new UploadJob(jobId, filename, status, processed, startedAt, null, null, null);
    }

    public UploadJob completed(UploadResponse result, Instant finishedAt) {
        return new UploadJob(jobId, filename, Status.COMPLETED, result.totalMessages(),
                startedAt, finishedAt, result, null);
    }

    public UploadJob failed(String error, Instant finishedAt) {
        return new UploadJob(jobId, filename, Status.FAILED, processed, startedAt, finishedAt,
                null, error);
    }
}
