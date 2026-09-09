package com.discoveryhub.cases.domain;

/**
 * Why a case exists (FR-2.1). Drives no behaviour today; carried for the audit trail and the UI so
 * an investigator can tell an internal investigation apart from a regulatory inquiry.
 */
public enum MatterType {
    INVESTIGATION,
    LITIGATION,
    REGULATORY_INQUIRY
}
