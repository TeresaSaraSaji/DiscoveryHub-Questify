package com.discoveryhub.cases.domain;

/**
 * Case lifecycle status (FR-2.2). Valid forward transitions are
 * {@code DRAFT -> ACTIVE -> UNDER_REVIEW -> CLOSED}; {@code CLOSED} is terminal. The set of legal
 * transitions is owned by the {@link com.discoveryhub.cases.lifecycle.CaseState State} objects, not
 * by a switch here, so adding a state is an additive change (OCP).
 */
public enum CaseStatus {
    DRAFT,
    ACTIVE,
    UNDER_REVIEW,
    CLOSED
}
