package com.discoveryhub.cases.domain;

/**
 * How an evidence item got onto the case (FR-2.4). {@code MANUAL} is a single message picked by the
 * investigator; {@code SEARCH} is a saved-search result set (or a page of one) added in bulk. The
 * distinction is audited so a reviewer can tell curated evidence from bulk-added evidence.
 */
public enum EvidenceSource {
    MANUAL,
    SEARCH
}
