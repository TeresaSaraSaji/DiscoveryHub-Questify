package com.discoveryhub.cases.api;

/**
 * One evidence row matching a {@code POST /cases/evidence/lookup} query — deliberately just the
 * (caseId, messageId) pair. Whether the case is under an active hold is hold-service's own data,
 * not this service's, so it is left to the caller rather than duplicated or asked for here.
 */
public record EvidenceLookupItem(String caseId, String messageId) {
}
