package com.discoveryhub.search.repository;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.search.config.SearchProperties;
import com.discoveryhub.search.mapper.CommunicationDocumentMapper;
import com.discoveryhub.search.model.CommunicationDocument;
import com.discoveryhub.search.model.SavedSearch;
import com.discoveryhub.search.model.SearchRequest;
import com.discoveryhub.search.model.SearchResponse;
import com.discoveryhub.search.model.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The repository is the only class that touches Spring Data Elasticsearch, so this is the only test
 * that has to know its types. {@link ElasticsearchOperations} is mocked, so no cluster is needed;
 * the assertions are about delegation — the right call is made and the result is mapped — not about
 * what Elasticsearch itself would do with the query.
 */
@ExtendWith(MockitoExtension.class)
class ElasticsearchSearchRepositoryTest {

    @Mock
    private ElasticsearchOperations operations;

    @Mock
    private CommunicationDocumentMapper mapper;

    private ElasticsearchSearchRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ElasticsearchSearchRepository(
                operations, mapper, new SearchProperties("communications", 200, 100, 10000));
    }

    @Test
    void searchExecutesTheQueryAndMapsEachHit() {
        Query query = mock(Query.class);
        SearchRequest request = new SearchRequest(
                "fraud", List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null);

        CommunicationDocument doc = document("msg-1");
        SearchResult result = new SearchResult(
                "msg-1", "ext-1", "custodian-1", "from@firm.test", List.of("to@firm.test"),
                "subject", Instant.parse("2024-05-11T21:37:00Z"), "fraud", List.of(), 1.5f, false, 0, List.of());

        @SuppressWarnings("unchecked")
        SearchHits<CommunicationDocument> hits = mock(SearchHits.class);
        @SuppressWarnings("unchecked")
        SearchHit<CommunicationDocument> hit = mock(SearchHit.class);
        when(operations.search(eq(query), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(hits);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(hits.getTotalHits()).thenReturn(1L);
        when(hit.getContent()).thenReturn(doc);
        when(hit.getScore()).thenReturn(1.5f);
        when(hit.getHighlightFields()).thenReturn(Map.of("body", List.of("<em>fraud</em>")));
        when(mapper.toSearchResult(eq(doc), eq(1.5f), eq("fraud"), any())).thenReturn(result);

        SearchResponse response = repository.search(query, request);

        assertThat(response.total()).isEqualTo(1L);
        assertThat(response.results()).containsExactly(result);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.tookMs()).isNotNegative();
        verify(mapper).toSearchResult(eq(doc), eq(1.5f), eq("fraud"), any());
    }

    @Test
    void searchWithNoHitsReturnsAnEmptyPage() {
        Query query = mock(Query.class);
        SearchRequest request = new SearchRequest(
                "fraud", List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null);

        @SuppressWarnings("unchecked")
        SearchHits<CommunicationDocument> hits = mock(SearchHits.class);
        when(operations.search(eq(query), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(hits);
        when(hits.getSearchHits()).thenReturn(List.of());
        when(hits.getTotalHits()).thenReturn(0L);

        SearchResponse response = repository.search(query, request);

        assertThat(response.results()).isEmpty();
        assertThat(response.total()).isZero();
    }

    @Test
    void indexSavesTheDocumentToTheConfiguredIndex() {
        CommunicationDocument doc = document("msg-1");
        when(operations.get(eq("msg-1"), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(null);

        repository.index(doc);

        verify(operations).save(eq(doc), any(IndexCoordinates.class));
    }

    @Test
    void indexDoesNotClobberAnOnHoldFlagAlreadySetOnAReindex() {
        // C2 regression: a re-index (consumer restart replaying messages.archived, a redelivery)
        // must not reset onHold to the mapper's default of false and lose what setHold wrote.
        CommunicationDocument incoming = document("msg-1");
        assertThat(incoming.isOnHold()).isFalse();
        CommunicationDocument existing = document("msg-1");
        existing.setOnHold(true);
        Instant holdUpdatedAt = Instant.parse("2024-06-01T00:00:00Z");
        existing.setHoldUpdatedAt(holdUpdatedAt);
        when(operations.get(eq("msg-1"), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(existing);

        repository.index(incoming);

        assertThat(incoming.isOnHold()).isTrue();
        assertThat(incoming.getHoldUpdatedAt()).isEqualTo(holdUpdatedAt);
        verify(operations).save(eq(incoming), any(IndexCoordinates.class));
    }

    @Test
    void indexOfABrandNewDocumentLeavesOnHoldFalse() {
        CommunicationDocument doc = document("msg-new");
        when(operations.get(eq("msg-new"), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(null);

        repository.index(doc);

        assertThat(doc.isOnHold()).isFalse();
        verify(operations).save(eq(doc), any(IndexCoordinates.class));
    }

    @Test
    void setHoldFetchesUpdatesAndSavesTheDocument() {
        CommunicationDocument doc = document("msg-1");
        assertThat(doc.isOnHold()).isFalse();
        when(operations.get(eq("msg-1"), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(doc);
        Instant occurredAt = Instant.parse("2024-06-01T00:00:00Z");

        repository.setHold("msg-1", true, occurredAt);

        assertThat(doc.isOnHold()).isTrue();
        assertThat(doc.getHoldUpdatedAt()).isEqualTo(occurredAt);
        verify(operations).save(eq(doc), any(IndexCoordinates.class));
    }

    @Test
    void setHoldIsANoopWhenTheMessageIsNotIndexed() {
        when(operations.get(eq("missing"), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(null);

        repository.setHold("missing", true, Instant.now());

        verify(operations, never()).save(any(), any(IndexCoordinates.class));
    }

    @Test
    void setHoldIgnoresAStaleEventOlderThanTheStoredHoldUpdatedAt() {
        // M1 regression: an out-of-order redelivery (e.g. a rebalance replaying an older event
        // after a newer one already applied) must not un-hold or re-hold a message.
        CommunicationDocument doc = document("msg-1");
        doc.setOnHold(true);
        Instant newer = Instant.parse("2024-06-02T00:00:00Z");
        doc.setHoldUpdatedAt(newer);
        when(operations.get(eq("msg-1"), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(doc);
        Instant staleEvent = Instant.parse("2024-06-01T00:00:00Z");

        repository.setHold("msg-1", false, staleEvent);

        assertThat(doc.isOnHold()).isTrue();
        assertThat(doc.getHoldUpdatedAt()).isEqualTo(newer);
        verify(operations, never()).save(any(), any(IndexCoordinates.class));
    }

    @Test
    void setHoldAppliesAnEventNewerThanTheStoredHoldUpdatedAt() {
        CommunicationDocument doc = document("msg-1");
        doc.setOnHold(true);
        Instant older = Instant.parse("2024-06-01T00:00:00Z");
        doc.setHoldUpdatedAt(older);
        when(operations.get(eq("msg-1"), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(doc);
        Instant newerEvent = Instant.parse("2024-06-02T00:00:00Z");

        repository.setHold("msg-1", false, newerEvent);

        assertThat(doc.isOnHold()).isFalse();
        assertThat(doc.getHoldUpdatedAt()).isEqualTo(newerEvent);
        verify(operations).save(eq(doc), any(IndexCoordinates.class));
    }

    @Test
    void setHoldByCustodianStreamsRatherThanLoadingTheWholeMailboxAsOnePage() {
        // M2 regression: setHoldByCustodian must scroll (searchForStream), the same as
        // searchMessageIds does, rather than materialising every hit via operations.search.
        CommunicationDocument doc = document("msg-1");
        assertThat(doc.isOnHold()).isFalse();

        @SuppressWarnings("unchecked")
        SearchHitsIterator<CommunicationDocument> stream = mock(SearchHitsIterator.class);
        when(operations.searchForStream(any(Query.class), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(stream);
        @SuppressWarnings("unchecked")
        SearchHit<CommunicationDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(doc);
        when(stream.hasNext()).thenReturn(true, false);
        when(stream.next()).thenReturn(hit);

        repository.setHoldByCustodian("custodian-1", true, Instant.parse("2024-06-01T00:00:00Z"));

        assertThat(doc.isOnHold()).isTrue();
        verify(operations).save(eq(doc), any(IndexCoordinates.class));
        verify(operations, never()).search(any(Query.class), eq(CommunicationDocument.class), any(IndexCoordinates.class));
        verify(stream).close();
    }

    @Test
    void deleteByMessageIdRemovesTheDocumentFromTheIndex() {
        repository.deleteByMessageId("msg-1");

        verify(operations).delete(eq("msg-1"), any(IndexCoordinates.class));
    }

    @Test
    void saveSavedSearchDelegatesToOperations() {
        SavedSearch saved = new SavedSearch("id-1", "name", "case-1", "{}", "alice", Instant.now());
        when(operations.save(eq(saved), any(IndexCoordinates.class))).thenReturn(saved);

        SavedSearch result = repository.saveSavedSearch(saved);

        assertThat(result).isEqualTo(saved);
        verify(operations).save(eq(saved), any(IndexCoordinates.class));
    }

    @Test
    void getSavedSearchReturnsTheStoredRecordOrEmpty() {
        SavedSearch saved = new SavedSearch("id-1", "name", "case-1", "{}", "alice", Instant.now());
        when(operations.get(eq("id-1"), eq(SavedSearch.class), any(IndexCoordinates.class)))
                .thenReturn(saved);
        when(operations.get(eq("missing"), eq(SavedSearch.class), any(IndexCoordinates.class)))
                .thenReturn(null);

        assertThat(repository.getSavedSearch("id-1")).contains(saved);
        assertThat(repository.getSavedSearch("missing")).isEmpty();
    }

    @Test
    void listSavedSearchesByCaseQueriesTheSavedSearchIndex() {
        SavedSearch saved = new SavedSearch("id-1", "name", "case-1", "{}", "alice", Instant.now());
        @SuppressWarnings("unchecked")
        SearchHits<SavedSearch> hits = mock(SearchHits.class);
        @SuppressWarnings("unchecked")
        SearchHit<SavedSearch> hit = mock(SearchHit.class);
        when(operations.search(any(Query.class), eq(SavedSearch.class), any(IndexCoordinates.class)))
                .thenReturn(hits);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(hit.getContent()).thenReturn(saved);

        List<SavedSearch> result = repository.listSavedSearches("case-1");

        assertThat(result).containsExactly(saved);
    }

    @Test
    void deleteSavedSearchRemovesById() {
        repository.deleteSavedSearch("id-1");

        verify(operations).delete(eq("id-1"), any(IndexCoordinates.class));
    }

    @Test
    void searchMessageIdsStreamsEveryMatchUpToTheCap() {
        Query query = mock(Query.class);
        @SuppressWarnings("unchecked")
        SearchHitsIterator<CommunicationDocument> stream = mock(SearchHitsIterator.class);
        when(operations.searchForStream(eq(query), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(stream);
        // Two documents then end of stream. The hit stubs are built before the iterator stubbing
        // so Mockito does not see an unfinished when() chain mid-iteration.
        SearchHit<CommunicationDocument> hit1 = hitFor("msg-1");
        SearchHit<CommunicationDocument> hit2 = hitFor("msg-2");
        when(stream.hasNext()).thenReturn(true, true, false);
        when(stream.next()).thenReturn(hit1, hit2);

        List<String> ids = repository.searchMessageIds(query, 100);

        assertThat(ids).containsExactly("msg-1", "msg-2");
        verify(stream).close();
    }

    @Test
    void searchMessageIdsStopsAtTheCap() {
        Query query = mock(Query.class);
        @SuppressWarnings("unchecked")
        SearchHitsIterator<CommunicationDocument> stream = mock(SearchHitsIterator.class);
        when(operations.searchForStream(eq(query), eq(CommunicationDocument.class), any(IndexCoordinates.class)))
                .thenReturn(stream);
        // The cap is 2, so only two hits are consumed; the third would be an unnecessary stub.
        SearchHit<CommunicationDocument> hit1 = hitFor("msg-1");
        SearchHit<CommunicationDocument> hit2 = hitFor("msg-2");
        when(stream.hasNext()).thenReturn(true, true, true);
        when(stream.next()).thenReturn(hit1, hit2);

        List<String> ids = repository.searchMessageIds(query, 2);

        assertThat(ids).containsExactly("msg-1", "msg-2");
        verify(stream).close();
    }

    private static CommunicationDocument document(String messageId) {
        return new CommunicationDocument(
                messageId, "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1", "from@firm.test",
                List.of("to@firm.test"), List.of(), "subject", "the body talks about fraud here",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, 0, List.of(), List.of(), false);
    }

    @SuppressWarnings("unchecked")
    private static SearchHit<CommunicationDocument> hitFor(String messageId) {
        SearchHit<CommunicationDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(document(messageId));
        return hit;
    }
}
