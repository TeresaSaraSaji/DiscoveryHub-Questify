package com.discoveryhub.disposition.domain;

/** How a run was started. Kept in the ledger so a surprise sweep can be traced to a person. */
public enum TriggerSource {
    SCHEDULED,
    MANUAL
}
