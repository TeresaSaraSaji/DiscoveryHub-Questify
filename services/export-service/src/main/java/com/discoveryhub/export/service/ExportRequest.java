package com.discoveryhub.export.service;

import java.time.Instant;
import java.util.List;

/**
 * The scope of an export (FR-6.1: "a case's evidence items, or a hold's full scope").
 *
 * <p>At least one of {@code caseId}, {@code messageIds} or {@code custodianId} is required, and
 * they compose rather than exclude each other:
 *
 * <ul>
 *   <li>{@code caseId} alone — every evidence item on the case, which is FR-6.1's first half.</li>
 *   <li>{@code caseId} with a {@code custodianId} — the case's evidence from that custodian
 *       only. The custodian narrows the case; it does not replace it.</li>
 *   <li>{@code custodianId} alone, with an optional date range — what a hold's scope resolves
 *       to.</li>
 *   <li>{@code messageIds} — an explicit selection, and it wins over the rest.</li>
 * </ul>
 *
 * <p>{@code from}/{@code to} narrow any of them by {@code sentAt}.
 *
 * <p>{@code caseId} used to be a label on the job rather than a selection, which meant the one
 * scope FR-6.1 names first could not be asked for at all.
 */
public record ExportRequest(String caseId, List<String> messageIds, String custodianId,
                            Instant from, Instant to) {

    public ExportRequest {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }

    public boolean isExplicit() {
        return !messageIds.isEmpty();
    }

    public boolean hasCase() {
        return caseId != null && !caseId.isBlank();
    }

    public boolean hasCustodian() {
        return custodianId != null && !custodianId.isBlank();
    }
}
