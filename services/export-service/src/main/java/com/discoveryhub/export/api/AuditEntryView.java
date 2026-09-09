package com.discoveryhub.export.api;

import com.discoveryhub.export.domain.AuditLogEntity;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/** Read shape for one audit row — {@code detail} is decoded back into a map for the API. */
public record AuditEntryView(
        String eventId,
        Instant occurredAt,
        String service,
        String action,
        String outcome,
        String subjectType,
        String subjectId,
        String actor,
        String correlationId,
        Map<String, String> detail) {

    static AuditEntryView of(AuditLogEntity e, ObjectMapper json) {
        Map<String, String> detail;
        try {
            detail = json.readValue(e.getDetail(), com.discoveryhub.export.audit.AuditEventListener.detailType());
        } catch (Exception ex) {
            detail = Map.of();
        }
        return new AuditEntryView(e.getEventId(), e.getOccurredAt(), e.getService(), e.getAction(),
                e.getOutcome(), e.getSubjectType(), e.getSubjectId(), e.getActor(),
                e.getCorrelationId(), detail);
    }
}
