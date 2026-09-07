package com.discoveryhub.ingestion.api;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.ingestion.service.IngestService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/messages")
public class IngestController {

    private final IngestService ingestService;
    private final int maxBatchSize;

    public IngestController(IngestService ingestService,
                            @Value("${discoveryhub.ingestion.max-batch-size:1000}") int maxBatchSize) {
        this.ingestService = ingestService;
        this.maxBatchSize = maxBatchSize;
    }

    @PostMapping
    public ResponseEntity<IngestResponse> ingest(@RequestBody List<Message> batch) {
        if (batch == null || batch.isEmpty()) {
            return ResponseEntity.badRequest().body(IngestResponse.of(List.of()));
        }
        if (batch.size() > maxBatchSize) {
            // Refused whole rather than truncated: a client that gets a partial result for an
            // oversized batch has no way to tell which messages were dropped.
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(IngestResponse.of(List.of(IngestResult.rejected(null,
                            "batch of " + batch.size() + " exceeds the limit of " + maxBatchSize
                                    + "; post smaller batches"))));
        }
        IngestResponse response = ingestService.ingest(batch);
        // Duplicates and validation rejections are reported per item on a 2xx; only an
        // infrastructure failure gets a retryable status, so a client knows to re-send.
        HttpStatus status = response.failed() > 0 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
