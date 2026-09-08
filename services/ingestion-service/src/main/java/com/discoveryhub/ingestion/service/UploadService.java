package com.discoveryhub.ingestion.service;

import com.discoveryhub.ingestion.api.IngestResponse;
import com.discoveryhub.ingestion.api.IngestResult;
import com.discoveryhub.ingestion.api.MessageBatch;
import com.discoveryhub.ingestion.api.MessageBatchDecoder;
import com.discoveryhub.ingestion.api.MessageStreamReader;
import com.discoveryhub.ingestion.api.UploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Ingests an uploaded file by streaming it through the same {@link IngestService} the HTTP API
 * uses.
 *
 * <p>Reusing that path is the point. Dedupe on both keys, per-item validation, attachment
 * integrity and audit events all apply to an upload exactly as they do to an API call, with no
 * second implementation to keep in step. In particular, uploading the same file twice is correctly
 * recognised as duplicates rather than doubling the archive — which is what a person will do by
 * accident, and probably during the demo.
 *
 * <p>The one difference is {@link AttachmentHydrator}: a source system attests to its checksums, an
 * uploaded file has nobody to attest for it, so missing checksums are computed here.
 *
 * <p>Chunked rather than accumulated. A file may hold far more messages than a single API batch
 * allows, and each chunk is published before the next is parsed, so peak memory follows the chunk
 * size rather than the file size.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final MessageStreamReader reader;
    private final MessageBatchDecoder decoder;
    private final AttachmentHydrator hydrator;
    private final IngestService ingestService;
    private final int chunkSize;
    private final int maxMessages;
    private final int maxReportedProblems;

    public UploadService(MessageStreamReader reader,
                         MessageBatchDecoder decoder,
                         AttachmentHydrator hydrator,
                         IngestService ingestService,
                         @Value("${discoveryhub.ingestion.upload.chunk-size:200}") int chunkSize,
                         @Value("${discoveryhub.ingestion.upload.max-messages:50000}") int maxMessages,
                         @Value("${discoveryhub.ingestion.upload.max-reported-problems:50}") int maxReportedProblems) {
        this.reader = reader;
        this.decoder = decoder;
        this.hydrator = hydrator;
        this.ingestService = ingestService;
        this.chunkSize = chunkSize;
        this.maxMessages = maxMessages;
        this.maxReportedProblems = maxReportedProblems;
    }

    public UploadResponse ingest(String filename, InputStream in) throws IOException {
        return ingest(filename, in, processed -> { });
    }

    /**
     * @param onProgress called after each chunk with the running count, so an asynchronous caller
     *                   can report progress instead of presenting a black box
     */
    public UploadResponse ingest(String filename, InputStream in, LongConsumer onProgress)
            throws IOException {
        Tally tally = new Tally();
        long started = System.currentTimeMillis();

        int total = reader.readInChunks(in, chunkSize, maxMessages, chunk -> {
            MessageBatch decoded = decoder.decode(chunk);
            IngestResponse response = ingestService.ingest(hydrate(decoded));
            tally.add(response);
            onProgress.accept(tally.processed());
        });

        log.info("upload {}: {} messages, {} accepted, {} duplicates, {} rejected, {} failed in {} ms",
                filename, total, tally.accepted, tally.duplicates, tally.rejected, tally.failed,
                System.currentTimeMillis() - started);

        return new UploadResponse(filename, total, tally.accepted, tally.duplicates,
                tally.rejected, tally.failed, List.copyOf(tally.problems), tally.truncated);
    }

    /** Fills in missing attachment checksums; entries that never decoded pass through untouched. */
    private MessageBatch hydrate(MessageBatch batch) {
        List<MessageBatch.Entry> hydrated = new ArrayList<>(batch.size());
        for (MessageBatch.Entry entry : batch.entries()) {
            hydrated.add(entry.rejection() != null
                    ? entry
                    : MessageBatch.Entry.decoded(hydrator.hydrate(entry.message())));
        }
        return new MessageBatch(hydrated);
    }

    private final class Tally {
        private int accepted;
        private int duplicates;
        private int rejected;
        private int failed;
        private boolean truncated;
        private final List<IngestResult> problems = new ArrayList<>();

        long processed() {
            return (long) accepted + duplicates + rejected + failed;
        }

        void add(IngestResponse response) {
            accepted += response.accepted();
            duplicates += response.duplicates();
            rejected += response.rejected();
            failed += response.failed();
            for (IngestResult result : response.results()) {
                if (result.outcome() == IngestResult.Outcome.ACCEPTED
                        || result.outcome() == IngestResult.Outcome.DUPLICATE) {
                    continue;
                }
                if (problems.size() < maxReportedProblems) {
                    problems.add(result);
                } else {
                    truncated = true;
                }
            }
        }
    }
}
