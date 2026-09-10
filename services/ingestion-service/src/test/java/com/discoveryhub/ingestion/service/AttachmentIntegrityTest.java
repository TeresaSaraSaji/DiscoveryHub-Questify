package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.ingestion.api.IngestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentIntegrityTest {

    private static final String CONTENT = "SGVsbG8gRGlzY292ZXJ5SHVi";
    private static final int CONTENT_LENGTH = 18;
    private static final String CORRECT_SHA =
            "679dd838cf2e83cace183c0c2bb5dcd43cc42997944863dcac878cb0a60c9e18";

    private RecordingPublisher publisher;
    private IngestService service;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        service = new IngestService(new InMemoryDedupeStore(), publisher,
                new InMemoryMessageIdMappingStore(),
                Clock.fixed(Instant.parse("2024-05-11T21:37:00Z"), ZoneOffset.UTC));
    }

    @Test
    void acceptsAnAttachmentWhoseHashAndSizeMatchItsContent() {
        IngestResponse response = service.ingest(List.of(
                withAttachment("OK-1", CONTENT, CONTENT_LENGTH, CORRECT_SHA)));

        assertThat(response.accepted()).isEqualTo(1);
    }

    @Test
    void rejectsATamperedSha256() {
        // The chain of custody is anchored on this value (FR-6.5). A wrong hash accepted here
        // would only surface as a failed export verification at the end of the demo.
        String wrong = "0".repeat(64);

        IngestResponse response = service.ingest(List.of(
                withAttachment("BAD-1", CONTENT, CONTENT_LENGTH, wrong)));

        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.results().getFirst().reason())
                .contains("sha256 mismatch")
                .contains(CORRECT_SHA);
        assertThat(publisher.ingested).isEmpty();
    }

    @Test
    void rejectsAWrongSizeBytes() {
        IngestResponse response = service.ingest(List.of(
                withAttachment("BAD-2", CONTENT, 999, CORRECT_SHA)));

        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.results().getFirst().reason()).contains("sizeBytes");
    }

    @Test
    void rejectsUndecodableContent() {
        IngestResponse response = service.ingest(List.of(
                withAttachment("BAD-3", "not base64 at all!!", 5, CORRECT_SHA)));

        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.results().getFirst().reason()).contains("not valid base64");
    }

    @Test
    void rejectsContentWithoutAHash() {
        IngestResponse response = service.ingest(List.of(
                withAttachment("BAD-4", CONTENT, CONTENT_LENGTH, null)));

        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.results().getFirst().reason()).contains("sha256 is required");
    }

    @Test
    void ignoresMetadataOnlyAttachments() {
        // P2's read API strips contentBase64, so a message replayed from storage carries metadata
        // only. P1 verifies what it can see the bytes of and nothing more.
        IngestResponse response = service.ingest(List.of(
                withAttachment("META-1", null, 1024, "whatever-p2-recorded")));

        assertThat(response.accepted()).isEqualTo(1);
    }

    private static Message withAttachment(String externalId, String contentBase64, long sizeBytes,
                                          String sha256) {
        return new Message(
                null, externalId, "EXCHANGE", MessageType.EMAIL, "alice", "alice@firm.test",
                List.of("bob@firm.test"), List.of(), "Q2 numbers", "see attached",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null,
                List.of(new Attachment(null, "q2.csv", "text/csv", sizeBytes, sha256, contentBase64)),
                List.of());
    }
}
