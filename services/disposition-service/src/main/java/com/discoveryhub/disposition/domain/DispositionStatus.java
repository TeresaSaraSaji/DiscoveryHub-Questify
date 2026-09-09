package com.discoveryhub.disposition.domain;

/** Lifecycle of a disposition run. Mirrors the CHECK constraint in {@code V1__disposition.sql}. */
public enum DispositionStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
