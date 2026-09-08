package com.discoveryhub.cases.lifecycle;

/**
 * Raised when a mutation (custodian, evidence, update, new hold) is attempted on a closed case,
 * which is read-only (FR-2.4). Mapped to HTTP 409 Conflict by the API exception handler.
 */
public class CaseReadOnlyException extends RuntimeException {

    private final String caseId;

    public CaseReadOnlyException(String caseId) {
        super("case is closed and read-only: " + caseId);
        this.caseId = caseId;
    }

    public String caseId() {
        return caseId;
    }
}
