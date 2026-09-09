package com.discoveryhub.cases.api;

import com.discoveryhub.cases.domain.CaseStatus;

/** Requested target status for a case transition (FR-2.2). Validity is decided by the State. */
public record TransitionRequest(CaseStatus targetStatus) {
}
