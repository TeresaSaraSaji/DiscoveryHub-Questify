package com.discoveryhub.ingestion.api;

import com.discoveryhub.ingestion.service.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

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
@RestController
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(path = "/messages/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(IngestResponse.of(List.of(
                    IngestResult.rejected(null, "no file uploaded, or the file is empty"))));
        }

        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        try (var in = file.getInputStream()) {
            UploadResponse response = uploadService.ingest(filename, in);
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
