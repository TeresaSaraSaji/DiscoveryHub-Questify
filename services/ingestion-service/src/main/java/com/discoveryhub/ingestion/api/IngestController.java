package com.discoveryhub.ingestion.api;

import com.discoveryhub.ingestion.service.IngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Tag(name = "Ingestion", description = "Accept messages into DiscoveryHub")
@RestController
@RequestMapping("/messages")
public class IngestController {

    private final IngestService ingestService;
    private final MessageBatchDecoder decoder;
    private final int maxBatchSize;

    public IngestController(IngestService ingestService, MessageBatchDecoder decoder,
                            @Value("${discoveryhub.ingestion.max-batch-size:1000}") int maxBatchSize) {
        this.ingestService = ingestService;
        this.decoder = decoder;
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * The body is taken as raw JSON rather than {@code List<Message>} so that converting an element
     * is a per-item concern. See {@link MessageBatch}.
     */
    @Operation(summary = "Submit a batch of messages",
            description = """
                    Takes a JSON array. The batch is not atomic: each message is accepted, deduped
                    or rejected on its own, and the response reports per-item outcomes, so one bad
                    message cannot lose the other 999.

                    A duplicate is a 2xx outcome, not an error — a source system re-sending is
                    normal. Only an infrastructure failure returns a retryable status.""")
    @PostMapping
    public ResponseEntity<IngestResponse> ingest(@RequestBody List<JsonNode> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(IngestResponse.of(List.of(
                    IngestResult.rejected(null, "body must be a non-empty JSON array of messages"))));
        }
        if (body.size() > maxBatchSize) {
            // Refused whole rather than truncated: a client that gets a partial result for an
            // oversized batch has no way to tell which messages were dropped.
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(IngestResponse.of(List.of(IngestResult.rejected(null,
                            "batch of " + body.size() + " exceeds the limit of " + maxBatchSize
                                    + "; post smaller batches"))));
        }
        IngestResponse response = ingestService.ingest(decoder.decode(body));
        // Duplicates and validation rejections are reported per item on a 2xx; only an
        // infrastructure failure gets a retryable status, so a client knows to re-send.
        HttpStatus status = response.failed() > 0 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
