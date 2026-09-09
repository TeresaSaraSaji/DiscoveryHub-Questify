package com.discoveryhub.cases.api;

/** A custodian to attach to a case (FR-2.3). Attaching the same custodian twice is a no-op. */
public record AddCustodianRequest(String custodianId) {
}
