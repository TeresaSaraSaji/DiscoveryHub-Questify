package com.discoveryhub.archive.domain;

/** Per-item outcome inside a disposition run (FR-5.3). */
public enum DispositionOutcome {
    /** Message was past retention and not held; deleted. */
    DELETED,
    /** Message was past retention but covered by an active hold; left in place. */
    SKIPPED_HOLD
}
