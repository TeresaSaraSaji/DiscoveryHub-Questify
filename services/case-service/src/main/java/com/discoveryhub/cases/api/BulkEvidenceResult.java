package com.discoveryhub.cases.api;

/**
 * Summary of a bulk-evidence add, so the caller knows how many were new, how many were already
 * present, and how many could not be filed at all.
 *
 * <p>{@code notInArchive} is the one worth reading. Evidence is filed from search results, and a
 * message destroyed by disposition can still be in the index for as long as it takes P3 to consume
 * the delete receipt. Those are refused rather than written as evidence rows pointing at nothing,
 * and counted here so the investigator learns it now — at the moment of filing — instead of weeks
 * later when an export cannot package them.
 */
public record BulkEvidenceResult(int requested, int added, int alreadyPresent, int notInArchive) {
}
