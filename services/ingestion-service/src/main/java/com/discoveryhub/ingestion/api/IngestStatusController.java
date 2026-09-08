package com.discoveryhub.ingestion.api;

import com.discoveryhub.contracts.Ids;
import com.discoveryhub.ingestion.service.IngestService;
import com.discoveryhub.ingestion.service.IngestStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoints for ingestion state.
 *
 * <p>Careful about what these are. P1 accepts, dedupes and publishes; it stores no messages. So it
 * can answer "has this id been through here" and "what has this instance done since it started",
 * and nothing else. It cannot return a message, because it does not have one — that is P2's job,
 * and routing message reads through here would quietly make ingestion a read path for data it does
 * not own.
 */
@Tag(name = "Ingestion status", description = "What this instance has seen. Not an archive query.")
@RestController
public class IngestStatusController {

    private final IngestService ingestService;

    public IngestStatusController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * Answers from the dedupe store, so it is subject to the same caveats: the TTL expires, a
     * flushed Redis forgets, and this instance fails the lookup closed rather than guessing. A
     * false here means "not seen by this deployment recently", not "definitely never ingested".
     * The authoritative answer lives in P2.
     */
    @Operation(summary = "Has this externalId been ingested?",
            description = """
                    Reports the ingestion dedupe state, not the archive. Subject to the dedupe TTL
                    and lost if Redis is flushed, so a false is "not seen recently by this
                    deployment" rather than "never ingested". P2 holds the authoritative answer.""")
    @GetMapping("/messages/{externalId}/status")
    public ResponseEntity<IngestedStatus> status(@PathVariable("externalId") String externalId) {
        boolean ingested = ingestService.hasIngested(externalId);
        return ResponseEntity.ok(new IngestedStatus(
                externalId,
                ingested,
                // Derived, not looked up: the id is a pure function of externalId, so it is
                // correct whether or not the message was ever seen. Useful to a caller wanting to
                // cross-reference against P2 or the search index.
                Ids.messageId(externalId)));
    }

    @Operation(summary = "Counts since this instance started",
            description = """
                    Per instance and reset on restart. P1 stores nothing, so it has no durable
                    total and does not pretend to one; the archive count belongs to P2.""")
    @GetMapping("/messages/stats")
    public ResponseEntity<IngestStats> stats() {
        return ResponseEntity.ok(ingestService.stats());
    }

    public record IngestedStatus(String externalId, boolean ingested, String messageId) {
    }
}
