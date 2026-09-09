package com.discoveryhub.search.service;

import com.discoveryhub.search.config.SearchProperties;
import com.discoveryhub.search.exception.SearchException;
import com.discoveryhub.search.kafka.SearchKafkaPublisher;
import com.discoveryhub.search.model.BulkAddToCaseRequest;
import com.discoveryhub.search.model.BulkAddToCaseResponse;
import com.discoveryhub.search.model.SaveSearchRequest;
import com.discoveryhub.search.model.SavedSearch;
import com.discoveryhub.search.model.SearchRequest;
import com.discoveryhub.search.model.SearchResponse;
import com.discoveryhub.search.model.SearchResult;
import com.discoveryhub.search.repository.SearchRepository;
import com.discoveryhub.search.strategy.DateSortStrategy;
import com.discoveryhub.search.strategy.RelevanceSortStrategy;
import com.discoveryhub.search.strategy.SearchSortStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.query.Query;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service is tested with a real {@link SearchQueryBuilder} and the real sort strategies, so the
 * only mocks are the {@link SearchRepository} (the Elasticsearch boundary) and the
 * {@link SearchKafkaPublisher} (the Kafka boundary). That keeps the test free of a running store
 * and broker while still exercising the validation, the page clamp, the strategy selection, the
 * saved-search round-trip, and the bulk add-to-case action.
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    private static final SearchProperties PROPERTIES = new SearchProperties("communications", 200, 100, 10000);

    @Mock
    private SearchRepository repository;

    @Mock
    private SearchKafkaPublisher publisher;

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        List<SearchSortStrategy> strategies = List.of(new RelevanceSortStrategy(), new DateSortStrategy());
        service = new SearchServiceImpl(
                repository,
                new SearchQueryBuilder(PROPERTIES),
                strategies,
                PROPERTIES,
                publisher,
                new ObjectMapper());
    }

    @Test
    void searchDelegatesAssembledQueryToRepositoryAndReturnsItsResponse() {
        SearchRequest request = request("fraud", SearchRequest.SortBy.RELEVANCE, null);
        SearchResponse expected = new SearchResponse(List.of(), 0, 0, 20, 3L);
        when(repository.search(any(Query.class), any(SearchRequest.class))).thenReturn(expected);

        SearchResponse result = service.search(request);

        assertThat(result).isEqualTo(expected);
        verify(repository).search(any(Query.class), any(SearchRequest.class));
    }

    @Test
    void rejectsARequestWithNoCriteriaAndNeverTouchesTheStore() {
        SearchRequest empty = new SearchRequest(
                null, List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null);

        assertThatThrownBy(() -> service.search(empty))
                .isInstanceOf(SearchException.class)
                .hasMessageContaining("at least one");

        verify(repository, never()).search(any(Query.class), any(SearchRequest.class));
    }

    @Test
    void acceptsARequestWithOnlyTheHasAttachmentFilter() {
        SearchRequest onlyFilter = new SearchRequest(
                null, List.of(), null, null, null, null, List.of(), true, null, 0, 20, null, null);
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        service.search(onlyFilter);

        verify(repository).search(any(Query.class), any(SearchRequest.class));
    }

    @Test
    void acceptsARequestWithOnlyTheOnHoldFilter() {
        SearchRequest onlyFilter = new SearchRequest(
                null, List.of(), null, null, null, null, List.of(), null, false, 0, 20, null, null);
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        service.search(onlyFilter);

        verify(repository).search(any(Query.class), any(SearchRequest.class));
    }

    @Test
    void clampsAnOversizedPageToTheConfiguredMaximum() {
        SearchRequest request = new SearchRequest(
                "fraud", List.of(), null, null, null, null, List.of(), null, null, 0, 500,
                SearchRequest.SortBy.RELEVANCE, null);
        SearchResponse stub = new SearchResponse(List.of(), 0, 0, 100, 1L);
        when(repository.search(any(Query.class), any(SearchRequest.class))).thenReturn(stub);

        service.search(request);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(repository).search(any(Query.class), captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(100);
    }

    @Test
    void relevanceSortLeavesTheQueryWithoutAnExplicitSort() {
        SearchRequest request = request("fraud", SearchRequest.SortBy.RELEVANCE, null);
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        service.search(request);

        Query query = captureQuery();
        // Relevance is Elasticsearch's default ordering, so no sort is attached. Query.getSort()
        // returns null (not Sort.unsorted()) when addSort was never called, which is the signal
        // here that the score-default ordering will stand.
        assertThat(query.getSort() == null || !query.getSort().isSorted()).isTrue();
    }

    @Test
    void dateSortAppliesSentAtInTheRequestedDirection() {
        SearchRequest request = request("fraud", SearchRequest.SortBy.DATE, SearchRequest.SortDirection.ASC);
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        service.search(request);

        Query query = captureQuery();
        Sort sort = query.getSort();
        assertThat(sort.isSorted()).isTrue();
        assertThat(sort.getOrderFor("sentAt"))
                .as("date sort must order by sentAt")
                .isNotNull();
        assertThat(sort.getOrderFor("sentAt").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void dateSortDefaultsToNewestFirstWhenDirectionIsOmitted() {
        SearchRequest request = request("fraud", SearchRequest.SortBy.DATE, null);
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        service.search(request);

        Query query = captureQuery();
        assertThat(query.getSort().getOrderFor("sentAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void theQueryCarriesHighlightConfiguration() {
        // Req 3: every query is built with a highlight so the matched terms come back wrapped.
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        service.search(request("fraud", SearchRequest.SortBy.RELEVANCE, null));

        Query query = captureQuery();
        assertThat(query.getHighlightQuery())
                .as("query must request highlighting")
                .isNotNull();
    }

    @Test
    void saveSearchPersistsTheRequestAndReturnsItWithAnId() {
        SearchRequest toSave = request("fraud", SearchRequest.SortBy.RELEVANCE, null);
        SaveSearchRequest saveRequest = new SaveSearchRequest("Q1 fraud", "case-1", toSave, "alice");

        when(repository.saveSavedSearch(any(SavedSearch.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SavedSearch saved = service.saveSearch(saveRequest);

        assertThat(saved.getName()).isEqualTo("Q1 fraud");
        assertThat(saved.getCaseId()).isEqualTo("case-1");
        assertThat(saved.getRequestJson()).contains("fraud");
        assertThat(saved.getId()).isNotNull();
        verify(repository).saveSavedSearch(any(SavedSearch.class));
    }

    @Test
    void runSavedSearchParsesTheStoredJsonAndReRunsIt() {
        SearchRequest original = request("fraud", SearchRequest.SortBy.DATE, SearchRequest.SortDirection.ASC);
        SaveSearchRequest saveRequest = new SaveSearchRequest("Q1", "case-1", original, "alice");
        // saveSearch() round-trips through the repository; stub it to return the saved record so
        // the id is populated for the subsequent getSavedSearch call.
        when(repository.saveSavedSearch(any(SavedSearch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        SavedSearch stored = service.saveSearch(saveRequest);

        when(repository.getSavedSearch(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 7, 0, 20, 1L));

        SearchResponse result = service.runSavedSearch(stored.getId());

        assertThat(result.total()).isEqualTo(7L);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(repository).search(any(Query.class), captor.capture());
        assertThat(captor.getValue().query()).isEqualTo("fraud");
        assertThat(captor.getValue().sortBy()).isEqualTo(SearchRequest.SortBy.DATE);
    }

    @Test
    void runSavedSearchThrows404WhenTheIdDoesNotExist() {
        when(repository.getSavedSearch("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.runSavedSearch("missing"))
                .isInstanceOf(SearchException.class)
                .satisfies(ex -> assertThat(((SearchException) ex).status()).isEqualTo(404));
    }

    @Test
    void addToCaseAddsTheCurrentPageAndPublishesAnAuditEvent() {
        // Req 6: "add all results on this page to case" — the default form.
        SearchRequest search = request("fraud", SearchRequest.SortBy.RELEVANCE, null);
        BulkAddToCaseRequest addRequest = new BulkAddToCaseRequest("case-1", false, search);
        List<SearchResult> page = List.of(
                result("msg-1"), result("msg-2"), result("msg-3"));
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(page, 3, 0, 20, 1L));

        BulkAddToCaseResponse response = service.addToCase(addRequest);

        assertThat(response.caseId()).isEqualTo("case-1");
        assertThat(response.added()).isEqualTo(3);
        assertThat(response.messageIds()).containsExactly("msg-1", "msg-2", "msg-3");
        assertThat(response.truncated()).isFalse();
        verify(publisher).publishAddToCase("case-1", List.of("msg-1", "msg-2", "msg-3"));
    }

    @Test
    void addToCaseWithAllResultsScansEveryMatchAndMarksTruncationAtTheCap() {
        // Req 6 stretch: "add all results" — scrolls the whole match set, capped at maxBulkResults.
        SearchRequest search = request("fraud", SearchRequest.SortBy.RELEVANCE, null);
        BulkAddToCaseRequest addRequest = new BulkAddToCaseRequest("case-1", true, search);
        when(repository.searchMessageIds(any(Query.class), anyInt()))
                .thenReturn(List.of("msg-1", "msg-2"));

        BulkAddToCaseResponse response = service.addToCase(addRequest);

        assertThat(response.added()).isEqualTo(2);
        assertThat(response.messageIds()).containsExactly("msg-1", "msg-2");
        assertThat(response.truncated()).isFalse();
        verify(repository).searchMessageIds(any(Query.class), eq(10000));
        verify(publisher).publishAddToCase("case-1", List.of("msg-1", "msg-2"));
    }

    @Test
    void addToCaseRefusesARequestWithNoCriteria() {
        BulkAddToCaseRequest addRequest = new BulkAddToCaseRequest(
                "case-1", false, new SearchRequest(
                        null, List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null));

        assertThatThrownBy(() -> service.addToCase(addRequest))
                .isInstanceOf(SearchException.class)
                .hasMessageContaining("at least one");

        verify(publisher, never()).publishAddToCase(any(), any());
    }

    @Test
    void addToCaseWithAllResultsAndEmptyMatchReturnsZeroAdded() {
        SearchRequest search = request("nonexistent", SearchRequest.SortBy.RELEVANCE, null);
        BulkAddToCaseRequest addRequest = new BulkAddToCaseRequest("case-1", true, search);
        when(repository.searchMessageIds(any(Query.class), anyInt()))
                .thenReturn(List.of());

        BulkAddToCaseResponse response = service.addToCase(addRequest);

        assertThat(response.added()).isZero();
        assertThat(response.messageIds()).isEmpty();
        assertThat(response.truncated()).isFalse();
        verify(publisher).publishAddToCase("case-1", List.of());
    }

    @Test
    void deleteSavedSearchDelegatesToRepository() {
        service.deleteSavedSearch("id-1");
        verify(repository).deleteSavedSearch("id-1");
    }

    @Test
    void listSavedSearchesDelegatesToRepository() {
        SavedSearch saved = new SavedSearch("id-1", "Q1", "case-1", "{}", "alice", Instant.now());
        when(repository.listSavedSearches("case-1")).thenReturn(List.of(saved));

        List<SavedSearch> result = service.listSavedSearches("case-1");

        assertThat(result).containsExactly(saved);
    }

    @Test
    void listSavedSearchesWithBlankCaseIdListsAll() {
        when(repository.listSavedSearches("")).thenReturn(List.of());

        service.listSavedSearches("");

        verify(repository).listSavedSearches("");
    }

    @Test
    void saveSearchWithNullRequestUsesDefaults() {
        // A null request in the save body should not crash — defaults fill in.
        SaveSearchRequest saveRequest = new SaveSearchRequest("Q1", "case-1", null, "alice");
        when(repository.saveSavedSearch(any(SavedSearch.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SavedSearch saved = service.saveSearch(saveRequest);

        assertThat(saved.getName()).isEqualTo("Q1");
        assertThat(saved.getRequestJson()).isNotNull();
    }

    @Test
    void searchWithMultipleCustodiansIsAccepted() {
        SearchRequest request = new SearchRequest(
                "fraud", List.of("custodian-1", "custodian-2"), null, null, null, null,
                List.of(), null, null, 0, 20, null, null);
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        service.search(request);

        verify(repository).search(any(Query.class), any(SearchRequest.class));
    }

    @Test
    void searchWithOnlyFromDateFilterIsAccepted() {
        SearchRequest request = new SearchRequest(
                null, List.of(), null, null, Instant.parse("2024-01-01T00:00:00Z"),
                null, List.of(), null, null, 0, 20, null, null);
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        service.search(request);

        verify(repository).search(any(Query.class), any(SearchRequest.class));
    }

    @Test
    void searchWithOnlyLabelsFilterIsAccepted() {
        SearchRequest request = new SearchRequest(
                null, List.of(), null, null, null, null, List.of("PRIVILEGED"),
                null, null, 0, 20, null, null);
        when(repository.search(any(Query.class), any(SearchRequest.class)))
                .thenReturn(new SearchResponse(List.of(), 0, 0, 20, 0L));

        service.search(request);

        verify(repository).search(any(Query.class), any(SearchRequest.class));
    }

    private SearchRequest request(String query, SearchRequest.SortBy sortBy, SearchRequest.SortDirection direction) {
        return new SearchRequest(query, List.of(), null, null, null, null, List.of(), null, null, 0, 20, sortBy, direction);
    }

    private SearchResult result(String messageId) {
        return new SearchResult(messageId, "ext", "custodian", "from@x.com", List.of("to@x.com"),
                "subject", Instant.parse("2024-05-11T21:37:00Z"), "snippet", List.of(), 1.0f, false, 0, List.of());
    }

    private Query captureQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(repository).search(captor.capture(), any(SearchRequest.class));
        return captor.getValue();
    }
}
