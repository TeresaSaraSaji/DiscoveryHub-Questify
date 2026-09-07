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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IngestControllerTest {

    private final List<Message> published = new ArrayList<>();
    private final IngestController controller = new IngestController(
            new IngestService(dedupeStore(), publisher(),
                    Clock.fixed(Instant.parse("2024-05-11T21:37:00Z"), ZoneOffset.UTC)),
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

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
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

    private static List<Message> batchOf(int n) {
        return IntStream.range(0, n).mapToObj(i -> new Message(
                        null, "BATCH-" + i, "EXCHANGE", MessageType.EMAIL, "alice",
                        "alice@firm.test", List.of("bob@firm.test"), List.of(), "subject", "body",
                        Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(), List.of()))
                .toList();
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
