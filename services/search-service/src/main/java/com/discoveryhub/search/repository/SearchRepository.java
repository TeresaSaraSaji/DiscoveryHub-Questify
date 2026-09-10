package com.discoveryhub.search.repository;

import com.discoveryhub.search.model.CommunicationDocument;
import com.discoveryhub.search.model.SavedSearch;
import com.discoveryhub.search.model.SearchRequest;
import com.discoveryhub.search.model.SearchResponse;
import org.springframework.data.elasticsearch.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The search-service's view of its own store: index documents, search them, mirror hold state,
 * persist saved searches, and scan matched message ids for the bulk add-to-case action.
 *
 * <p>Implementations are free to use any backing engine — this is the seam the unit tests sit at,
 * mocking it so a search service test never needs a running Elasticsearch. {@link Query} is the
 * Spring Data Elasticsearch abstraction, which the service assembles before handing it over; the
 * repository's job is to execute and map, not to decide what to look for.
 */
public interface SearchRepository {

    /** Execute an assembled query and return one page of results. */
    SearchResponse search(Query query, SearchRequest request);

    /**
     * Index (or re-index) a single archived message. Must not clobber an {@code onHold} flag (or
     * its {@code holdUpdatedAt}) already present on the document — a re-index (e.g. after a
     * consumer restart replays {@code messages.archived}) carries no hold information of its own,
     * so it must preserve whatever {@link #setHold}/{@link #setHoldByCustodian} last wrote.
     */
    void index(CommunicationDocument document);

    /**
     * Mirror hold state onto one document. No-op if the message is not in the index.
     *
     * @param occurredAt when the source {@code holds.events} record was produced. An event whose
     *                    {@code occurredAt} is not after the document's currently stored
     *                    {@code holdUpdatedAt} is a stale replay/redelivery and is ignored, so an
     *                    out-of-order event cannot un-hold (or re-hold) a message a newer event
     *                    already settled.
     */
    void setHold(String messageId, boolean onHold, Instant occurredAt);

    /** Mirror hold state onto every document in a custodian's mailbox. See {@link #setHold}. */
    void setHoldByCustodian(String custodianId, boolean onHold, Instant occurredAt);

    /** Remove one document — used when a message is permanently disposed. */
    void deleteByMessageId(String messageId);

    /** Persist (or update) a saved search. */
    SavedSearch saveSavedSearch(SavedSearch savedSearch);

    /** Load one saved search by id, or empty if it does not exist. */
    Optional<SavedSearch> getSavedSearch(String id);

    /** List saved searches for a case ({@code caseId == null} or blank lists every saved search). */
    List<SavedSearch> listSavedSearches(String caseId);

    /** Delete a saved search. No-op if it does not exist. */
    void deleteSavedSearch(String id);

    /**
     * Scan every match for a query and collect their message ids, up to {@code max}. Used by the
     * "add all results to case" bulk action — the page form uses the ids already in the page
     * response, this is the unbounded (capped) form. Implemented with a scroll so a large match set
     * does not load into one page.
     */
    List<String> searchMessageIds(Query query, int max);
}
