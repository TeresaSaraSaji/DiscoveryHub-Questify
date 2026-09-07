package com.discoveryhub.ingestion.api;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.ingestion.service.IngestService;
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

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public ResponseEntity<IngestResponse> ingest(@RequestBody List<Message> batch) {
        if (batch == null || batch.isEmpty()) {
            return ResponseEntity.badRequest().body(IngestResponse.of(List.of()));
        }
        IngestResponse response = ingestService.ingest(batch);
        // Duplicates and validation rejections are reported per item on a 2xx; only an
        // infrastructure failure gets a retryable status, so a client knows to re-send.
        HttpStatus status = response.failed() > 0 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
