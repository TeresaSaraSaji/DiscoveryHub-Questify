package com.discoveryhub.search.client;

import com.discoveryhub.search.config.CaseClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Files matched messages onto a case by calling case-service's
 * {@code POST /cases/{id}/evidence/batch}.
 *
 * <p><b>Why P3 calls P4 rather than publishing an event.</b> Case membership is P4's data, so P4
 * has to do the write either way. The choice is between asking it directly and publishing a
 * command for it to consume. Direct wins here for one reason: the caller gets a truthful answer.
 * The endpoint reports how many rows it actually created, so the HTTP response, the audit event and
 * the UI can all state what happened instead of predicting it. The previous design published an
 * event nothing consumed and reported success for an action that never occurred.
 *
 * <p><b>What this costs.</b> P4 being down now fails the bulk add. That is the right failure: the
 * user is told the messages were not filed, rather than being told they were and finding an empty
 * case later. It is graceful degradation in the NFR-2 sense — search itself keeps working, only
 * this one action is refused, and nothing cascades.
 *
 * <p>Chunked at {@link CaseClientProperties#batchSize}, because case-service writes a batch in a
 * single transaction and the "add all results" cap is 10,000 ids. One transaction that size would
 * hold locks on another service's database for the duration.
 */
@Component
public class CaseEvidenceWriter {

    private static final Logger log = LoggerFactory.getLogger(CaseEvidenceWriter.class);

    private final RestClient rest;
    private final CaseClientProperties props;

    public CaseEvidenceWriter(RestClient caseRestClient, CaseClientProperties props) {
        this.rest = caseRestClient;
        this.props = props;
    }

    /**
     * File every id onto the case, in chunks.
     *
     * <p>Partial success is reported, not hidden: if a later chunk fails, the chunks that already
     * committed stay committed, and the exception carries the count that made it. Re-running is
     * safe because the endpoint treats an already-present id as {@code alreadyPresent} rather than
     * an error, so the retry adds only what is missing.
     *
     * @throws CaseEvidenceException if case-service could not be reached or refused the write
     */
    public BulkEvidenceResult fileEvidence(String caseId, List<String> messageIds, String searchRef) {
        if (messageIds == null || messageIds.isEmpty()) {
            return BulkEvidenceResult.empty();
        }
        String path = "/cases/" + UriUtils.encodePathSegment(caseId, StandardCharsets.UTF_8) + "/evidence/batch";
        BulkEvidenceResult total = BulkEvidenceResult.empty();

        for (int from = 0; from < messageIds.size(); from += props.batchSize()) {
            List<String> chunk = messageIds.subList(from, Math.min(from + props.batchSize(), messageIds.size()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("messageIds", chunk);
            body.put("source", "SEARCH");
            body.put("searchRef", searchRef);
            try {
                BulkEvidenceResult result = rest.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(BulkEvidenceResult.class);
                total = total.plus(result == null ? BulkEvidenceResult.empty() : result);
            } catch (Exception ex) {
                log.error("failed to file evidence chunk [{}..{}) on case {}: {}",
                        from, from + chunk.size(), caseId, ex.toString());
                throw new CaseEvidenceException(caseId, total, ex);
            }
        }
        log.info("filed evidence on case {}: requested={}, added={}, alreadyPresent={}",
                caseId, total.requested(), total.added(), total.alreadyPresent());
        return total;
    }

    /** case-service could not be asked, or refused. Carries what had already been written. */
    public static class CaseEvidenceException extends RuntimeException {

        private final transient BulkEvidenceResult partial;

        public CaseEvidenceException(String caseId, BulkEvidenceResult partial, Throwable cause) {
            super("could not file evidence on case " + caseId + " (" + partial.added()
                    + " already written before the failure): " + cause.getMessage(), cause);
            this.partial = partial;
        }

        /** What made it in before the failure. Non-zero means the case is partially populated. */
        public BulkEvidenceResult partial() {
            return partial;
        }
    }
}
