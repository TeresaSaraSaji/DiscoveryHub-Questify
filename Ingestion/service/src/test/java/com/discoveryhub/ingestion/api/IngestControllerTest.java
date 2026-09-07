package com.discoveryhub.ingestion.api;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.ingestion.service.DedupeStore;
import com.discoveryhub.ingestion.service.EventPublisher;
import com.discoveryhub.ingestion.service.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bodies are handed over as JSON rather than as {@code Message} objects on purpose. Binding is
 * where a batch can stop being per-item, so a test that constructs records directly cannot see the
 * failure mode it most needs to.
 */
class IngestControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Message> published = new ArrayList<>();
    private final IngestController controller = new IngestController(
            new IngestService(dedupeStore(), publisher(),
                    Clock.fixed(Instant.parse("2024-05-11T21:37:00Z"), ZoneOffset.UTC)),
            new MessageBatchDecoder(MAPPER),
            3);

    @Test
    void acceptsABatchAtTheLimit() {
        ResponseEntity<IngestResponse> response = controller.ingest(batchOf(3));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().accepted()).isEqualTo(3);
    }

    @Test
    void refusesAnOversizedBatchWholeRatherThanTruncatingIt() {
        ResponseEntity<IngestResponse> response = controller.ingest(batchOf(4));

        // Asserted as 413 rather than as an HttpStatus constant: Spring 7 renamed
        // PAYLOAD_TOO_LARGE to CONTENT_TOO_LARGE and the two are distinct constants, so comparing
        // enums would fail on the rename alone even though the status a client sees never moved.
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().results().getFirst().reason())
                .contains("exceeds the limit of 3");
        // Nothing partially ingested: a client given a partial result for a refused batch has no
        // way to tell which messages were dropped.
        assertThat(published).isEmpty();
    }

    @Test
    void rejectsAnEmptyBatch() {
        assertThat(controller.ingest(List.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.ingest(null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anUnknownEnumValueRejectsOnlyItsOwnMessage() {
        // Previously Jackson abandoned the whole array here and the batch came back as a framework
        // error page, so 249 valid messages were lost to one bad field.
        List<JsonNode> body = new ArrayList<>(batchOf(2));
        body.addFirst(json(message("BAD-TYPE").replace("\"EMAIL\"", "\"VOICEMAIL\"")));

        ResponseEntity<IngestResponse> response = controller.ingest(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().accepted()).isEqualTo(2);
        assertThat(response.getBody().rejected()).isEqualTo(1);
        assertThat(response.getBody().results().getFirst().reason()).contains("type");
        assertThat(published).hasSize(2);
    }

    @Test
    void aNullInsideAnArrayFieldRejectsOnlyItsOwnMessage() {
        // The contracts records copy their list fields with List.copyOf, which throws on a null
        // element. That throw used to happen mid-array and took the rest of the batch with it.
        List<JsonNode> body = new ArrayList<>(batchOf(1));
        body.addFirst(json(message("BAD-TO").replace("[\"bob@firm.test\"]", "[null]")));

        ResponseEntity<IngestResponse> response = controller.ingest(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().accepted()).isEqualTo(1);
        assertThat(response.getBody().rejected()).isEqualTo(1);
        assertThat(published).hasSize(1);
        // Jackson cannot attribute a constructor failure to a field, so the raw reason is only
        // "problem: NullPointerException". A loader needs to be told which field to fix.
        assertThat(response.getBody().results().getFirst().reason())
                .isEqualTo("malformed field 'to': array must not contain null elements");
    }

    @Test
    void anUndecodableMessageStillReportsItsExternalId() {
        // Recovered from the raw JSON, because a loader has to know which message to fix.
        ResponseEntity<IngestResponse> response = controller.ingest(List.of(
                json(message("EXCH-77").replace("\"EMAIL\"", "\"VOICEMAIL\""))));

        assertThat(response.getBody().results().getFirst().externalId()).isEqualTo("EXCH-77");
    }

    @Test
    void aNullElementRejectsOnlyItsOwnSlot() {
        List<JsonNode> body = new ArrayList<>(batchOf(1));
        body.addFirst(MAPPER.nullNode());

        ResponseEntity<IngestResponse> response = controller.ingest(body);

        assertThat(response.getBody().accepted()).isEqualTo(1);
        assertThat(response.getBody().rejected()).isEqualTo(1);
    }

    @Test
    void additiveOptionalFieldsAreTolerated() {
        // message-schema.md allows additive optional fields, so an older P1 must not reject a
        // message from a newer producer.
        ResponseEntity<IngestResponse> response = controller.ingest(List.of(
                json(message("EXCH-NEW").replace("\"body\":\"body\"",
                        "\"body\":\"body\",\"fieldAddedLater\":\"x\""))));

        assertThat(response.getBody().accepted()).isEqualTo(1);
    }

    private static List<JsonNode> batchOf(int n) {
        return IntStream.range(0, n).mapToObj(i -> json(message("BATCH-" + i))).toList();
    }

    private static JsonNode json(String raw) {
        return MAPPER.readTree(raw);
    }

    private static String message(String externalId) {
        return MAPPER.writeValueAsString(new Message(
                null, externalId, "EXCHANGE", MessageType.EMAIL, "alice",
                "alice@firm.test", List.of("bob@firm.test"), List.of(), "subject", "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(), List.of()));
    }

    private static DedupeStore dedupeStore() {
        Set<String> seen = new HashSet<>();
        return new DedupeStore() {
            @Override
            public boolean claim(String externalId) {
                return seen.add(externalId);
            }

            @Override
            public void release(String externalId) {
                seen.remove(externalId);
            }
        };
    }

    private EventPublisher publisher() {
        return new EventPublisher() {
            @Override
            public void publishIngested(Message message) {
                published.add(message);
            }

            @Override
            public void publishAudit(AuditEvent event) {
            }
        };
    }
}
