package com.discoveryhub.ingestion.service;

import com.discoveryhub.ingestion.api.UploadJob;
import com.discoveryhub.ingestion.api.UploadResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UploadJobStoreTest {

    private static final Instant NOW = Instant.parse("2024-05-11T21:37:00Z");

    private static UploadResponse result() {
        return new UploadResponse("f.ndjson", 1, 1, 0, 0, 0, List.of(), false);
    }

    @Test
    void findsAJobItWasGiven() {
        UploadJobStore store = new UploadJobStore(Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofHours(2));
        store.put(UploadJob.running("job-1", "f.ndjson", NOW));

        assertThat(store.find("job-1")).isPresent();
        assertThat(store.find("nope")).isEmpty();
    }

    @Test
    void evictsFinishedJobsPastTheRetentionWindow() {
        UploadJobStore store = new UploadJobStore(Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofHours(2));
        store.put(UploadJob.running("old", "f.ndjson", NOW.minus(Duration.ofHours(5)))
                .completed(result(), NOW.minus(Duration.ofHours(3))));
        store.put(UploadJob.running("recent", "f.ndjson", NOW).completed(result(), NOW));

        assertThat(store.find("old")).isEmpty();
        assertThat(store.find("recent")).isPresent();
    }

    @Test
    void staysBoundedWhenJobsKeepArriving() {
        UploadJobStore store = new UploadJobStore(Clock.fixed(NOW, ZoneOffset.UTC), 3, Duration.ofHours(2));
        for (int i = 0; i < 20; i++) {
            store.put(UploadJob.running("job-" + i, "f.ndjson", NOW.plusSeconds(i))
                    .completed(result(), NOW.plusSeconds(i)));
        }
        assertThat(store.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void neverEvictsAJobThatIsStillRunning() {
        // Dropping an in-flight job to make room would report "unknown job" for an upload that is
        // actively ingesting, which is worse than exceeding the bound.
        UploadJobStore store = new UploadJobStore(Clock.fixed(NOW, ZoneOffset.UTC), 2, Duration.ofHours(2));
        store.put(UploadJob.running("in-flight", "big.ndjson", NOW.minus(Duration.ofHours(1))));
        for (int i = 0; i < 10; i++) {
            store.put(UploadJob.running("done-" + i, "f.ndjson", NOW.plusSeconds(i))
                    .completed(result(), NOW.plusSeconds(i)));
        }

        assertThat(store.find("in-flight")).isPresent();
    }
}
