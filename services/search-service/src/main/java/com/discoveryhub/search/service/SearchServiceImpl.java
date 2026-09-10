package com.discoveryhub.search.service;

import com.discoveryhub.search.client.BulkEvidenceResult;
import com.discoveryhub.search.client.CaseEvidenceWriter;
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
import com.discoveryhub.search.strategy.SearchSortStrategy;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link SearchService}. Responsibilities, in order for a live search:
 *
 * <ol>
 *   <li>Validate — a request with no query and no filter would match the whole archive, which is
 *       neither useful nor cheap, so it is refused with a 400 rather than paginated.</li>
 *   <li>Clamp the page size to {@code SearchProperties.maxPageSize} so a caller cannot ask for
 *       size=10000 and pull a mailbox into one response.</li>
 *   <li>Pick a {@link SearchSortStrategy} by the request's {@code sortBy} and apply its
 *       {@link Sort}.</li>
 *   <li>Build the {@link Query} and hand it to the repository, which executes and maps.</li>
 * </ol>
 *
 * <p>Beyond live search it owns the saved-search lifecycle (the {@link SearchRequest} is stored as
 * JSON and parsed back on re-run, so a saved search from an older client still deserialises as long
 * as the additive-fields rule in message-schema.md holds) and the bulk add-to-case action (page
 * form collects ids from the page; all-results form scrolls the whole match set, capped at
 * {@code maxBulkResults}).
 *
 * <p>Spring Data Elasticsearch types ({@link Query}, {@link Sort}) reach only this far — the
 * controller and the tests above it never see them.
 */
@Service
public class SearchServiceImpl implements SearchService {

    private final SearchRepository repository;
    private final SearchQueryBuilder queryBuilder;
    private final List<SearchSortStrategy> strategies;
    private final SearchProperties properties;
    private final SearchKafkaPublisher publisher;
    private final CaseEvidenceWriter caseEvidence;
    private final ObjectMapper json;

    public SearchServiceImpl(SearchRepository repository,
                             SearchQueryBuilder queryBuilder,
                             List<SearchSortStrategy> strategies,
                             SearchProperties properties,
                             SearchKafkaPublisher publisher,
                             CaseEvidenceWriter caseEvidence,
                             ObjectMapper json) {
        this.repository = repository;
        this.queryBuilder = queryBuilder;
        this.strategies = strategies;
        this.properties = properties;
        this.publisher = publisher;
        this.caseEvidence = caseEvidence;
        this.json = json;
    }

    @Override
    public SearchResponse search(SearchRequest request) {
        requireACriterion(request);
        SearchRequest effective = clampPageSize(request);
        Sort sort = selectSort(effective);
        Query query = queryBuilder.build(effective, sort);
        return repository.search(query, effective);
    }

    @Override
    public SavedSearch saveSearch(SaveSearchRequest request) {
        SearchRequest toStore = request.request() == null
                ? new SearchRequest(null, List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null)
                : request.request();
        SavedSearch saved = new SavedSearch(
                UUID.randomUUID().toString(),
                request.name(),
                request.caseId(),
                writeRequest(toStore),
                request.createdBy(),
                Instant.now());
        return repository.saveSavedSearch(saved);
    }

    @Override
    public Optional<SavedSearch> getSavedSearch(String id) {
        return repository.getSavedSearch(id);
    }

    @Override
    public List<SavedSearch> listSavedSearches(String caseId) {
        return repository.listSavedSearches(caseId);
    }

    @Override
    public void deleteSavedSearch(String id) {
        repository.deleteSavedSearch(id);
    }

    @Override
    public SearchResponse runSavedSearch(String id) {
        SavedSearch saved = repository.getSavedSearch(id)
                .orElseThrow(() -> new SearchException("saved search not found: " + id, 404));
        SearchRequest request = readRequest(saved.getRequestJson());
        return search(request);
    }

    @Override
    public BulkAddToCaseResponse addToCase(BulkAddToCaseRequest request) {
        SearchRequest searchRequest = request.request() == null
                ? new SearchRequest(null, List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null)
                : request.request();
        requireACriterion(searchRequest);

        List<String> messageIds;
        boolean truncated = false;
        if (request.allResults()) {
            int batchSize = Math.min(1000, properties.maxBulkResults());
            SearchRequest scrollRequest = withSize(searchRequest, 0, batchSize);
            Query query = queryBuilder.build(scrollRequest, selectSort(scrollRequest));
            messageIds = repository.searchMessageIds(query, properties.maxBulkResults());
            truncated = messageIds.size() >= properties.maxBulkResults();
        } else {
            SearchRequest effective = clampPageSize(searchRequest);
            Sort sort = selectSort(effective);
            Query query = queryBuilder.build(effective, sort);
            SearchResponse response = repository.search(query, effective);
            messageIds = response.results().stream().map(SearchResult::messageId).toList();
        }

        // Finding the messages is only half the action. Case membership is P4's data, so the write
        // goes to P4 — and it goes now, synchronously, so that what is reported below is what
        // actually happened. This call used to be a Kafka event nothing consumed, which meant the
        // response, the audit record and the UI all described a write that never occurred.
        BulkEvidenceResult filed;
        try {
            filed = caseEvidence.fileEvidence(request.caseId(), messageIds, writeRequest(searchRequest));
        } catch (CaseEvidenceWriter.CaseEvidenceException ex) {
            publisher.publishAddToCaseFailed(
                    request.caseId(), messageIds.size(), ex.partial().added(), ex.getMessage());
            // 502, not 500: P3 did its job and a dependency did not. The caller needs to know the
            // messages were *not* filed, which is the whole point of doing the write before
            // answering.
            throw new SearchException(ex.getMessage(), 502);
        }

        publisher.publishAddToCase(request.caseId(), messageIds, filed.added(), filed.alreadyPresent());
        return new BulkAddToCaseResponse(request.caseId(), messageIds.size(), filed.added(),
                filed.alreadyPresent(), messageIds, truncated);
    }

    private void requireACriterion(SearchRequest request) {
        boolean hasText = request.query() != null && !request.query().isBlank();
        boolean hasFilter = !request.custodianIds().isEmpty()
                || request.type() != null
                || (request.from() != null && !request.from().isBlank())
                || !request.labels().isEmpty()
                || request.sentAfter() != null
                || request.sentBefore() != null
                || request.hasAttachment() != null
                || request.onHold() != null;
        if (!hasText && !hasFilter) {
            throw new SearchException(
                    "at least one of query, custodianIds, type, from, labels, sentAfter, "
                            + "sentBefore, hasAttachment or onHold is required",
                    400);
        }
    }

    private SearchRequest clampPageSize(SearchRequest request) {
        if (request.size() <= properties.maxPageSize()) {
            return request;
        }
        return withSize(request, request.page(), properties.maxPageSize());
    }

    private static SearchRequest withSize(SearchRequest request, int page, int size) {
        return new SearchRequest(
                request.query(), request.custodianIds(), request.type(), request.from(),
                request.sentAfter(), request.sentBefore(), request.labels(),
                request.hasAttachment(), request.onHold(),
                page, size, request.sortBy(), request.sortDirection());
    }

    private Sort selectSort(SearchRequest request) {
        return strategies.stream()
                .filter(s -> s.appliesTo() == request.sortBy())
                .findFirst()
                .map(s -> s.sortFor(request))
                .orElse(Sort.unsorted());
    }

    private String writeRequest(SearchRequest request) {
        try {
            return json.writeValueAsString(request);
        } catch (RuntimeException ex) {
            throw new SearchException("failed to serialise saved search request", ex);
        }
    }

    private SearchRequest readRequest(String requestJson) {
        try {
            return json.readValue(requestJson, SearchRequest.class);
        } catch (RuntimeException ex) {
            throw new SearchException("failed to parse saved search request", ex);
        }
    }
}
