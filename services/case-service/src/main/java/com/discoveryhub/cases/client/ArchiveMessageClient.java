package com.discoveryhub.cases.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Asks P2 whether a message actually exists before it is filed as evidence.
 *
 * <p>Evidence is filed from search results, and the search index is eventually consistent with the
 * archive — a message destroyed by disposition is removed from the index by a Kafka consumer, and
 * until that record is processed the result set can still contain it. Filing one of those creates
 * an evidence row pointing at nothing: invisible on the case, and surfacing much later as an item
 * an export cannot package.
 *
 * <p><b>Fail closed.</b> If P2 cannot be reached the message is not accepted, because "it exists"
 * and "I could not check" are different answers and only one of them justifies writing an evidence
 * row. This is the same stance the hold guards take before a deletion: an unverified message is
 * never acted on. The caller turns that into a 503, so the investigator retries rather than ending
 * up with a case they have to audit by hand.
 */
@Component
public class ArchiveMessageClient {

    private static final Logger log = LoggerFactory.getLogger(ArchiveMessageClient.class);

    private final RestClient client;

    public ArchiveMessageClient(RestClient archiveRestClient) {
        this.client = archiveRestClient;
    }

    /** Whether P2 still holds this message. */
    public boolean exists(String messageId) {
        try {
            client.get().uri("/messages/{id}", messageId).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            log.error("could not verify message {} against the archive: {}", messageId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "cannot verify the message exists: the archive is unreachable");
        }
    }

    /**
     * Which of these messages P2 still holds, preserving the caller's order.
     *
     * <p>One request per id. P2 exposes no bulk existence check, and a filing action is worth a few
     * hundred cheap calls to avoid writing evidence rows that point at nothing. If a batch ever
     * grows past the point where that is acceptable, the fix is a bulk endpoint on P2 rather than
     * dropping the check.
     */
    public Set<String> existing(List<String> messageIds) {
        Set<String> present = new LinkedHashSet<>();
        for (String messageId : messageIds) {
            if (exists(messageId)) {
                present.add(messageId);
            }
        }
        return present;
    }
}
