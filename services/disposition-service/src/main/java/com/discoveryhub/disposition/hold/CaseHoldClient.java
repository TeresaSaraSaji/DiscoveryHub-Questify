package com.discoveryhub.disposition.hold;

import com.discoveryhub.disposition.config.DispositionProperties;
import com.discoveryhub.disposition.domain.ArchiveCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P4, asked the two questions that decide whether a sweep may delete anything.
 *
 * <p>This is the case-level guard, and it is deliberately separate from {@link HoldCheckClient}.
 * That one asks "is this message flagged?", which depends on P4 having already expanded a hold
 * down to the message. These ask "what is under hold right now, and which of these messages are
 * evidence in a held case?" — neither of which depends on propagation having finished.
 *
 * <p>Both calls are made once per sweep, not once per candidate. There are only ever a handful of
 * active holds, and the evidence question is a single bulk lookup over the run's candidate ids, so
 * a 500-message sweep costs two requests rather than a thousand.
 */
@Component
public class CaseHoldClient {

    private static final Logger log = LoggerFactory.getLogger(CaseHoldClient.class);

    private static final ParameterizedTypeReference<List<ActiveHold>> HOLD_LIST =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<List<EvidenceHold>> EVIDENCE_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final DispositionProperties props;

    public CaseHoldClient(RestClient holdCheckRestClient, DispositionProperties props) {
        this.restClient = holdCheckRestClient;
        this.props = props;
    }

    /**
     * The hold picture for one sweep: active hold scopes, plus which of these candidates are
     * evidence items in a held case.
     *
     * <p>Returns an <i>unavailable</i> context if either question cannot be answered. Partial
     * knowledge is not usable here — knowing the hold scopes but not the evidence membership would
     * let the sweep delete an out-of-scope evidence item with full confidence, which is worse than
     * knowing nothing and refusing.
     */
    public HoldContext contextFor(List<ArchiveCandidate> candidates) {
        if (!props.holdCheck().enabled()) {
            return HoldContext.disabled();
        }
        List<ActiveHold> holds = fetchActiveHolds();
        if (holds == null) {
            return HoldContext.unavailable();
        }
        // No holds anywhere means nothing can be protected, so skip the second call. This is the
        // normal state of the system and is worth not paying for.
        if (holds.isEmpty()) {
            return HoldContext.of(List.of(), Map.of());
        }
        Map<String, EvidenceHold> evidence = fetchHeldEvidence(candidates);
        if (evidence == null) {
            return HoldContext.unavailable();
        }
        return HoldContext.of(holds, evidence);
    }

    /** @return null if P4 could not be asked — distinct from an empty list. */
    private List<ActiveHold> fetchActiveHolds() {
        try {
            List<ActiveHold> holds = restClient.get()
                    .uri("/holds/active")
                    .retrieve()
                    .body(HOLD_LIST);
            if (holds == null) {
                log.warn("P4 returned an empty body for /holds/active — treating as unavailable");
                return null;
            }
            log.debug("{} active hold(s) in force", holds.size());
            return holds;
        } catch (Exception ex) {
            log.warn("could not fetch active holds from P4 — failing closed: {}", ex.toString());
            return null;
        }
    }

    /**
     * Which of the run's candidates are evidence items in a case under hold (FR-2.4).
     *
     * <p>A bulk POST rather than a query string: a sweep carries up to {@code batch-size} message
     * ids and a GET would run into URL length limits somewhere between here and P4 — a failure
     * that would look like a hold check bug and behave like data loss.
     *
     * @return null if P4 could not be asked — distinct from an empty map.
     */
    private Map<String, EvidenceHold> fetchHeldEvidence(List<ArchiveCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        List<String> messageIds = candidates.stream().map(ArchiveCandidate::messageId).toList();
        try {
            List<EvidenceHold> held = restClient.post()
                    .uri("/holds/evidence-check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messageIds", messageIds))
                    .retrieve()
                    .body(EVIDENCE_LIST);
            if (held == null) {
                log.warn("P4 returned an empty body for /holds/evidence-check — treating as unavailable");
                return null;
            }
            Map<String, EvidenceHold> byMessageId = new LinkedHashMap<>();
            for (EvidenceHold item : held) {
                if (item.messageId() != null) {
                    byMessageId.put(item.messageId(), item);
                }
            }
            log.debug("{} of {} candidates are evidence in a held case", byMessageId.size(), messageIds.size());
            return byMessageId;
        } catch (Exception ex) {
            log.warn("could not check held-case evidence with P4 — failing closed: {}", ex.toString());
            return null;
        }
    }
}
