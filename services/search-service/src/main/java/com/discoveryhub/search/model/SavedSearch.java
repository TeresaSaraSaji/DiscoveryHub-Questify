package com.discoveryhub.search.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

/**
 * A search an investigator saved against a case, so they can re-run it later without retyping the
 * criteria. Stored in its own Elasticsearch index ({@code saved-searches}) — P3 owns Elasticsearch,
 * and a saved search is search-service metadata, not a communication, so it does not live in the
 * {@code communications} index.
 *
 * <p>The {@link SearchRequest} is stored as {@code requestJson} rather than as a nested object — the
 * same "JSON text for an unqueried structured field" pattern {@code MessageEntity} uses for its
 * list fields. A saved search is never queried by its own criteria (you look it up by id or by
 * case), so a typed mapping would buy nothing but portability risk, and re-running parses it back
 * into a {@link SearchRequest} with the same Jackson 3 {@code ObjectMapper} that serialised it.
 */
@Document(indexName = "saved-searches")
public class SavedSearch {

    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    @Field(type = FieldType.Keyword)
    private String name;

    @Field(type = FieldType.Keyword)
    private String caseId;

    /** Serialised {@link SearchRequest}. Parsed back into a record on re-run. */
    @Field(type = FieldType.Text, index = false)
    private String requestJson;

    @Field(type = FieldType.Keyword)
    private String createdBy;

    @Field(type = FieldType.Date, format = DateFormat.strict_date_time, name = "createdAt")
    private Instant createdAt;

    public SavedSearch() {
    }

    public SavedSearch(String id, String name, String caseId, String requestJson,
                       String createdBy, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.caseId = caseId;
        this.requestJson = requestJson;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
