package com.discoveryhub.cases.messaging;

import com.discoveryhub.contracts.CaseEvent;
import com.discoveryhub.cases.domain.CaseStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Factory (creational) for {@link CaseEvent}. Keeps the action strings and the resulting-status
 * mapping in one place, so the service layer says "transitioned" and the wire verb
 * ({@code case.transitioned}) is decided here. A new event type is a method here, not a string
 * scattered across the service.
 */
@Component
public final class CaseEventFactory {

    public CaseEvent created(String caseId, CaseStatus status, String correlationId) {
        return new CaseEvent(caseId, "case.created", status.name(), null, correlationId, Instant.now());
    }

    public CaseEvent updated(String caseId, CaseStatus status, String correlationId) {
        return new CaseEvent(caseId, "case.updated", status.name(), null, correlationId, Instant.now());
    }

    public CaseEvent transitioned(String caseId, CaseStatus from, CaseStatus to, String correlationId) {
        return new CaseEvent(caseId, "case.transitioned", to.name(), from.name(), correlationId, Instant.now());
    }

    public CaseEvent closed(String caseId, CaseStatus from, String correlationId) {
        return new CaseEvent(caseId, "case.closed", CaseStatus.CLOSED.name(), from.name(), correlationId, Instant.now());
    }
}
