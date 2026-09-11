package com.discoveryhub.search.service;

import com.discoveryhub.search.model.AddSelectedToCaseRequest;
import com.discoveryhub.search.model.BulkAddToCaseRequest;
import com.discoveryhub.search.model.BulkAddToCaseResponse;
import com.discoveryhub.search.model.SaveSearchRequest;
import com.discoveryhub.search.model.SavedSearch;
import com.discoveryhub.search.model.SearchHistoryEntry;
import com.discoveryhub.search.model.SearchRequest;
import com.discoveryhub.search.model.SearchResponse;

import java.util.List;
import java.util.Optional;

/**
 * The search entry point. The controller delegates here; this is where request validation, size
 * clamping, and sort-strategy selection happen before the assembled query is handed to the
 * repository. Keeping it an interface lets the controller test mock it without a store.
 *
 * <p>Beyond live search, it owns the saved-search lifecycle (save, load, list, delete, re-run) and
 * the bulk add-to-case action.
 */
public interface SearchService {

    SearchResponse search(SearchRequest request);

    /** Persist a search for a case so it can be re-run later. Returns the stored record with its id. */
    SavedSearch saveSearch(SaveSearchRequest request);

    /** Load one saved search by id. */
    Optional<SavedSearch> getSavedSearch(String id);

    /** List saved searches for a case (blank {@code caseId} lists every saved search). */
    List<SavedSearch> listSavedSearches(String caseId);

    /** Delete a saved search. */
    void deleteSavedSearch(String id);

    /** Load a saved search by id and re-run it. Throws if the id does not exist. */
    SearchResponse runSavedSearch(String id);

    /** The most recent executed searches, newest first. {@code limit} is clamped server-side. */
    List<SearchHistoryEntry> listHistory(Integer limit);

    /** Remove every history entry. */
    void clearHistory();

    /** Add the matched messages to a case: the current page by default, or all matches if allResults. */
    BulkAddToCaseResponse addToCase(BulkAddToCaseRequest request);

    /** Add exactly the hand-picked message ids to a case. No search is re-run. */
    BulkAddToCaseResponse addSelectedToCase(AddSelectedToCaseRequest request);
}
