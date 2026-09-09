package com.discoveryhub.cases.api;

import com.discoveryhub.cases.domain.MatterType;

/**
 * Create- and update-case payload (FR-2.1). On create, {@code name}, {@code matterType}, and
 * {@code owner} are required; {@code description} is optional. On update, only the supplied fields
 * are applied. {@code source}/{@code searchRef} live on the evidence endpoints, not here.
 */
public record CaseRequest(
        String name,
        String description,
        MatterType matterType,
        String owner) {
}
