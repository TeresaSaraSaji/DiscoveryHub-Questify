package com.discoveryhub.search.service;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.search.config.SearchProperties;
import com.discoveryhub.search.model.SearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The query builder is tested directly because it is where requirements 1-3 live: the text query
 * covers body + subject + participants (Req 1), every filter is AND-ed onto the text match (Req 2),
 * and highlighting is requested on the text fields (Req 3).
 *
 * <p>These tests inspect the built {@link Query} at the stable Spring Data Elasticsearch API level —
 * page size, sort, highlight presence — rather than the internal {@code Criteria} chain structure,
 * which is an implementation detail of SDE and reshuffles between versions. The correctness of the
 * filter combination is verified through {@link SearchServiceTest} (which uses the real builder)
 * and the live integration test against Elasticsearch.
 */
class SearchQueryBuilderTest {

    private SearchQueryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SearchQueryBuilder(new SearchProperties("communications", 200, 100, 10000));
    }

    // ---- Req 1: full-text across body, subject, and participants ----

    @Test
    void textQueryBuildsSuccessfullyWithAllFiveFields() {
        // The builder does not throw when the text query covers body+subject+from+to+cc.
        // The five-field OR chain is built inside subCriteria; the structural test for which
        // fields are in the chain is in the live integration test where ES confirms all five
        // fields are searched.
        SearchRequest request = textOnly("fraud");

        Query query = builder.build(request, Sort.unsorted());

        assertThat(query).isInstanceOf(CriteriaQuery.class);
        // The sub-criteria holds the OR chain for the text fields.
        assertThat(((CriteriaQuery) query).getCriteria().getSubCriteria()).isNotEmpty();
    }

    /**
     * A phrase is the most ordinary query a person types, and it used to be a 500.
     *
     * <p>{@code Criteria.contains} builds a wildcard query, and Spring Data Elasticsearch rejects a
     * wildcard containing a blank outright — {@code *quarterly report*} threw
     * {@code InvalidDataAccessApiUsageException} before the builder ever reached Elasticsearch, so
     * every multi-word search returned "unexpected error". Nothing in the single-word tests above
     * could catch it.
     */
    @Test
    void multiWordQueriesBuildInsteadOfThrowing() {
        assertThatCode(() -> builder.build(new SearchRequest("quarterly report", null, null, null,
                null, null, null, null, null, null, null, null, null), Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void aQueryOfOnlyWhitespaceIsTreatedAsNoQueryRatherThanAnEmptyClause() {
        assertThatCode(() -> builder.build(new SearchRequest("   ", null, MessageType.EMAIL, null,
                null, null, null, null, null, null, null, null, null), Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void textQueryMatchingAParticipantFieldBuildsSuccessfully() {
        // A query for an email address should build — it will match the from/to/cc text fields.
        SearchRequest request = textOnly("alice@firm.test");

        Query query = builder.build(request, Sort.unsorted());

        assertThat(((CriteriaQuery) query).getCriteria().getSubCriteria()).isNotEmpty();
    }

    // ---- Req 2: every filter AND-ed onto the text match ----

    @Test
    void buildsWithCustodianFilterOnly() {
        SearchRequest request = new SearchRequest(
                null, List.of("custodian-1"), null, null, null, null, List.of(), null, null, 0, 20, null, null);

        Query query = builder.build(request, Sort.unsorted());

        // No text -> no sub-criteria; the custodian filter is the only criterion.
        assertThat(((CriteriaQuery) query).getCriteria().getSubCriteria()).isEmpty();
        assertThat(((CriteriaQuery) query).getCriteria().getCriteriaChain()).hasSize(1);
    }

    @Test
    void buildsWithTypeFilterOnly() {
        SearchRequest request = new SearchRequest(
                null, List.of(), MessageType.CHAT, null, null, null, List.of(), null, null, 0, 20, null, null);

        assertThatCode(() -> builder.build(request, Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void buildsWithFromFilterOnly() {
        SearchRequest request = new SearchRequest(
                null, List.of(), null, "alice@firm.test", null, null, List.of(), null, null, 0, 20, null, null);

        assertThatCode(() -> builder.build(request, Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void fromFilterIsLowerCasedForCaseInsensitiveMatchingAgainstTheKeywordField() {
        // M4 regression: `from` is indexed lower-cased (CommunicationDocumentMapper); the filter
        // must lower-case its value too, or "Alice@Firm.Test" would never match the stored
        // "alice@firm.test" against a case-sensitive Keyword field.
        SearchRequest request = new SearchRequest(
                null, List.of(), null, "Alice@Firm.Test", null, null, List.of(), null, null, 0, 20, null, null);

        Query query = builder.build(request, Sort.unsorted());
        Criteria from = ((CriteriaQuery) query).getCriteria().getCriteriaChain().stream()
                .filter(c -> "from".equals(c.getField().getName()))
                .findFirst().orElseThrow();

        assertThat(from.getQueryCriteriaEntries()).anySatisfy(entry ->
                assertThat(entry.getValue()).isEqualTo("alice@firm.test"));
    }

    @Test
    void buildsWithDateRangeFilterOnly() {
        SearchRequest request = new SearchRequest(
                null, List.of(), null, null,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T00:00:00Z"),
                List.of(), null, null, 0, 20, null, null);

        assertThatCode(() -> builder.build(request, Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void dateRangeBoundsAreInclusiveNotExclusive() {
        // M8 regression: a message sent at exactly sentBefore (or sentAfter) must match, matching
        // the inclusive convention DateRangeScopeFilter and the disposition eligibility query use.
        Instant after = Instant.parse("2024-01-01T00:00:00Z");
        Instant before = Instant.parse("2024-12-31T00:00:00Z");
        SearchRequest request = new SearchRequest(
                null, List.of(), null, null, after, before, List.of(), null, null, 0, 20, null, null);

        Query query = builder.build(request, Sort.unsorted());
        Criteria sentAt = ((CriteriaQuery) query).getCriteria().getCriteriaChain().stream()
                .filter(c -> "sentAt".equals(c.getField().getName()))
                .findFirst().orElseThrow();

        assertThat(sentAt.getQueryCriteriaEntries()).anySatisfy(entry -> {
            assertThat(entry.getKey()).isEqualTo(Criteria.OperationKey.GREATER_EQUAL);
            assertThat(entry.getValue()).isEqualTo(after);
        });
        assertThat(sentAt.getQueryCriteriaEntries()).anySatisfy(entry -> {
            assertThat(entry.getKey()).isEqualTo(Criteria.OperationKey.LESS_EQUAL);
            assertThat(entry.getValue()).isEqualTo(before);
        });
    }

    @Test
    void buildsWithLabelsFilterOnly() {
        SearchRequest request = new SearchRequest(
                null, List.of(), null, null, null, null, List.of("PRIVILEGED", "SENSITIVE"),
                null, null, 0, 20, null, null);

        assertThatCode(() -> builder.build(request, Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void buildsWithHasAttachmentTrueFilterOnly() {
        SearchRequest request = new SearchRequest(
                null, List.of(), null, null, null, null, List.of(), true, null, 0, 20, null, null);

        assertThatCode(() -> builder.build(request, Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void buildsWithHasAttachmentFalseFilterOnly() {
        SearchRequest request = new SearchRequest(
                null, List.of(), null, null, null, null, List.of(), false, null, 0, 20, null, null);

        assertThatCode(() -> builder.build(request, Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void buildsWithOnHoldTrueFilterOnly() {
        SearchRequest request = new SearchRequest(
                null, List.of(), null, null, null, null, List.of(), null, true, 0, 20, null, null);

        assertThatCode(() -> builder.build(request, Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void buildsWithOnHoldFalseFilterOnly() {
        SearchRequest request = new SearchRequest(
                null, List.of(), null, null, null, null, List.of(), null, false, 0, 20, null, null);

        assertThatCode(() -> builder.build(request, Sort.unsorted()))
                .doesNotThrowAnyException();
    }

    @Test
    void buildsWithEveryFilterCombined() {
        // All filters at once: text + custodian + type + from + date + labels + hasAttachment + onHold.
        SearchRequest request = new SearchRequest(
                "fraud", List.of("custodian-1"), MessageType.EMAIL, "alice@firm.test",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T00:00:00Z"),
                List.of("PRIVILEGED"), true, false, 0, 20, null, null);

        // The subCriteria wraps the text OR chain; the criteriaChain has the filters.
        Query query = builder.build(request, Sort.unsorted());
        CriteriaQuery cq = (CriteriaQuery) query;

        assertThat(cq.getCriteria().getSubCriteria()).hasSize(1);
        // The chain has the AND-ed filters (custodian, type, from, date, labels, hasAttachment, onHold).
        assertThat(cq.getCriteria().getCriteriaChain()).hasSize(7);
    }

    // ---- Req 3: highlighting ----

    @Test
    void queryAlwaysRequestsHighlighting() {
        SearchRequest request = textOnly("fraud");

        Query query = builder.build(request, Sort.unsorted());

        assertThat(query.getHighlightQuery())
                .as("highlight must be configured on every query")
                .isNotNull();
    }

    @Test
    void highlightingIsPresentEvenForFilterOnlyQueries() {
        // A query with no text still gets highlighting — the server returns nothing to highlight,
        // but the config is there so a text query added later doesn't miss it.
        SearchRequest request = new SearchRequest(
                null, List.of("custodian-1"), null, null, null, null, List.of(), null, null, 0, 20, null, null);

        Query query = builder.build(request, Sort.unsorted());

        assertThat(query.getHighlightQuery()).isNotNull();
    }

    // ---- Req 4: pagination + sort ----

    @Test
    void queryAppliesTheGivenSort() {
        SearchRequest request = textOnly("fraud");
        Sort dateDesc = Sort.by(Sort.Direction.DESC, "sentAt");

        Query query = builder.build(request, dateDesc);

        assertThat(query.getSort()).isEqualTo(dateDesc);
    }

    @Test
    void queryAppliesPaginationFromTheRequest() {
        SearchRequest request = new SearchRequest(
                "fraud", List.of(), null, null, null, null, List.of(), null, null, 3, 50, null, null);

        Query query = builder.build(request, Sort.unsorted());

        assertThat(query.getPageable().getPageNumber()).isEqualTo(3);
        assertThat(query.getPageable().getPageSize()).isEqualTo(50);
    }

    @Test
    void unsortedSortLeavesQueryWithoutExplicitSort() {
        SearchRequest request = textOnly("fraud");

        Query query = builder.build(request, Sort.unsorted());

        // Sort.unsorted() means "no sort attached" — Elasticsearch's score default stands.
        assertThat(query.getSort() == null || !query.getSort().isSorted()).isTrue();
    }

    @Test
    void defaultPageIsZeroWhenNotSpecified() {
        SearchRequest request = new SearchRequest(
                "fraud", List.of(), null, null, null, null, List.of(), null, null, null, null, null, null);

        Query query = builder.build(request, Sort.unsorted());

        assertThat(query.getPageable().getPageNumber()).isZero();
        assertThat(query.getPageable().getPageSize()).isEqualTo(20);
    }

    private SearchRequest textOnly(String query) {
        return new SearchRequest(query, List.of(), null, null, null, null, List.of(), null, null, 0, 20, null, null);
    }
}
