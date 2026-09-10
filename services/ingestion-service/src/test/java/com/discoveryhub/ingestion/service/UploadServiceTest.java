package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.RetentionLabels;
import com.discoveryhub.ingestion.api.MessageBatchDecoder;
import com.discoveryhub.ingestion.api.MessageStreamReader;
import com.discoveryhub.ingestion.api.RetentionMode;
import com.discoveryhub.ingestion.api.UploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadServiceTest {

    // Mirrors the application mapper. A record's missing component binds as null, which cannot be
    // coerced into a primitive long, so without this an uploaded attachment that omits sizeBytes
    // is rejected outright — for a field the uploader is not expected to know.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
            .build();

    private RecordingPublisher publisher;
    private UploadService service;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        IngestService ingest = new IngestService(new InMemoryDedupeStore(), publisher,
                new InMemoryMessageIdMappingStore(),
                Clock.fixed(Instant.parse("2024-05-11T21:37:00Z"), ZoneOffset.UTC));
        service = new UploadService(
                new MessageStreamReader(MAPPER), new MessageBatchDecoder(MAPPER),
                new AttachmentHydrator(), ingest, 2, 1000, 3);
    }

    private UploadResponse upload(String filename, String content) throws Exception {
        return service.ingest(filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void ingestsNdjson() throws Exception {
        UploadResponse response = upload("corpus.ndjson",
                json("EXCH-1", "first") + "\n" + json("EXCH-2", "second") + "\n");

        assertThat(response.totalMessages()).isEqualTo(2);
        assertThat(response.accepted()).isEqualTo(2);
        assertThat(publisher.ingested).hasSize(2);
    }

    @Test
    void ingestsAJsonArrayToo() throws Exception {
        // The frontend will assemble an array; the corpus generator writes NDJSON. Both are
        // formats people already have, so both are read rather than one being refused.
        UploadResponse response = upload("batch.json",
                "[" + json("EXCH-1", "first") + "," + json("EXCH-2", "second") + "]");

        assertThat(response.totalMessages()).isEqualTo(2);
        assertThat(response.accepted()).isEqualTo(2);
    }

    @Test
    void reUploadingTheSameFileAddsNothing() throws Exception {
        String file = json("EXCH-1", "first") + "\n" + json("EXCH-2", "second") + "\n";

        assertThat(upload("corpus.ndjson", file).accepted()).isEqualTo(2);
        UploadResponse second = upload("corpus.ndjson", file);

        assertThat(second.accepted()).isZero();
        assertThat(second.duplicates()).isEqualTo(2);
        assertThat(publisher.ingested).hasSize(2);
    }

    @Test
    void aFileOfIdenticalMessagesUnderNewIdsStillDedupes() throws Exception {
        // Someone re-exports the same mailbox and every message gets a fresh source key. Only the
        // content fingerprint catches this.
        UploadResponse response = upload("re-export.ndjson",
                json("EXCH-1", "same") + "\n" + json("EXCH-99", "same") + "\n");

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.duplicates()).isEqualTo(1);
    }

    @Test
    void computesAttachmentChecksumsTheUploaderCouldNotKnow() throws Exception {
        String content = "invoice,amount\nNORTHGATE-1,12400000\n";
        String base64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));

        UploadResponse response = upload("with-attachment.ndjson",
                jsonWithAttachment("EXCH-att", base64, null, null));

        assertThat(response.accepted()).isEqualTo(1);
        var attachment = publisher.ingested.getFirst().attachments().getFirst();
        assertThat(attachment.sha256()).isEqualTo(sha256(content));
        assertThat(attachment.sizeBytes()).isEqualTo(content.length());
    }

    @Test
    void aChecksumTheUploaderDidSupplyIsStillVerified() throws Exception {
        // Hydration fills gaps, it never overwrites a claim — otherwise the upload path could be
        // used to smuggle a bad checksum past the integrity check.
        String base64 = Base64.getEncoder().encodeToString("real".getBytes(StandardCharsets.UTF_8));

        UploadResponse response = upload("bad-hash.ndjson",
                jsonWithAttachment("EXCH-bad", base64, "0".repeat(64), 4L));

        assertThat(response.accepted()).isZero();
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.problems().getFirst().reason()).contains("sha256 mismatch");
    }

    @Test
    void oneBadLineDoesNotLoseTheRest() throws Exception {
        UploadResponse response = upload("mixed.ndjson",
                json("EXCH-1", "first") + "\n"
                        + json("EXCH-2", "second").replace("\"EMAIL\"", "\"VOICEMAIL\"") + "\n"
                        + json("EXCH-3", "third") + "\n");

        assertThat(response.accepted()).isEqualTo(2);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.problems().getFirst().reason()).contains("type");
    }

    @Test
    void problemDetailIsCappedButCountsStayExact() throws Exception {
        StringBuilder file = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            file.append(json("EXCH-" + i, "body " + i).replace("\"EMAIL\"", "\"VOICEMAIL\"")).append('\n');
        }

        UploadResponse response = upload("all-bad.ndjson", file.toString());

        assertThat(response.rejected()).isEqualTo(10);
        assertThat(response.problems()).hasSize(3);
        assertThat(response.problemsTruncated()).isTrue();
    }

    @Test
    void refusesAFileWithMoreMessagesThanAllowed() {
        UploadService small = new UploadService(
                new MessageStreamReader(MAPPER), new MessageBatchDecoder(MAPPER),
                new AttachmentHydrator(),
                new IngestService(new InMemoryDedupeStore(), publisher,
                        new InMemoryMessageIdMappingStore(),
                        Clock.fixed(Instant.parse("2024-05-11T21:37:00Z"), ZoneOffset.UTC)),
                2, 2, 10);

        String file = json("EXCH-1", "a") + "\n" + json("EXCH-2", "b") + "\n" + json("EXCH-3", "c");

        assertThatThrownBy(() -> small.ingest("big.ndjson",
                new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(MessageStreamReader.TooManyMessagesException.class)
                .hasMessageContaining("more than 2");
    }

    @Test
    void chunkingDoesNotChangeTheOutcome() throws Exception {
        // Chunk size is 2 and this is 5 messages, so the boundary falls mid-file.
        StringBuilder file = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            file.append(json("EXCH-" + i, "body " + i)).append('\n');
        }

        UploadResponse response = upload("five.ndjson", file.toString());

        assertThat(response.totalMessages()).isEqualTo(5);
        assertThat(response.accepted()).isEqualTo(5);
        assertThat(publisher.ingested).extracting(Message::externalId)
                .containsExactly("EXCH-0", "EXCH-1", "EXCH-2", "EXCH-3", "EXCH-4");
    }

    @Test
    void normalRetentionModeAddsNoLabel() throws Exception {
        service.ingest("f.json",
                new ByteArrayInputStream(json("EXCH-DEMO-1", "body").getBytes(StandardCharsets.UTF_8)),
                RetentionMode.NORMAL);

        assertThat(publisher.ingested).hasSize(1);
        assertThat(publisher.ingested.getFirst().labels()).doesNotContain(RetentionLabels.DEMO_RETENTION);
    }

    @Test
    void demoRetentionModeTagsEveryMessageInTheUpload() throws Exception {
        UploadResponse response = service.ingest("f.json",
                new ByteArrayInputStream(
                        (json("EXCH-DEMO-2", "a") + "\n" + json("EXCH-DEMO-3", "b") + "\n")
                                .getBytes(StandardCharsets.UTF_8)),
                RetentionMode.DEMO);

        assertThat(response.accepted()).isEqualTo(2);
        assertThat(publisher.ingested).hasSize(2);
        assertThat(publisher.ingested).allSatisfy(m ->
                assertThat(m.labels()).contains(RetentionLabels.DEMO_RETENTION));
    }

    @Test
    void demoRetentionModeDoesNotChangeDedupeOrAnyOtherField() throws Exception {
        // The label must be invisible to identity: uploading in DEMO mode must dedupe exactly as
        // NORMAL mode would against the same externalId, and every other field must be untouched.
        upload("first.json", json("EXCH-DEMO-4", "body"));
        UploadResponse second = service.ingest("second.json",
                new ByteArrayInputStream(json("EXCH-DEMO-4", "body").getBytes(StandardCharsets.UTF_8)),
                RetentionMode.DEMO);

        assertThat(second.duplicates()).isEqualTo(1);
        assertThat(second.accepted()).isZero();
        Message original = publisher.ingested.getFirst();
        assertThat(original.externalId()).isEqualTo("EXCH-DEMO-4");
        assertThat(original.body()).isEqualTo("body");
    }

    private static String json(String externalId, String body) {
        return """
                {"externalId":"%s","source":"EXCHANGE","type":"EMAIL","custodianId":"alice",
                 "from":"alice@firm.test","to":["bob@firm.test"],"subject":"Q2 numbers",
                 "body":"%s","sentAt":"2024-05-11T21:37:00Z","threadId":"thread-1"}
                """.formatted(externalId, body).replace("\n", "");
    }

    /**
     * A null {@code sha256} or {@code size} omits the field entirely rather than sending zero.
     * That distinction matters: a real uploader leaves them out, and sending {@code "sizeBytes":0}
     * instead is what hid a deserialization failure on the primitive from these tests once already.
     */
    private static String jsonWithAttachment(String externalId, String base64, String sha256, Long size) {
        String hash = sha256 == null ? "" : "\"sha256\":\"%s\",".formatted(sha256);
        String bytes = size == null ? "" : "\"sizeBytes\":%d,".formatted(size);
        return """
                {"externalId":"%s","source":"EXCHANGE","type":"EMAIL","custodianId":"alice",
                 "from":"alice@firm.test","to":["bob@firm.test"],"subject":"invoice",
                 "body":"see attached","sentAt":"2024-05-11T21:37:00Z","threadId":"thread-1",
                 "attachments":[{"filename":"invoice.csv","contentType":"text/csv",
                 %s%s"contentBase64":"%s"}]}
                """.formatted(externalId, hash, bytes, base64).replace("\n", "");
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
