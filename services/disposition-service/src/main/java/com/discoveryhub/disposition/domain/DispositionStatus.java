package com.discoveryhub.disposition.domain;

/** Lifecycle of a disposition run. Mirrors the CHECK constraint in {@code V4__queued_runs.sql}. */
public enum DispositionStatus {
    /**
     * Accepted, not yet started. Only ever seen on an asynchronously triggered run, in the moment
     * between the API answering 202 and the executor picking the run up. It is a real persisted
     * state rather than a UI fiction: if the service dies in that window the row is the evidence
     * that a sweep was ordered and never ran.
     */
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
