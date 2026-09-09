package com.discoveryhub.disposition.hold;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A message attached to a case as an evidence item (FR-2.4), where that case is under an active
 * hold. P4 reports these from {@code POST /holds/evidence-check}.
 *
 * <p>This is not the same thing as {@link ActiveHold} covering the message, and the difference is
 * why both guards exist. A hold's scope is expressed as custodians, a date range and optionally
 * search terms — but an investigator can add <i>any</i> message to a case, including one from a
 * custodian the hold never mentioned or a date outside its range. Once it is evidence in a held
 * matter it must survive, whether or not it matches the scope that hold was written with.
 *
 * <p>Concretely: a hold scoped to two custodians for 2019-2021 does not cover a 2024 message from
 * a third custodian, so {@link ActiveHold#covers} correctly returns false — and yet if an
 * investigator pulled that message into the case as evidence, deleting it destroys part of the
 * production. Scope matching alone would do exactly that.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvidenceHold(
        String messageId,
        String holdId,
        String caseId,
        String caseName) {

    /** For the ledger and the audit trail: which case's hold protected this evidence item. */
    public String describe() {
        String label = caseName == null || caseName.isBlank() ? caseId : caseName + " (" + caseId + ")";
        return "evidence in case " + label + " under hold " + holdId;
    }
}
