package com.discoveryhub.search.controller;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.search.exception.SearchException;
import com.discoveryhub.search.model.BulkAddToCaseRequest;
import com.discoveryhub.search.model.BulkAddToCaseResponse;
import com.discoveryhub.search.model.SaveSearchRequest;
import com.discoveryhub.search.model.SavedSearch;
import com.discoveryhub.search.model.SearchRequest;
import com.discoveryhub.search.model.SearchResponse;
import com.discoveryhub.search.model.SearchResult;
import com.discoveryhub.search.service.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The controller is tested as a plain unit, the same way {@code IngestControllerTest} in P1 is: the
 * controller is constructed directly with a mocked {@link SearchService}, and the assertions are
 * about what the controller delegates and how it shapes the GET form into a {@link SearchRequest}.
 *
 * <p>Bean-validation enforcement ({@code @Valid}) is Spring's binding layer, an integration concern
 * the way P2's unique-constraint path is in {@code ArchiveServiceTest}; the unit test covers the
 * controller's own logic. The {@code GlobalExceptionHandler} mapping is exercised through the same
 * {@link SearchException} the service throws, so a handler test would only re-prove the mapping.
 */
@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private SearchService searchService;

    @InjectMocks
    private SearchController controller;

    @Test
    void postSearchDelegatesTheRequestBodyAndReturnsTheServiceResponse() {
        SearchRequest request = new SearchRequest(
                "fraud", List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null);
        SearchResponse expected = new SearchResponse(List.of(), 0, 0, 20, 3L);
        when(searchService.search(any(SearchRequest.class))).thenReturn(expected);

        SearchResponse result = controller.search(request);

        assertThat(result).isEqualTo(expected);
        verify(searchService).search(request);
    }

    @Test
    void getSearchBuildsARequestFromQueryParamsAndDelegatesIt() {
        SearchResponse expected = new SearchResponse(List.of(), 5, 0, 20, 1L);
        when(searchService.search(any(SearchRequest.class))).thenReturn(expected);

        SearchResponse result = controller.search(
                "fraud", List.of("custodian-1"), MessageType.EMAIL, "from@firm.test",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T00:00:00Z"),
                List.of("PRIVILEGED"), true, false, 1, 50,
                SearchRequest.SortBy.DATE, SearchRequest.SortDirection.ASC);

        assertThat(result).isEqualTo(expected);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(searchService).search(captor.capture());
        SearchRequest sent = captor.getValue();
        assertThat(sent.query()).isEqualTo("fraud");
        assertThat(sent.custodianIds()).containsExactly("custodian-1");
        assertThat(sent.type()).isEqualTo(MessageType.EMAIL);
        assertThat(sent.from()).isEqualTo("from@firm.test");
        assertThat(sent.sentAfter()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(sent.sentBefore()).isEqualTo(Instant.parse("2024-12-31T00:00:00Z"));
        assertThat(sent.labels()).containsExactly("PRIVILEGED");
        assertThat(sent.hasAttachment()).isTrue();
        assertThat(sent.onHold()).isFalse();
        assertThat(sent.page()).isEqualTo(1);
        assertThat(sent.size()).isEqualTo(50);
        assertThat(sent.sortBy()).isEqualTo(SearchRequest.SortBy.DATE);
        assertThat(sent.sortDirection()).isEqualTo(SearchRequest.SortDirection.ASC);
    }

    @Test
    void getSearchAppliesDefaultsForEverythingOmitted() {
        when(searchService.search(any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        controller.search("fraud", null, null, null, null, null, null, null, null, null, null, null, null);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(searchService).search(captor.capture());
        SearchRequest sent = captor.getValue();
        // Defaults come from SearchRequest's compact constructor, so a caller sending only the
        // query still gets a valid first page in the default sort order.
        assertThat(sent.page()).isZero();
        assertThat(sent.size()).isEqualTo(20);
        assertThat(sent.sortBy()).isEqualTo(SearchRequest.SortBy.RELEVANCE);
        assertThat(sent.sortDirection()).isEqualTo(SearchRequest.SortDirection.DESC);
        assertThat(sent.custodianIds()).isEmpty();
        assertThat(sent.labels()).isEmpty();
        assertThat(sent.hasAttachment()).isNull();
        assertThat(sent.onHold()).isNull();
    }

    @Test
    void aServiceRefusalPropagatesSoTheHandlerCanMapIt() {
        when(searchService.search(any(SearchRequest.class))).thenThrow(
                new SearchException("at least one criterion is required", 400));

        // The controller does not swallow a SearchException; the GlobalExceptionHandler turns it
        // into a 400. Asserting it propagates is asserting the handler will see it.
        assertThatThrownBy(() -> controller.search(
                null, null, null, null, null, null, null, null, null, 0, 20, null, null))
                .isInstanceOf(SearchException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void saveSearchDelegatesToTheService() {
        SaveSearchRequest save = new SaveSearchRequest("Q1", "case-1",
                new SearchRequest("fraud", List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null),
                "alice");
        SavedSearch saved = new SavedSearch("id-1", "Q1", "case-1", "{}", "alice", Instant.now());
        when(searchService.saveSearch(save)).thenReturn(saved);

        SavedSearch result = controller.saveSearch(save);

        assertThat(result).isEqualTo(saved);
        verify(searchService).saveSearch(save);
    }

    @Test
    void listSavedSearchesDelegatesToTheService() {
        SavedSearch saved = new SavedSearch("id-1", "Q1", "case-1", "{}", "alice", Instant.now());
        when(searchService.listSavedSearches("case-1")).thenReturn(List.of(saved));

        List<SavedSearch> result = controller.listSavedSearches("case-1");

        assertThat(result).containsExactly(saved);
    }

    @Test
    void getSavedSearchReturnsOkWhenFoundAndNotFoundWhenAbsent() {
        SavedSearch saved = new SavedSearch("id-1", "Q1", "case-1", "{}", "alice", Instant.now());
        when(searchService.getSavedSearch("id-1")).thenReturn(Optional.of(saved));
        when(searchService.getSavedSearch("missing")).thenReturn(Optional.empty());

        ResponseEntity<SavedSearch> found = controller.getSavedSearch("id-1");
        ResponseEntity<SavedSearch> notFound = controller.getSavedSearch("missing");

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).isEqualTo(saved);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteSavedSearchReturnsNoContent() {
        ResponseEntity<Void> result = controller.deleteSavedSearch("id-1");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(searchService).deleteSavedSearch("id-1");
    }

    @Test
    void runSavedSearchDelegatesToTheService() {
        SearchResponse expected = new SearchResponse(List.of(), 3, 0, 20, 1L);
        when(searchService.runSavedSearch("id-1")).thenReturn(expected);

        SearchResponse result = controller.runSavedSearch("id-1");

        assertThat(result).isEqualTo(expected);
        verify(searchService).runSavedSearch("id-1");
    }

    @Test
    void addToCaseDelegatesToTheService() {
        BulkAddToCaseRequest request = new BulkAddToCaseRequest("case-1", false,
                new SearchRequest("fraud", List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null));
        BulkAddToCaseResponse expected = new BulkAddToCaseResponse("case-1", 2, List.of("msg-1", "msg-2"), false);
        when(searchService.addToCase(request)).thenReturn(expected);

        BulkAddToCaseResponse result = controller.addToCase(request);

        assertThat(result).isEqualTo(expected);
        verify(searchService).addToCase(request);
    }

    @Test
    void addToCaseWithAllResultsDelegatesToTheService() {
        BulkAddToCaseRequest request = new BulkAddToCaseRequest("case-42", true,
                new SearchRequest("fraud", List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null));
        BulkAddToCaseResponse expected = new BulkAddToCaseResponse("case-42", 100, List.of("msg-1"), true);
        when(searchService.addToCase(request)).thenReturn(expected);

        BulkAddToCaseResponse result = controller.addToCase(request);

        assertThat(result.added()).isEqualTo(100);
        assertThat(result.truncated()).isTrue();
        verify(searchService).addToCase(request);
    }

    @Test
    void getSavedSearchReturnsNotFoundWhenServiceReturnsEmpty() {
        when(searchService.getSavedSearch("missing")).thenReturn(Optional.empty());

        ResponseEntity<SavedSearch> result = controller.getSavedSearch("missing");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody()).isNull();
    }

    @Test
    void listSavedSearchesWithNoCaseIdDelegatesToTheService() {
        when(searchService.listSavedSearches(null)).thenReturn(List.of());

        List<SavedSearch> result = controller.listSavedSearches(null);

        assertThat(result).isEmpty();
        verify(searchService).listSavedSearches(null);
    }

    @Test
    void deleteSavedSearchAlwaysReturnsNoContent() {
        ResponseEntity<Void> result = controller.deleteSavedSearch("any-id");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(searchService).deleteSavedSearch("any-id");
    }

    @Test
    void runSavedSearchPropagatesServiceExceptionForMissingId() {
        when(searchService.runSavedSearch("missing"))
                .thenThrow(new SearchException("saved search not found: missing", 404));

        assertThatThrownBy(() -> controller.runSavedSearch("missing"))
                .isInstanceOf(SearchException.class)
                .satisfies(ex -> assertThat(((SearchException) ex).status()).isEqualTo(404));
    }
}
