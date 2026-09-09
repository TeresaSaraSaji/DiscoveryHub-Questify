package com.discoveryhub.export.domain;

/** Lifecycle of an export job (FR-6.2): Queued -&gt; Running -&gt; Completed / Failed. */
public enum ExportStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
