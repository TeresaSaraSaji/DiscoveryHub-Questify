package com.discoveryhub.search.controller;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.search.model.AddSelectedToCaseRequest;
import com.discoveryhub.search.model.BulkAddToCaseRequest;
import com.discoveryhub.search.model.BulkAddToCaseResponse;
import com.discoveryhub.search.model.SaveSearchRequest;
import com.discoveryhub.search.model.SavedSearch;
import com.discoveryhub.search.model.SearchHistoryEntry;
import com.discoveryhub.search.model.SearchRequest;
import com.discoveryhub.search.model.SearchResponse;
import com.discoveryhub.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Search over the archived message index, plus the saved-search and bulk add-to-case actions an
 * investigator uses around it.
 *
 * <p>{@code POST /search} takes the full request body (every filter, the full sort control) and is
 * the form a UI uses. {@code GET /search} mirrors it with query params for the common case a person
 * pastes into a browser — a text query and maybe a custodian. Both go through the same service, so
 * the validation, clamping, and strategy selection are identical.
 *
 * <p>Saved searches ({@code /search/saved*}) let an investigator persist a query against a case and
 * re-run it later without retyping the criteria. The bulk add-to-case action
 * ({@code /search/add-to-case}) takes a search and publishes the matched message ids to a case —
 * the current page by default, or every match (the stretch form) with {@code allResults=true}.
 */
@Tag(name = "Search", description = "Search archived messages")
@RestController
@RequestMapping("/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(summary = "Search archived messages",
            description = """
                    Full-text (body, subject, from/to/cc) and filtered search over the archived message
                    index. Filters: date range, communication type, custodian(s), labels, has-attachment,
                    on-hold. Matching terms are highlighted in the result snippets. Defaults are applied
                    for anything omitted, so the smallest valid request is {"query":"fraud"}.""")
    @PostMapping
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return searchService.search(request);
    }

    @Operation(summary = "Search archived messages (query params)",
            description = "The same search as POST /search expressed as query parameters, for the " +
                    "case a person pastes into a browser.")
    @GetMapping
    public SearchResponse search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<String> custodianIds,
            @RequestParam(required = false) MessageType type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) Instant sentAfter,
            @RequestParam(required = false) Instant sentBefore,
            @RequestParam(required = false) List<String> labels,
            @RequestParam(required = false) Boolean hasAttachment,
            @RequestParam(required = false) Boolean onHold,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size,
            @RequestParam(required = false) SearchRequest.SortBy sortBy,
            @RequestParam(required = false) SearchRequest.SortDirection sortDirection) {
        return searchService.search(new SearchRequest(
                query, custodianIds, type, from, sentAfter, sentBefore, labels,
                hasAttachment, onHold, page, size, sortBy, sortDirection));
    }

    @Operation(summary = "Save a search for a case",
            description = "Persists a named search against a case so an investigator can re-run it " +
                    "later without retyping the criteria.")
    @PostMapping("/saved")
    public SavedSearch saveSearch(@Valid @RequestBody SaveSearchRequest request) {
        return searchService.saveSearch(request);
    }

    @Operation(summary = "List saved searches",
            description = "Lists saved searches, optionally filtered by case id.")
    @GetMapping("/saved")
    public List<SavedSearch> listSavedSearches(@RequestParam(required = false) String caseId) {
        return searchService.listSavedSearches(caseId);
    }

    @Operation(summary = "Get one saved search")
    @GetMapping("/saved/{id}")
    public ResponseEntity<SavedSearch> getSavedSearch(@PathVariable String id) {
        return searchService.getSavedSearch(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a saved search")
    @DeleteMapping("/saved/{id}")
    public ResponseEntity<Void> deleteSavedSearch(@PathVariable String id) {
        searchService.deleteSavedSearch(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Re-run a saved search",
            description = "Loads the saved search by id and executes it against the current index.")
    @PostMapping("/saved/{id}/run")
    public SearchResponse runSavedSearch(@PathVariable String id) {
        return searchService.runSavedSearch(id);
    }

    @Operation(summary = "List recent searches",
            description = "The most recently executed searches, newest first. Every first-page " +
                    "search execution is recorded automatically; paging through a result set is not.")
    @GetMapping("/history")
    public List<SearchHistoryEntry> listHistory(@RequestParam(required = false) Integer limit) {
        return searchService.listHistory(limit);
    }

    @Operation(summary = "Clear the search history")
    @DeleteMapping("/history")
    public ResponseEntity<Void> clearHistory() {
        searchService.clearHistory();
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add matched messages to a case",
            description = """
                    Bulk action. Runs the given search and adds the matched message ids to a case. By
                    default only the current page is added ("add all results on this page"); set
                    allResults=true to add every match (capped at discoveryhub.search.max-bulk-results).""")
    @PostMapping("/add-to-case")
    public BulkAddToCaseResponse addToCase(@Valid @RequestBody BulkAddToCaseRequest request) {
        return searchService.addToCase(request);
    }

    @Operation(summary = "Add hand-picked messages to a case",
            description = "Files exactly the given message ids as evidence. Unlike /add-to-case, no " +
                    "search is re-run: the ids the reviewer ticked are the decision, verbatim.")
    @PostMapping("/add-selected-to-case")
    public BulkAddToCaseResponse addSelectedToCase(@Valid @RequestBody AddSelectedToCaseRequest request) {
        return searchService.addSelectedToCase(request);
    }
}
