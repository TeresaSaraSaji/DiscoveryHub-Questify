package com.discoveryhub.search.service;

import com.discoveryhub.search.config.SearchProperties;
import com.discoveryhub.search.model.CommunicationDocument;
import com.discoveryhub.search.model.SearchRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightFieldParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a {@link SearchRequest} into a Spring Data Elasticsearch {@link Query}.
 *
 * <p>The free-text {@code query} is matched as a substring (wildcard) across the message's text —
 * the body, the subject, and the participants ({@code from}, {@code to}, {@code cc}) — the
 * eDiscovery reading of "find every message mentioning this phrase or this person", not a ranked
 * match. Each filter (custodian, type, from, sent-range, labels, has-attachment, on-hold) is AND-ed
 * on top, so they narrow the text match rather than competing with it.
 *
 * <p>Highlighting is requested on the text fields the query matches (body, subject, from, to, cc),
 * with the fragment size bounded by {@code SearchProperties.snippetSize}. Elasticsearch wraps the
 * matched terms in {@code <em>…</em>}; the mapper turns the body fragment into the snippet and the
 * rest into {@link com.discoveryhub.search.model.SearchResult#highlights()}.
 *
 * <p>The service guarantees the request has at least one criterion before calling this, so the
 * returned query is never an empty match-all. {@link Sort} is attached only when it is actually
 * sorted — a relevance request leaves the sort off so Elasticsearch's score-default ordering
 * stands.
 */
@Component
public class SearchQueryBuilder {

    /** Criteria.contains cannot take a term with a blank in it — see the query build below. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final SearchProperties properties;

    public SearchQueryBuilder(SearchProperties properties) {
        this.properties = properties;
    }

    public Query build(SearchRequest request, Sort sort) {
        CriteriaQuery query = new CriteriaQuery(criteria(request));
        query.setPageable(PageRequest.of(request.page(), request.size()));
        if (sort != null && sort.isSorted()) {
            query.addSort(sort);
        }
        query.setHighlightQuery(highlight());
        return query;
    }

    /** Highlight the text fields a query can match, bounded to the snippet window. */
    private HighlightQuery highlight() {
        HighlightFieldParameters params = HighlightFieldParameters.builder()
                .withFragmentSize(properties.snippetSize())
                .withNumberOfFragments(1)
                .build();
        Highlight highlight = new Highlight(List.of(
                new HighlightField("body", params),
                new HighlightField("subject", params),
                new HighlightField("from", params),
                new HighlightField("to", params),
                new HighlightField("cc", params)));
        return new HighlightQuery(highlight, CommunicationDocument.class);
    }

    private Criteria criteria(SearchRequest request) {
        Criteria criteria = null;

        if (request.query() != null && !request.query().isBlank()) {
            String term = request.query();
            // Full-text across the message's text: body + subject + the participants. A search for a
            // person's address or a phrase in a subject hits the same query as a search for body text.
            // Wrapped in a sub-criteria so the AND-ed filters below apply to the whole OR group, not
            // just the last OR'd field — without this, a filter like onHold=false would bind as
            // (body OR subject OR from OR to OR (cc AND onHold=false)) instead of
            // (body OR subject OR from OR to OR cc) AND onHold=false.
            //
            // from is a Keyword field (see CommunicationDocument), so its wildcard match is
            // case-sensitive against the exact stored string, unlike body/subject/to/cc's analyzed
            // Text match. Lower-casing the term against the lower-cased stored value (see
            // CommunicationDocumentMapper.fromMessage) keeps a search for "alice" matching
            // From: Alice@firm.test the same way it already matches that address in to/cc.
            //
            // Split on whitespace, and AND the words together. Criteria.contains builds a wildcard
            // query, and Spring Data Elasticsearch refuses a wildcard containing a blank —
            // `*quarterly report*` throws InvalidDataAccessApiUsageException("Cannot constructQuery
            // ... Use expression or multiple clauses instead"), which surfaced as a 500 on every
            // multi-word search. A two-word phrase is the most ordinary query there is, so this is
            // the "multiple clauses" the exception asks for: each word must appear somewhere in the
            // message, though not necessarily in the same field, which is the useful reading of
            // "quarterly report from alice".
            // Each word becomes its own OR group, and the groups are added as sibling
            // sub-criteria of one field-less root, which is how Spring Data Elasticsearch ANDs
            // them. Note `and(Criteria)` is *not* the way to do it: it asserts the criteria has a
            // field, and these groups deliberately do not have one.
            Criteria text = new Criteria();
            boolean any = false;
            for (String word : WHITESPACE.split(term.trim())) {
                if (word.isEmpty()) {
                    continue;
                }
                text = text.subCriteria(new Criteria("body").contains(word)
                        .or(new Criteria("subject").contains(word))
                        .or(new Criteria("from").contains(word.toLowerCase(Locale.ROOT)))
                        .or(new Criteria("to").contains(word))
                        .or(new Criteria("cc").contains(word)));
                any = true;
            }
            // A query of nothing but whitespace leaves no clause at all rather than an empty one,
            // so the filters below still decide the result instead of a field-less criteria
            // reaching Elasticsearch and failing there.
            if (any) {
                criteria = text;
            }
        }

        List<String> custodianIds = request.custodianIds();
        if (!custodianIds.isEmpty()) {
            criteria = chain(criteria, new Criteria("custodianId").in(custodianIds.toArray()));
        }

        if (request.type() != null) {
            criteria = chain(criteria, new Criteria("type").is(request.type()));
        }

        if (request.from() != null && !request.from().isBlank()) {
            criteria = chain(criteria, new Criteria("from").is(request.from().toLowerCase(Locale.ROOT)));
        }

        Instant after = request.sentAfter();
        Instant before = request.sentBefore();
        if (after != null || before != null) {
            // Inclusive on both ends, matching the e-discovery convention (a hold's date range and
            // disposition's retention cutoff are both inclusive — see DateRangeScopeFilter and
            // MessageRepository.findDispositionEligible). An exclusive bound here previously meant
            // a query for "messages sent on or before 2024-12-31T23:59:59Z" silently dropped any
            // message sent at exactly that instant.
            Criteria range = new Criteria("sentAt");
            if (after != null) {
                range = range.greaterThanEqual(after);
            }
            if (before != null) {
                range = range.lessThanEqual(before);
            }
            criteria = chain(criteria, range);
        }

        if (!request.labels().isEmpty()) {
            criteria = chain(criteria, new Criteria("labels").in(request.labels().toArray()));
        }

        // hasAttachment is tri-state: null = no filter, true = attachmentCount > 0,
        // false = attachmentCount == 0. Stored as an int count rather than a boolean so it also
        // serves the "this hit carried N attachments" display.
        if (Boolean.TRUE.equals(request.hasAttachment())) {
            criteria = chain(criteria, new Criteria("attachmentCount").greaterThan(0));
        } else if (Boolean.FALSE.equals(request.hasAttachment())) {
            criteria = chain(criteria, new Criteria("attachmentCount").is(0));
        }

        // onHold mirrors holds.events so a held message can be flagged and filtered. A hold
        // preserves, it does not suppress, so onHold=false filters to the unheld set rather than
        // removing held messages from the index.
        if (Boolean.TRUE.equals(request.onHold())) {
            criteria = chain(criteria, new Criteria("onHold").is(true));
        } else if (Boolean.FALSE.equals(request.onHold())) {
            criteria = chain(criteria, new Criteria("onHold").is(false));
        }

        return criteria;
    }

    private static Criteria chain(Criteria existing, Criteria next) {
        return existing == null ? next : existing.and(next);
    }
}
