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

    /** Every message in the custodian's mailbox, paged through to the end. */
    public List<Message> listByCustodian(String custodianId) {
        List<Message> all = new ArrayList<>();
        int page = 0;
        while (true) {
            final int currentPage = page;
            PagedMessages response = rest.get()
                    .uri(uri -> uri.path("/messages")
                            .queryParam("custodianId", custodianId)
                            .queryParam("page", currentPage)
                            .queryParam("size", pageSize)
                            .build())
                    .retrieve()
                    .body(PagedMessages.class);
            if (response == null || response.content() == null || response.content().isEmpty()) {
                break;
            }
            all.addAll(response.content());
            int totalPages = response.totalPages() > 0 ? response.totalPages() : 1;
            if (page + 1 >= totalPages) {
                break;
            }
            page++;
        }
        log.debug("fetched {} messages for custodian {}", all.size(), custodianId);
        return all;
    }

    /** Slice of Spring's Page JSON — only the fields the pager needs. */
    public record PagedMessages(List<Message> content, int totalPages, long totalElements, boolean last) {
    }
}
