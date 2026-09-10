package com.discoveryhub.disposition.hold;

import com.discoveryhub.disposition.config.DispositionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The hold guard: a synchronous call to P4's {@code GET /holds/check?messageId=...} before anything
 * is deleted (FR-4.2, FR-5.2).
 *
 * <p>Synchronous, in a system that is otherwise event-driven, because destroying evidence is the
 * one operation where a stale answer is unacceptable. P2 mirrors hold state from {@code
 * holds.events} onto an {@code on_hold} flag and this service reads it as a fast-path skip, but a
 * consumer lag of a few seconds between "hold placed" and "flag set" is exactly the window in
 * which a sweep would delete evidence. So the flag is an optimisation and this call is the answer.
 *
 * <p>Fail closed: an unreachable P4 reports {@link Verdict#UNKNOWN}, and the sweep treats unknown
 * as held whenever {@code hold-check.required} is true. Cannot verify, will not delete.
 */
@Component
public class HoldCheckClient {

    private static final Logger log = LoggerFactory.getLogger(HoldCheckClient.class);

    private final RestClient restClient;
    private final DispositionProperties props;

    public HoldCheckClient(RestClient holdCheckRestClient, DispositionProperties props) {
        this.restClient = holdCheckRestClient;
        this.props = props;
    }

    /** What P4 said, with "could not ask" kept distinct from "no". */
    public enum Verdict {
        HELD,
        NOT_HELD,
        /** P4 unreachable, or hold checking switched off entirely. */
        UNKNOWN
    }

    public Verdict check(String messageId) {
        if (!props.holdCheck().enabled()) {
            return Verdict.UNKNOWN;
        }
        try {
            HoldCheckResponse response = restClient.get()
                    .uri(uri -> uri.path("/holds/check").queryParam("messageId", messageId).build())
                    .retrieve()
                    .body(HoldCheckResponse.class);
            // A 200 with an unparseable or empty body is not a "no". Treat it as unasked.
            if (response == null) {
                log.warn("hold check for {} returned an empty body — treating as unknown", messageId);
                return Verdict.UNKNOWN;
            }
            return response.held() ? Verdict.HELD : Verdict.NOT_HELD;
        } catch (Exception ex) {
            log.warn("hold check failed for {} — treating as unknown: {}", messageId, ex.toString());
            return Verdict.UNKNOWN;
        }
    }
}
