package com.discoveryhub.export.messaging;

import com.discoveryhub.contracts.Topics;
import com.discoveryhub.export.service.ExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The actual worker side of FR-6.2's "asynchronous background job". {@code POST /exports} only
 * writes a {@code QUEUED} row and publishes here; the real work — fetching messages, building the
 * package, uploading it — happens on this consumer thread, off the request that created the job.
 */
@Component
public class ExportJobListener {

    private static final Logger log = LoggerFactory.getLogger(ExportJobListener.class);

    private final ExportService exportService;
    private final ObjectMapper json;

    public ExportJobListener(ExportService exportService, ObjectMapper json) {
        this.exportService = exportService;
        this.json = json;
    }

    @KafkaListener(topics = Topics.EXPORT_JOBS, groupId = "p5-export")
    public void onJobRequested(String payload) {
        ExportJobRequested request;
        try {
            request = json.readValue(payload, ExportJobRequested.class);
        } catch (JacksonException ex) {
            log.warn("skipping unparseable export.jobs payload: {}", ex.getMessage());
            return;
        }
        exportService.process(request.jobId());
    }
}
