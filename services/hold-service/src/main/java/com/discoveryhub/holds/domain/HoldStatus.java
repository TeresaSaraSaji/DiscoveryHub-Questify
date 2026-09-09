package com.discoveryhub.holds.domain;

/**
 * Hold lifecycle. Placement is asynchronous: a hold is created {@code RESOLVING}, the worker
 * resolves its scope and flips it {@code ACTIVE}; release flips it {@code RELEASED}; a scope
 * resolution that cannot complete (P2 unreachable) marks it {@code FAILED} so the user can retry.
 *
 * <p>Only {@code ACTIVE} holds count toward {@code GET /holds/check} (a message is held iff an
 * active hold's coverage includes it). {@code RESOLVING} holds have no coverage yet.
 */
public enum HoldStatus {
    RESOLVING,
    ACTIVE,
    RELEASED,
    FAILED
}
