package com.discoveryhub.search.model;

import com.discoveryhub.contracts.MessageType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.List;

/**
 * One search request. Bound from {@code POST /search} (the full body) or from {@code GET /search}
 * query params (the common case). Defaults are applied in the compact constructor so a caller can
 * send as little as {@code {"query":"fraud"}} and get a sensible first page.
 *
 * <p>Validation is all-or-nothing here (unlike P1's per-message batch): a search is a single query,
 * so bean validation rejecting the whole request is correct, and {@code GlobalExceptionHandler}
 * turns a {@code @Valid} failure into a 400 with the offending fields named.
 *
 * <p>Filters: {@code hasAttachment} and {@code onHold} are tri-state — {@code null} means "do not
 * filter on this", {@code true}/{@code false} mean "only messages that are/are not". That keeps the
 * common case (no filter) distinct from "only messages with no attachments".
 */
public record SearchRequest(
        String query,
        List<String> custodianIds,
        MessageType type,
        String from,
        Instant sentAfter,
        Instant sentBefore,
        List<String> labels,
        Boolean hasAttachment,
        Boolean onHold,
        @PositiveOrZero Integer page,
        @Max(10000) Integer size,
        SortBy sortBy,
        SortDirection sortDirection) {

    /** What to order by. Relevance is the Elasticsearch default; date is the eDiscovery default. */
    public enum SortBy {
        RELEVANCE,
        DATE
    }

    public enum SortDirection {
        ASC,
        DESC
    }

    public SearchRequest {
        custodianIds = custodianIds == null ? List.of() : List.copyOf(custodianIds);
        labels = labels == null ? List.of() : List.copyOf(labels);
        page = page == null ? 0 : Math.max(0, page);
        // 20 is the first-page size that fits a browser without scrolling. The hard cap lives in
        // SearchProperties.maxPageSize, which the service clamps against after binding.
        size = size == null ? 20 : Math.max(1, size);
        sortBy = sortBy == null ? SortBy.RELEVANCE : sortBy;
        sortDirection = sortDirection == null ? SortDirection.DESC : sortDirection;
    }
}
