package com.discoveryhub.export.messaging;

import com.discoveryhub.contracts.AuditEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Builds the {@link AuditEvent}s P5 emits for FR-6 export activity and FR-7's own bookkeeping. */
@Component
public final class ExportEvents {

    private static final String SERVICE = "P5";
    private static final String ACTOR = "system";

    public AuditEvent requested(String jobId, String caseId, int scopeSize) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("caseId", String.valueOf(caseId));
        detail.put("scopeSize", String.valueOf(scopeSize));
        return event("export.requested", AuditEvent.Outcome.SUCCESS, "export-job", jobId, jobId, detail);
    }

    public AuditEvent completed(String jobId, int itemCount, String packageSha256) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("itemCount", String.valueOf(itemCount));
        detail.put("packageSha256", packageSha256);
        return event("export.completed", AuditEvent.Outcome.SUCCESS, "export-job", jobId, jobId, detail);
    }

    public AuditEvent failed(String jobId, String reason) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        return event("export.failed", AuditEvent.Outcome.FAILURE, "export-job", jobId, jobId, detail);
    }

    public AuditEvent downloaded(String jobId) {
        return event("export.downloaded", AuditEvent.Outcome.SUCCESS, "export-job", jobId, jobId, Map.of());
    }

    private AuditEvent event(String action, AuditEvent.Outcome outcome, String subjectType,
                             String subjectId, String correlationId, Map<String, String> detail) {
        Instant occurredAt = Instant.now();
        String eventId = UUID.nameUUIDFromBytes(
                (action + "|" + subjectId + "|" + occurredAt).getBytes(StandardCharsets.UTF_8)).toString();
        return new AuditEvent(eventId, occurredAt, SERVICE, action, outcome,
                subjectType, subjectId, ACTOR, correlationId, detail);
    }
}
