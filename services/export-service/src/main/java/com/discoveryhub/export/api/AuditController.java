package com.discoveryhub.export.api;

import com.discoveryhub.export.domain.AuditLogEntity;
import com.discoveryhub.export.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * FR-7.4: the audit trail, viewable and filterable per case and across the system. Every filter is
 * optional; {@code correlationId} is the closest thing to a "per case" scope until P4 exists to
 * supply a real case id, since it ties every event from one user action together across services.
 *
 * <p>Deliberately read-only. There is no PUT, PATCH or DELETE anywhere in this controller, which
 * is the whole enforcement mechanism for FR-7.3 — an audit entry cannot be changed through an API
 * that offers no way to change it.
 */
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final AuditLogRepository repository;
    private final ObjectMapper json;

    public AuditController(AuditLogRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @GetMapping
    public Page<AuditEntryView> search(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 500),
                Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<AuditLogEntity> results = repository.search(
                service, action, outcome, subjectType, subjectId, correlationId, from, to, pageable);
        return results.map(e -> AuditEntryView.of(e, json));
    }
}
