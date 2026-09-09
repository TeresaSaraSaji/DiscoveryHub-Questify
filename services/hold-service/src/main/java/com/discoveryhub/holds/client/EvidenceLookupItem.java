package com.discoveryhub.holds.client;

/**
 * One evidence row as case-service's {@code POST /cases/evidence/lookup} reports it — mirrors
 * {@code com.discoveryhub.cases.api.EvidenceLookupItem} on the other side of that call. Not
 * shared via {@code contracts}: the shape is local to this one integration, same rule applied to
 * {@code HoldEvent} and P2.2's own P4 DTOs.
 */
public record EvidenceLookupItem(String caseId, String messageId) {
}
