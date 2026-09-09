package com.discoveryhub.export.service;

import com.discoveryhub.contracts.Message;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads archived messages and attachment bytes back out of P2 — the only place an export package
 * gets its content from, since P5 stores nothing of its own beyond the finished package and the
 * job record.
 */
@Component
public class ArchiveClient {

    private final RestClient client;

    public ArchiveClient(RestClient archiveRestClient) {
        this.client = archiveRestClient;
    }

    public Optional<Message> findMessage(String messageId) {
        try {
            return Optional.ofNullable(client.get()
                    .uri("/messages/{id}", messageId)
                    .retrieve()
                    .body(Message.class));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public byte[] fetchAttachmentBytes(String messageId, String attachmentId) {
        byte[] body = client.get()
                .uri("/messages/{messageId}/attachments/{attachmentId}", messageId, attachmentId)
                .retrieve()
                .body(byte[].class);
        return body == null ? new byte[0] : body;
    }

    /**
     * Resolves a custodian + optional date-range scope (a hold's scope, until P4 exists to hand us
     * one directly) to the concrete message ids it covers. Pages through P2's list endpoint rather
     * than assuming everything fits on one page.
     */
    public List<String> resolveCustodianScope(String custodianId, Instant from, Instant to) {
        List<String> ids = new ArrayList<>();
        int page = 0;
        while (true) {
            int currentPage = page;
            ArchivePage result = client.get()
                    .uri(uri -> uri.path("/messages")
                            .queryParam("custodianId", custodianId)
                            .queryParam("page", currentPage)
                            .queryParam("size", 500)
                            .build())
                    .retrieve()
                    .body(ArchivePage.class);
            if (result == null || result.content().isEmpty()) {
                break;
            }
            for (Message m : result.content()) {
                if (inRange(m.sentAt(), from, to)) {
                    ids.add(m.messageId());
                }
            }
            if (result.last()) {
                break;
            }
            page++;
        }
        return ids;
    }

    private static boolean inRange(Instant sentAt, Instant from, Instant to) {
        if (from != null && sentAt.isBefore(from)) {
            return false;
        }
        return to == null || !sentAt.isAfter(to);
    }
}
