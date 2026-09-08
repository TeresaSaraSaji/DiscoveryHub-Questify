package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.messaging.HoldCheckResponse;
import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Synchronous call to P4's {@code GET /holds/check?messageId=...}. This is the disposition guard
 * (architecture decision 4): deletion of held data is the one place where eventual consistency is
 * unacceptable, so the retention job asks P4 directly rather than trusting the local {@code onHold}
 * flag. If P4 is unreachable the caller treats the message as held and skips it — fail closed,
 * never delete unverified data (FR-4.2, FR-5.2).
 */
@Component
public class HoldCheckClient {

    private static final Logger log = LoggerFactory.getLogger(HoldCheckClient.class);

    private final RestClient restClient;

    public HoldCheckClient(RestClient holdCheckRestClient) {
        this.restClient = holdCheckRestClient;
    }

    /**
     * @return {@code true} if P4 says the message is held, or if P4 could not be reached. Never
     *         {@code false} on a communication failure — that would let a held message be deleted.
     */
    public boolean isHeld(String messageId) {
        try {
            HoldCheckResponse response = restClient.get()
                    .uri(uri -> uri.path("/holds/check").queryParam("messageId", messageId).build())
                    .retrieve()
                    .body(HoldCheckResponse.class);
            return response != null && response.held();
        } catch (Exception ex) {
            log.warn("hold check failed for {} — treating as held (fail closed): {}", messageId, ex.toString());
            return true;
        }
    }

    /** Topic name, exposed for audit detail so the wiring is visible without grepping. */
    @SuppressWarnings("unused")
    public String holdEventsTopic() {
        return Topics.HOLDS_EVENTS;
    }
}
