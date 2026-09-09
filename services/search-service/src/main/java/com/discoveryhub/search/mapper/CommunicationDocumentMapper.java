package com.discoveryhub.search.mapper;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.search.config.SearchProperties;
import com.discoveryhub.search.model.CommunicationDocument;
import com.discoveryhub.search.model.SearchResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates between the frozen {@link Message} wire format (as it arrives on
 * {@code messages.archived}) and the {@link CommunicationDocument} that Elasticsearch stores, and
 * back out to the {@link SearchResult} a client sees.
 *
 * <p>The archived shape already has attachment {@code contentBase64} dropped (message-schema.md),
 * so nothing here touches bytes — only {@code filename} and the count, which is what a reviewer
 * needs to see what a hit carried without being able to reconstruct it from the index.
 *
 * <p>{@code onHold} starts false; the {@code HoldEventConsumer} flips it as hold events arrive. A
 * message is never removed from the index by a hold — a hold preserves, it does not suppress.
 *
 * <p>Highlighting: when the repository hands back a non-empty {@code highlightFields} map (from
 * Elasticsearch's highlight feature), the body fragment inside it — already wrapped in
 * {@code <em>…</em>} around the matched terms — is used as the snippet, so a UI gets a single
 * consistent highlighted preview. The full per-field highlight list is carried in
 * {@link SearchResult#highlights()} for any field a UI wants to render inline.
 */
@Component
public class CommunicationDocumentMapper {

    private final SearchProperties properties;

    public CommunicationDocumentMapper(SearchProperties properties) {
        this.properties = properties;
    }

    /** Archived wire message -> Elasticsearch document, ready to index with onHold=false. */
    public CommunicationDocument fromMessage(Message m) {
        List<String> filenames = m.attachments().stream()
                .map(Attachment::filename)
                .toList();
        return new CommunicationDocument(
                m.messageId(), m.externalId(), m.source(), m.type(), m.custodianId(), m.from(),
                m.to(), m.cc(), m.subject(), m.body(), m.sentAt(), m.threadId(), m.inReplyTo(),
                m.attachments().size(), filenames, m.labels(), false);
    }

    /**
     * Elasticsearch document -> one search hit, with a bounded snippet around the query term.
     * If the server returned highlighted body fragments, the first one is used as the snippet so
     * the matched terms appear already wrapped in {@code <em>…</em>}.
     */
    public SearchResult toSearchResult(CommunicationDocument doc, float score, String query,
                                       Map<String, List<String>> highlightFields) {
        List<String> bodyHighlights = highlightFields == null ? List.of() : highlightFields.getOrDefault("body", List.of());
        String snippet = bodyHighlights.isEmpty()
                ? snippet(doc.getBody(), query, properties.snippetSize())
                : bodyHighlights.getFirst();
        List<String> highlights = flatten(highlightFields);
        return new SearchResult(
                doc.getMessageId(), doc.getExternalId(), doc.getCustodianId(), doc.getFrom(),
                doc.getTo(), doc.getSubject(), doc.getSentAt(), snippet, highlights, score,
                doc.isOnHold(), doc.getAttachmentCount(), doc.getAttachmentFilenames());
    }

    /** Back-compat overload: no highlight -> plain snippet, empty highlights list. */
    public SearchResult toSearchResult(CommunicationDocument doc, float score, String query) {
        return toSearchResult(doc, score, query, Map.of());
    }

    /** Flatten the per-field highlight map into a single list of highlighted fragments, field by field. */
    private static List<String> flatten(Map<String, List<String>> highlightFields) {
        if (highlightFields == null || highlightFields.isEmpty()) {
            return List.of();
        }
        return highlightFields.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * A window of at most {@code size} characters around the first case-insensitive match of the
     * query in the body. If there is no match, the first {@code size} characters. Ellipses mark a
     * truncated edge so a reviewer can tell the snippet was cut. This is the fallback used when the
     * server did not return a highlighted fragment; the highlighted path above is preferred.
     */
    static String snippet(String body, String query, int size) {
        if (body == null || body.isEmpty() || size <= 0) {
            return "";
        }
        int match = -1;
        if (query != null && !query.isBlank()) {
            match = body.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        }
        if (match < 0) {
            return body.length() <= size ? body : body.substring(0, size) + "...";
        }
        int half = size / 2;
        int start = Math.max(0, match - half);
        int end = Math.min(body.length(), start + size);
        start = Math.max(0, end - size);
        String window = body.substring(start, end);
        return (start > 0 ? "..." : "") + window + (end < body.length() ? "..." : "");
    }
}
