package com.discoveryhub.export.service;

import java.time.Instant;
import java.util.List;

/**
 * The scope of an export (FR-6.1: "a case's evidence items, or a hold's full scope"). P4 does not
 * exist yet, so the two scopes it would normally hand us are represented directly: an explicit
 * list of {@code messageIds} (what a case's evidence-item list would resolve to) or a {@code
 * custodianId} plus an optional date range (what a hold's scope would resolve to). Exactly one of
 * the two must be supplied.
 */
public record ExportRequest(String caseId, List<String> messageIds, String custodianId,
                            Instant from, Instant to) {

    public ExportRequest {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }

    public boolean isExplicit() {
        return !messageIds.isEmpty();
    }
}
