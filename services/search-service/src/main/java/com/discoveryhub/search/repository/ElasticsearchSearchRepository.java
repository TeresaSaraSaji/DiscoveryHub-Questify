package com.discoveryhub.search.repository;

import com.discoveryhub.search.config.SearchProperties;
import com.discoveryhub.search.mapper.CommunicationDocumentMapper;
import com.discoveryhub.search.model.CommunicationDocument;
import com.discoveryhub.search.model.SavedSearch;
import com.discoveryhub.search.model.SearchRequest;
import com.discoveryhub.search.model.SearchResponse;
import com.discoveryhub.search.model.SearchResult;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Elasticsearch-backed {@link SearchRepository}. All of Spring Data Elasticsearch lives behind
 * this class — nothing upstream imports an Elasticsearch type.
 *
 * <p>Hold updates are read-modify-write against the whole document rather than a partial update,
 * because a partial-update API is the one place Spring Data Elasticsearch reshuffles between
 * versions, and a hold event is rare enough that the extra fetch is not the hot path. The
 * guarantee that a held message is never lost lives in P4 and P2, not here — this flag is display.
 *
 * <p>Saved searches live in their own index ({@code saved-searches}); the bulk "add all" scan uses
 * a scroll so a large match set does not load into one page.
 */
@Component
public class ElasticsearchSearchRepository implements SearchRepository {

    private static final int STREAM_BATCH = 1000;

    private final ElasticsearchOperations operations;
    private final CommunicationDocumentMapper mapper;
    private final SearchProperties properties;

    public ElasticsearchSearchRepository(ElasticsearchOperations operations,
                                        CommunicationDocumentMapper mapper,
                                        SearchProperties properties) {
        this.operations = operations;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public SearchResponse search(Query query, SearchRequest request) {
        long start = System.nanoTime();
        SearchHits<CommunicationDocument> hits = operations.search(
                query, CommunicationDocument.class, index());
        long tookMs = (System.nanoTime() - start) / 1_000_000L;

        List<SearchResult> results = hits.getSearchHits().stream()
                .map(hit -> mapper.toSearchResult(
                        hit.getContent(), hit.getScore(), request.query(), hit.getHighlightFields()))
                .toList();

        return new SearchResponse(results, hits.getTotalHits(), request.page(), request.size(), tookMs);
    }

    @Override
    public void index(CommunicationDocument document) {
        operations.save(document, index());
    }

    @Override
    public void setHold(String messageId, boolean onHold) {
        CommunicationDocument doc = operations.get(messageId, CommunicationDocument.class, index());
        if (doc != null) {
            doc.setOnHold(onHold);
            operations.save(doc, index());
        }
    }

    @Override
    public void setHoldByCustodian(String custodianId, boolean onHold) {
        CriteriaQuery byCustodian = new CriteriaQuery(new Criteria("custodianId").is(custodianId));
        SearchHits<CommunicationDocument> hits = operations.search(
                byCustodian, CommunicationDocument.class, index());
        for (SearchHit<CommunicationDocument> hit : hits.getSearchHits()) {
            CommunicationDocument doc = hit.getContent();
            doc.setOnHold(onHold);
            operations.save(doc, index());
        }
    }

    @Override
    public void deleteByMessageId(String messageId) {
        operations.delete(messageId, index());
    }

    @Override
    public SavedSearch saveSavedSearch(SavedSearch savedSearch) {
        return operations.save(savedSearch, savedSearchIndex());
    }

    @Override
    public Optional<SavedSearch> getSavedSearch(String id) {
        return Optional.ofNullable(operations.get(id, SavedSearch.class, savedSearchIndex()));
    }

    @Override
    public List<SavedSearch> listSavedSearches(String caseId) {
        CriteriaQuery query = (caseId == null || caseId.isBlank())
                ? new CriteriaQuery(new Criteria()) // match all
                : new CriteriaQuery(new Criteria("caseId").is(caseId));
        SearchHits<SavedSearch> hits = operations.search(query, SavedSearch.class, savedSearchIndex());
        return hits.getSearchHits().stream().map(SearchHit::getContent).toList();
    }

    @Override
    public void deleteSavedSearch(String id) {
        operations.delete(id, savedSearchIndex());
    }

    @Override
    public List<String> searchMessageIds(Query query, int max) {
        List<String> ids = new ArrayList<>();
        try (SearchHitsIterator<CommunicationDocument> stream =
                     operations.searchForStream(query, CommunicationDocument.class, index())) {
            while (stream.hasNext() && ids.size() < max) {
                ids.add(stream.next().getContent().getMessageId());
            }
        }
        return ids;
    }

    private IndexCoordinates index() {
        return IndexCoordinates.of(properties.indexName());
    }

    private IndexCoordinates savedSearchIndex() {
        return IndexCoordinates.of("saved-searches");
    }
}
