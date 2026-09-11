package com.discoveryhub.ingestion.api;

import com.discoveryhub.ingestion.service.AsyncUploadService;
import com.discoveryhub.ingestion.service.UploadJobStore;
import com.discoveryhub.ingestion.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * File upload for the frontend: {@code POST /messages/upload}, {@code multipart/form-data}, field
 * {@code file}.
 *
 * <p>Accepts a JSON array or NDJSON, the two shapes people already have. Everything behind this is
 * the same code {@code POST /messages} runs, so dedupe, validation, attachment integrity and audit
 * apply identically — including the useful consequence that uploading the same file twice adds
 * nothing the second time.
 *
 * <p>Synchronous on purpose, for now. It is honest at the sizes this is bounded to and it avoids a
 * job store nobody has asked for yet. Beyond a few thousand messages a browser will time out
 * waiting, and at that point this should return a job id and report progress the way P5's exports
 * do — but building that before anyone needs it would be guessing at the shape.
 */
@Tag(name = "Ingestion", description = "Accept messages into DiscoveryHub")
@RestController
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    private final UploadService uploadService;
    private final AsyncUploadService asyncUploadService;
    private final UploadJobStore jobs;

    public UploadController(UploadService uploadService, AsyncUploadService asyncUploadService,
                            UploadJobStore jobs) {
        this.uploadService = uploadService;
        this.asyncUploadService = asyncUploadService;
        this.jobs = jobs;
    }

    @Operation(summary = "Poll an asynchronous upload",
            description = """
                    Job state is held in memory, so a job id is only meaningful to the instance
                    that issued it and does not survive a restart. Re-running a lost upload is
                    safe: the second run reports duplicates and ingests nothing.""")
    @GetMapping("/messages/uploads/{jobId}")
    public ResponseEntity<?> status(@PathVariable("jobId") String jobId) {
        return jobs.find(jobId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(IngestResponse.of(
                        List.of(IngestResult.rejected(jobId,
                                "unknown job; it may have expired, or been issued by another instance")))));
    }

    @Operation(summary = "Upload a file of messages",
            description = """
                    Accepts a JSON array or NDJSON. Dedupe, validation, attachment integrity and
                    audit apply exactly as they do to POST /messages, so uploading the same file
                    twice adds nothing.

                    retentionMode chooses how long the uploaded messages are kept before P2's
                    disposition sweep is allowed to delete them:
                      - NORMAL (default): the type-based retention period (discoveryhub.retention),
                        the same as every other message.
                      - DEMO: a short, per-message override (minutes, not years) so this specific
                        upload — and nothing else — becomes disposition-eligible within the length
                        of a demo. Pick this for a file you are about to show being disposed of;
                        picking it for a real upload means it is deleted far sooner than intended.

                    With async=true the file is spooled to disk, 202 Accepted is returned with a
                    job id, and progress is polled from GET /messages/uploads/{jobId}. Use it for
                    anything large: the 12,000-message corpus takes around 75 seconds, which is
                    longer than a browser will wait.""")
    @PostMapping(path = "/messages/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "async", defaultValue = "false") boolean async,
            @RequestParam(value = "retentionMode", defaultValue = "NORMAL") RetentionMode retentionMode) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(IngestResponse.of(List.of(
                    IngestResult.rejected(null, "no file uploaded, or the file is empty"))));
        }

        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();

        if (async) {
            try (var in = file.getInputStream()) {
                UploadJob job = asyncUploadService.submit(filename, in, retentionMode);
                return ResponseEntity.accepted()
                        .header("Location", "/messages/uploads/" + job.jobId())
                        .body(job);
            } catch (RejectedExecutionException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(IngestResponse.of(
                        List.of(IngestResult.rejected(filename, "ingestion is busy; retry shortly"))));
            } catch (IOException e) {
                log.warn("upload {} could not be spooled", filename, e);
                return ResponseEntity.badRequest().body(IngestResponse.of(List.of(
                        IngestResult.rejected(filename, "could not read file: " + e.getMessage()))));
            }
        }

        try (var in = file.getInputStream()) {
            UploadResponse response = uploadService.ingest(filename, in, retentionMode);
            // Same convention as the JSON endpoint: per-item problems are a 2xx outcome, and only
            // an infrastructure failure gets a retryable status so a client knows to re-send.
            HttpStatus status = response.failed() > 0 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
            return ResponseEntity.status(status).body(response);
        } catch (MessageStreamReader.TooManyMessagesException e) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(IngestResponse.of(
                    List.of(IngestResult.rejected(filename, e.getMessage()))));
        } catch (IOException e) {
            // Malformed JSON part-way through a file is reported here rather than per item: once
            // the parser loses its place there is no reliable next element to resume from, and
            // pretending otherwise would silently drop the remainder.
            log.warn("upload {} could not be read", filename, e);
            return ResponseEntity.badRequest().body(IngestResponse.of(List.of(
                    IngestResult.rejected(filename, "could not read file: " + e.getMessage()))));
        } catch (RuntimeException e) {
            log.warn("upload {} could not be parsed", filename, e);
            return ResponseEntity.badRequest().body(IngestResponse.of(List.of(
                    IngestResult.rejected(filename,
                            "file is not a JSON array or NDJSON of messages: " + e.getMessage()))));
        }
    }
}
