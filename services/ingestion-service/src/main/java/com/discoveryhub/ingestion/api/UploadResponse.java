package com.discoveryhub.ingestion.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The outcome of one uploaded file.
 *
 * <p>Counts are exact; the per-item detail is capped. A 12,000-message upload reporting every
 * result would return a response several megabytes wide that no frontend would render and no
 * person would read. What someone actually needs is how many landed and what went wrong with the
 * ones that did not — so {@code problems} carries only the failures, and only the first few.
 * {@code problemsTruncated} says plainly when there were more, rather than leaving the reader to
 * infer it from the arithmetic.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadResponse(
        String filename,
        int totalMessages,
        int accepted,
        int duplicates,
        int rejected,
        int failed,
        List<IngestResult> problems,
        boolean problemsTruncated) {
}
