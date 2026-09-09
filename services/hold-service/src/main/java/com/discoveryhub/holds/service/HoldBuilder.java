package com.discoveryhub.holds.service;

import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Builder (creational) for a freshly placed hold. Validates the invariants a new hold must
 * satisfy — a non-blank case id and at least one custodian — and stamps a new {@code holdId},
 * the {@code RESOLVING} status, and {@code placedAt}. The scope is applied to the entity's columns
 * via {@link HoldEntity#applyScope} at build time, so the persisted row carries the scope the
 * worker will resolve.
 *
 * <p>Like {@code CaseBuilder}, hold ids are random UUIDs: holds are created by a user, not
 * replayed from a source system, so the deterministic-id guarantee that messages need does not
 * apply.
 */
public final class HoldBuilder {

    private String caseId;
    private HoldScope scope;

    private HoldBuilder() {
    }

    public static HoldBuilder create() {
        return new HoldBuilder();
    }

    public HoldBuilder caseId(String caseId) {
        this.caseId = caseId;
        return this;
    }

    public HoldBuilder scope(HoldScope scope) {
        this.scope = scope;
        return this;
    }

    public HoldEntity build() {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId is required");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        if (scope.custodians().isEmpty()) {
            throw new IllegalArgumentException("hold scope must name at least one custodian");
        }
        HoldEntity entity = new HoldEntity(
                UUID.randomUUID().toString(),
                caseId,
                HoldStatus.RESOLVING,
                Instant.now());
        entity.applyScope(scope);
        return entity;
    }
}
