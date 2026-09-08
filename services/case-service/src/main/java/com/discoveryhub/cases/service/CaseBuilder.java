package com.discoveryhub.cases.service;

import com.discoveryhub.cases.domain.CaseEntity;
import com.discoveryhub.cases.domain.CaseStatus;
import com.discoveryhub.cases.domain.MatterType;

import java.time.Instant;
import java.util.UUID;

/**
 * Builder (creational) for a freshly created {@link CaseEntity}. Centralises the invariants a new
 * case must satisfy — non-blank name and owner, a matter type — so the service and any future
 * caller (a seed script, a test) cannot assemble an invalid case. {@code build()} assigns a new
 * {@code caseId} and stamps {@code createdAt} and the {@code DRAFT} status, so the create path is
 * consistent regardless of who calls it.
 *
 * <p>Case ids are random UUIDs, not derived: unlike messages, cases are created by a user, not
 * replayed from a source system, so the deterministic-id guarantee that {@code Ids.messageId}
 * gives messages does not apply here.
 */
public final class CaseBuilder {

    private String name;
    private String description;
    private MatterType matterType;
    private String owner;

    private CaseBuilder() {
    }

    public static CaseBuilder create() {
        return new CaseBuilder();
    }

    public CaseBuilder name(String name) {
        this.name = name;
        return this;
    }

    public CaseBuilder description(String description) {
        this.description = description;
        return this;
    }

    public CaseBuilder matterType(MatterType matterType) {
        this.matterType = matterType;
        return this;
    }

    public CaseBuilder owner(String owner) {
        this.owner = owner;
        return this;
    }

    public CaseEntity build() {
        require(name, "name");
        require(owner, "owner");
        if (matterType == null) {
            throw new IllegalArgumentException("matterType is required");
        }
        String trimmedDescription = description == null || description.isBlank() ? null : description;
        return new CaseEntity(
                UUID.randomUUID().toString(),
                name.trim(),
                trimmedDescription,
                matterType,
                owner.trim(),
                CaseStatus.DRAFT,
                Instant.now());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
