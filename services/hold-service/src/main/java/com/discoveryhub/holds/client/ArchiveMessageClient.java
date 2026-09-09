package com.discoveryhub.holds.client;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.holds.config.HoldProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Pages P2's read API ({@code GET /messages?custodianId=...}) to enumerate every message in a
 * custodian's mailbox — the input to scope resolution. A hold scoped by custodian must cover the
 * exact set of that custodian's messages, and the hold-service owns no messages, so it asks P2.
 *
 * <p>If P2 is unreachable or returns an error, the call throws and the hold worker marks the hold
 * FAILED: we never silently hold an empty scope, because an empty coverage table would make
 * {@code GET /holds/check} answer "not held" for everything — the unsafe direction. Failing the
 * placement is the safe failure (the user retries once P2 is back).
 */
@Component
public class ArchiveMessageClient {

    private static final Logger log = LoggerFactory.getLogger(ArchiveMessageClient.class);

    private final RestClient rest;
    private final int pageSize;

    public ArchiveMessageClient(RestClient archiveRestClient, HoldProperties props) {
        this.rest = archiveRestClient;
        this.pageSize = props.scopePageSize();
    }

    /**
     * Every message in the custodian's mailbox, paged through to the end.
     *
     * <p>The loop is driven by {@code page < totalPages}, not by "stop at the first empty page":
     * an unexpected empty-but-not-last page from P2 (a transient gap, a bug) previously stopped
     * enumeration early and silently under-resolved the scope — the unsafe direction for a
     * protective hold, since messages missed here are never covered and {@code GET /holds/check}
     * would answer "not held" for them. Any response that does not have the shape scope
     * resolution needs (null body, a page short of what {@code totalPages} promised) is now a
     * hard failure, so the caller marks the hold FAILED instead of silently persisting partial
     * coverage.
     */
    public List<Message> listByCustodian(String custodianId) {
        List<Message> all = new ArrayList<>();
        int page = 0;
        int totalPages = 1;
        while (page < totalPages) {
            final int currentPage = page;
            PagedMessages response = rest.get()
                    .uri(uri -> uri.path("/messages")
                            .queryParam("custodianId", custodianId)
                            .queryParam("page", currentPage)
                            .queryParam("size", pageSize)
                            .build())
                    .retrieve()
                    .body(PagedMessages.class);
            if (response == null || response.content() == null) {
                throw new IllegalStateException(
                        "archive returned no body for custodian " + custodianId + " page " + currentPage);
            }
            if (page == 0) {
                totalPages = response.totalPages() > 0 ? response.totalPages() : 1;
            }
            if (response.content().isEmpty() && page + 1 < totalPages) {
                throw new IllegalStateException("archive returned an empty page " + currentPage
                        + " of " + totalPages + " for custodian " + custodianId + "; scope resolution aborted"
                        + " rather than silently under-covering the hold");
            }
            all.addAll(response.content());
            page++;
        }
        log.debug("fetched {} messages for custodian {}", all.size(), custodianId);
        return all;
    }

    /** Slice of Spring's Page JSON — only the fields the pager needs. */
    public record PagedMessages(List<Message> content, int totalPages, long totalElements, boolean last) {
    }
}
