package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestStatusTest {

    private IngestService service;

    @BeforeEach
    void setUp() {
        service = new IngestService(new InMemoryDedupeStore(), new RecordingPublisher(),
                new InMemoryMessageIdMappingStore(),
                Clock.fixed(Instant.parse("2024-05-11T21:37:00Z"), ZoneOffset.UTC));
    }

    @Test
    void reportsWhetherAnExternalIdHasBeenIngested() {
        assertThat(service.hasIngested("EXCH-1")).isFalse();
        service.ingest(List.of(email("EXCH-1")));
        assertThat(service.hasIngested("EXCH-1")).isTrue();
    }

    @Test
    void checkingAnIdDoesNotClaimIt() {
        // A read must never have the side effect of marking something as seen, or asking about a
        // message would stop it from ever being accepted.
        assertThat(service.hasIngested("EXCH-2")).isFalse();
        assertThat(service.hasIngested("EXCH-2")).isFalse();

        assertThat(service.ingest(List.of(email("EXCH-2"))).accepted()).isEqualTo(1);
    }

    @Test
    void countsEveryOutcome() {
        service.ingest(List.of(email("EXCH-1")));
        service.ingest(List.of(email("EXCH-1")));
        service.ingest(List.of(new Message(
                null, "EXCH-bad", "EXCHANGE", MessageType.EMAIL, null, "a@firm.test",
                List.of(), List.of(), "s", "b", Instant.parse("2024-05-11T21:37:00Z"),
                "t", null, List.of(), List.of())));

        IngestStats stats = service.stats();
        assertThat(stats.accepted()).isEqualTo(1);
        assertThat(stats.duplicates()).isEqualTo(1);
        assertThat(stats.rejected()).isEqualTo(1);
        assertThat(stats.total()).isEqualTo(3);
    }

    private static Message email(String externalId) {
        return new Message(
                null, externalId, "EXCHANGE", MessageType.EMAIL, "alice", "alice@firm.test",
                List.of("bob@firm.test"), List.of(), "Q2 numbers", "body of " + externalId,
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(), List.of());
    }
}
