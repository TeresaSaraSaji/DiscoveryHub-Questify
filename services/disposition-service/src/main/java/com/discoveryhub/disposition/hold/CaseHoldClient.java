package com.discoveryhub.disposition.hold;

import com.discoveryhub.disposition.config.DispositionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Fetches the holds currently in force from P4's {@code GET /holds/active}, once per sweep.
 *
 * <p>This is the case-level guard. {@link HoldCheckClient} asks "is this message flagged?", which
 * depends on P4 having already expanded a hold down to the message; this asks "what is under hold
 * right now?", which does not. Placing a hold on a case is enough to stop a delete, whether or not
 * the asynchronous propagation FR-4.3 mandates has caught up.
 */
@Component
public class CaseHoldClient {

    private static final Logger log = LoggerFactory.getLogger(CaseHoldClient.class);

    private static final ParameterizedTypeReference<List<ActiveHold>> HOLD_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final DispositionProperties props;

    public CaseHoldClient(RestClient holdCheckRestClient, DispositionProperties props) {
        this.restClient = holdCheckRestClient;
        this.props = props;
    }

    /**
     * The holds in force now.
     *
     * <p>Returns an <i>unavailable</i> snapshot rather than an empty one when P4 cannot be
     * reached. An empty list means "nothing is under hold, delete freely"; that is the most
     * dangerous possible interpretation of a network error, and keeping the two states distinct in
     * the type is what stops a caller from conflating them.
     */
    public HoldScopeSnapshot activeHolds() {
        if (!props.holdCheck().enabled()) {
            return HoldScopeSnapshot.disabled();
        }
        try {
            List<ActiveHold> holds = restClient.get()
                    .uri("/holds/active")
                    .retrieve()
                    .body(HOLD_LIST);
            if (holds == null) {
                log.warn("P4 returned an empty body for /holds/active — treating scope as unavailable");
                return HoldScopeSnapshot.unavailable();
            }
            log.debug("{} active hold(s) in force", holds.size());
            return HoldScopeSnapshot.of(holds);
        } catch (Exception ex) {
            log.warn("could not fetch active holds from P4 — treating scope as unavailable: {}", ex.toString());
            return HoldScopeSnapshot.unavailable();
        }
    }
}
