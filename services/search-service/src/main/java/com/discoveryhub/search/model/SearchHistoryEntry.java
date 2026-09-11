package com.discoveryhub.search.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

/**
 * One executed search, recorded so an investigator can see what has been asked of the archive and
 * re-run it. Stored in its own Elasticsearch index ({@code search-history}) for the same reason
 * {@link SavedSearch} has one: history is search-service metadata, not a communication.
 *
 * <p>Unlike a saved search, a history entry is written by the service itself, not by a user action
 * — every first-page execution of {@code POST /search} leaves one. Pagination through an existing
 * result set is deliberately not recorded: page 3 of "fraud" is the same question as page 0, and a
 * history that repeats itself every time someone clicks Next is noise, not a record.
 *
 * <p>The full {@link SearchRequest} is stored as {@code requestJson} (the same unqueried-JSON-text
 * pattern as {@code SavedSearch.requestJson}), so re-running replays the exact criteria. The
 * {@code query} text is duplicated as its own field purely for display — a history list that shows
 * raw JSON is unreadable.
 */
@Document(indexName = "search-history")
public class SearchHistoryEntry {

    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    /** The free-text part of the request, null for a pure filter search. Display only. */
    @Field(type = FieldType.Keyword)
    private String query;

    /** Serialised {@link SearchRequest}. Parsed back into a record on re-run. */
    @Field(type = FieldType.Text, index = false)
    private String requestJson;

    /** Total hits the query matched when it ran — the answer may differ on a re-run. */
    @Field(type = FieldType.Long)
    private long totalHits;

    @Field(type = FieldType.Long)
    private long tookMs;

    @Field(type = FieldType.Date, format = DateFormat.strict_date_time, name = "executedAt")
    private Instant executedAt;

    public SearchHistoryEntry() {
    }

    public SearchHistoryEntry(String id, String query, String requestJson,
                              long totalHits, long tookMs, Instant executedAt) {
        this.id = id;
        this.query = query;
        this.requestJson = requestJson;
        this.totalHits = totalHits;
        this.tookMs = tookMs;
        this.executedAt = executedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }

    public long getTotalHits() { return totalHits; }
    public void setTotalHits(long totalHits) { this.totalHits = totalHits; }

    public long getTookMs() { return tookMs; }
    public void setTookMs(long tookMs) { this.tookMs = tookMs; }

    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
}
