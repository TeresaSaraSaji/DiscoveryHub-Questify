package com.discoveryhub.export.messaging;

/**
 * The payload on {@code export.jobs} (Topics.EXPORT_JOBS: "P5 to its own workers, one export job
 * to build"). Deliberately just the id — the job row in Postgres is the source of truth for
 * everything about it, so the message is a wake-up call, not a copy of the state.
 */
public record ExportJobRequested(String jobId) {
}
