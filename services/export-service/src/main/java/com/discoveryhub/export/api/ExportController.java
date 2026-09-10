package com.discoveryhub.export.api;

import com.discoveryhub.export.domain.ExportJobEntity;
import com.discoveryhub.export.domain.ExportStatus;
import com.discoveryhub.export.messaging.ExportEvents;
import com.discoveryhub.export.messaging.ExportKafkaPublisher;
import com.discoveryhub.export.service.ExportRequest;
import com.discoveryhub.export.service.ExportService;
import com.discoveryhub.export.service.ExportVerifier;
import com.discoveryhub.export.service.ObjectStorageClient;
import com.discoveryhub.export.service.VerificationResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * FR-6: request an export, watch it move Queued -&gt; Running -&gt; Completed/Failed, download the
 * finished package via an expiring link, and verify its checksums independently of trusting the
 * job record.
 */
@RestController
@RequestMapping("/exports")
public class ExportController {

    private final ExportService exportService;
    private final ObjectStorageClient storage;
    private final ExportVerifier verifier;
    private final ExportKafkaPublisher publisher;
    private final ExportEvents events;

    public ExportController(ExportService exportService, ObjectStorageClient storage,
                            ExportVerifier verifier, ExportKafkaPublisher publisher, ExportEvents events) {
        this.exportService = exportService;
        this.storage = storage;
        this.verifier = verifier;
        this.publisher = publisher;
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<ExportJobEntity> requestExport(@RequestBody ExportRequest request) {
        ExportJobEntity job = exportService.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", "/exports/" + job.getJobId())
                .body(job);
    }

    @GetMapping
    public List<ExportJobEntity> recent() {
        return exportService.recent();
    }

    @GetMapping("/{jobId}")
    public ExportJobEntity status(@PathVariable String jobId) {
        return exportService.find(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "export job not found: " + jobId));
    }

    @PostMapping("/{jobId}/retry")
    public ExportJobEntity retry(@PathVariable String jobId) {
        return exportService.retry(jobId);
    }

    /** An expiring link, not the bytes themselves — a completed package can be downloaded more than once. */
    @GetMapping("/{jobId}/download")
    public Map<String, Object> download(@PathVariable String jobId) {
        ExportJobEntity job = exportService.find(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "export job not found: " + jobId));
        if (job.getStatus() != ExportStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "export job " + jobId + " is " + job.getStatus() + ", not COMPLETED");
        }
        String url = storage.presignedDownloadUrl(job.getObjectKey());
        publisher.publishAudit(events.downloaded(jobId));
        return Map.of(
                "jobId", jobId,
                "url", url,
                "packageSha256", job.getPackageSha256(),
                "sizeBytes", job.getPackageSizeBytes());
    }

    /** FR-6.5: re-derive every checksum from the package's own bytes; trust nothing the job record claims. */
    @GetMapping("/{jobId}/verify")
    public VerificationResult verify(@PathVariable String jobId) {
        ExportJobEntity job = exportService.find(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "export job not found: " + jobId));
        if (job.getStatus() != ExportStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "export job " + jobId + " is " + job.getStatus() + ", not COMPLETED");
        }
        byte[] bytes = storage.fetchPackage(job.getObjectKey());
        return verifier.verify(bytes, job.getPackageSha256());
    }
}
