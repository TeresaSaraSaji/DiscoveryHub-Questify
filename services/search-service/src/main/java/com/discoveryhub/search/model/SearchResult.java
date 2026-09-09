package com.discoveryhub.search.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One hit in a {@link SearchResponse}. The attachment bytes are not here (message-schema.md); only
 * the count and the filenames, so a reviewer can see what a hit carried.
 *
 * <p>{@code snippet} is a window around the first query-term match in the body, bounded by
 * {@code SearchProperties.snippetSize} so a large body does not ship whole in every hit.
 * {@code highlights} is the Elasticsearch highlight of the matched fields (body, subject, and the
 * participant fields), with the matched terms wrapped in {@code <em>…</em>} — this is what the
 * requirement "highlighted matching terms" delivers. When the server returns a highlight for the
 * body, the snippet falls back to that highlighted fragment so a UI shows one consistent hit
 * preview; {@code highlights} carries the per-field detail for any field a UI wants to render
 * inline.
 *
 * <p>{@code onHold} is the mirrored hold flag, so a UI can badge a hit without a second call. It is
 * display only — a held message is still a hit, because a hold preserves, it does not suppress.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchResult(
        String messageId,
        String externalId,
        String custodianId,
        String from,
        List<String> to,
        String subject,
        Instant sentAt,
        String snippet,
        List<String> highlights,
        float score,
        boolean onHold,
        int attachmentCount,
        List<String> attachmentFilenames) {
}
