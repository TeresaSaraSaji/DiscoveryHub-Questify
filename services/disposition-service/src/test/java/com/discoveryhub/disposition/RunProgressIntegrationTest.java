package com.discoveryhub.disposition;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.domain.DispositionRunEntity;
import com.discoveryhub.disposition.domain.DispositionStatus;
import com.discoveryhub.disposition.domain.TriggerSource;
import com.discoveryhub.disposition.repository.DispositionItemRepository;
import com.discoveryhub.disposition.repository.DispositionRunRepository;
import com.discoveryhub.disposition.run.DispositionService;
import com.discoveryhub.disposition.run.RetentionPolicyService;
import com.discoveryhub.disposition.support.ArchiveSchema;
import com.discoveryhub.disposition.support.Containers;
import com.discoveryhub.disposition.support.HoldServiceStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Triggering a sweep without waiting for it, and watching it while it runs (FR-8.1, NFR-3).
 *
 * <p>A sweep makes one HTTP call to P4 per candidate and considers up to {@code batch-size} of
 * them, so the synchronous trigger holds a connection open for the length of the work. Over the
 * full corpus that is how the browser times out on the bulk operation NFR-3 says must not time
 * out, which is why {@code ?async=true} exists and why it has to be tested through the real HTTP
 * stack rather than by calling the service — the assertion is about the response arriving before
 * the work finishes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunProgressIntegrationTest {

    private static final HoldServiceStub P4 = new HoldServiceStub();

    private static final Instant LONG_AGO = Instant.parse("2017-03-01T00:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", Containers.DISPOSITION_DB::getJdbcUrl);
        registry.add("spring.datasource.username", Containers.DISPOSITION_DB::getUsername);
        registry.add("spring.datasource.password", Containers.DISPOSITION_DB::getPassword);

        registry.add("discoveryhub.disposition.archive.datasource.url", Containers.ARCHIVE_DB::getJdbcUrl);
        registry.add("discoveryhub.disposition.archive.datasource.username", Containers.ARCHIVE_DB::getUsername);
        registry.add("discoveryhub.disposition.archive.datasource.password", Containers.ARCHIVE_DB::getPassword);

        registry.add("spring.kafka.bootstrap-servers", Containers.KAFKA::getBootstrapServers);
        // ARCHIVE_DB mode has no implementation now that P2 splits into MongoDB (content) and its
        // own slim Postgres (hold/retention); KAFKA is the only mode. P2 is not running here, so
        // nothing ever answers on disposition.results — but that is fine for what this test
        // watches: the sweep counts a published DELETE_REQUESTED as deleted immediately, which is
        // the same number these assertions checked under ARCHIVE_DB mode.
        registry.add("discoveryhub.disposition.delete-mode", () -> "KAFKA");
        registry.add("discoveryhub.disposition.archive.hold-check-base-url", P4::baseUrl);
        registry.add("discoveryhub.disposition.schedule.enabled", () -> "false");
        registry.add("spring.kafka.consumer.group-id", () -> "test-" + java.util.UUID.randomUUID());
    }

    /**
     * The JDK client rather than a Spring test client: Boot 4 dropped {@code TestRestTemplate},
     * and for a server-sent-event stream a plain body-as-string read is exactly what is wanted —
     * it returns when the server closes the stream, which is the behaviour under test.
     */
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired DispositionService disposition;
    @Autowired RetentionPolicyService retention;
    @Autowired DispositionRunRepository runs;
    @Autowired DispositionItemRepository items;

    @Autowired
    @Qualifier("archiveJdbcTemplate")
    JdbcTemplate archive;

    @LocalServerPort int port;

    @BeforeAll
    void createArchiveSchema() {
        ArchiveSchema.create(archive);
    }

    @AfterAll
    static void stopStub() {
        P4.stop();
    }

    @BeforeEach
    void reset() {
        ArchiveSchema.truncate(archive);
        items.deleteAll();
        runs.deleteAll();
        P4.reset();
        retention.updatePeriod(MessageType.EMAIL, Duration.ofDays(2555), "test");
    }

    @Test
    void anAsyncTriggerAnswers202WithAQueuedRunBeforeTheSweepFinishes() {
        insert(20);

        HttpResponse<String> response = post("/disposition/runs?async=true");

        assertThat(response.statusCode()).isEqualTo(202);
        String runId = runIdOf(response.body());
        // The row exists the moment the caller is answered, so a crash before the sweep starts
        // still leaves evidence that one was ordered.
        assertThat(runs.findById(runId)).isPresent();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            DispositionRunEntity run = runs.findById(runId).orElseThrow();
            assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
            assertThat(run.getDeletedCount()).isEqualTo(20);
        });
    }

    @Test
    void progressReportsTheFinishedRun() {
        insert(3);
        disposition.run(TriggerSource.MANUAL, false, "test");

        HttpResponse<String> response = get("/disposition/runs/progress");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"COMPLETED\"");
        assertThat(response.body()).contains("\"deleted\":3");
        assertThat(response.body()).contains("\"percent\":100");
    }

    /**
     * The flow the UI actually performs: open the stream, <i>then</i> start a sweep, and watch it
     * from beginning to end.
     *
     * <p>This is here because the first version of the stream failed exactly this sequence. It
     * closed as soon as the snapshot it had on hand was terminal, which is the state between runs
     * — so a client that connected and then clicked Run was disconnected before the run started
     * and saw the previous sweep's totals instead. An end-to-end run against the compose stack
     * showed it; no unit test would have.
     */
    @Test
    void theStreamStaysOpenAcrossTheStartOfTheNextRunAndClosesWhenItEnds() throws Exception {
        // A finished run first, so `current` is terminal when the stream connects — the exact
        // condition that used to hang up on the client.
        insert(1);
        disposition.run(TriggerSource.MANUAL, false, "warm-up");

        insert(3);
        CompletableFuture<HttpResponse<String>> stream = http.sendAsync(
                HttpRequest.newBuilder(uri("/disposition/runs/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Give the emitter time to register before the run it is supposed to observe begins.
        Thread.sleep(500);
        disposition.run(TriggerSource.MANUAL, false, "watched");

        // Returns only because the server closes the stream when the run ends.
        String body = stream.get(30, TimeUnit.SECONDS).body();
        assertThat(body).contains("event:progress");
        assertThat(body).contains("\"status\":\"RUNNING\"");
        assertThat(body).contains("\"terminal\":true");
        // The run it watched, not the warm-up: three candidates, not one.
        assertThat(body).contains("\"total\":3");
    }

    /** A client attaching between runs is told how the last one ended, immediately. */
    @Test
    void theStreamSendsTheLastRunsSnapshotOnConnect() throws Exception {
        insert(2);
        disposition.run(TriggerSource.MANUAL, false, "test");

        CompletableFuture<HttpResponse<String>> stream = http.sendAsync(
                HttpRequest.newBuilder(uri("/disposition/runs/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(500);
        // Nothing else will run, so close the stream from this side to read what was buffered.
        disposition.run(TriggerSource.MANUAL, false, "closer");

        assertThat(stream.get(30, TimeUnit.SECONDS).body())
                .contains("\"deleted\":2")
                .contains("\"status\":\"COMPLETED\"");
    }

    /** Two sweeps would race on the same rows, so the second is refused rather than queued. */
    @Test
    void aSecondAsyncTriggerWhileOneIsRunningIsRefused() {
        // Every candidate costs a round trip to the P4 stub, so this run lasts long enough for the
        // second request to land while it is still going.
        insert(200);
        HttpResponse<String> first = post("/disposition/runs?async=true");
        assertThat(first.statusCode()).isEqualTo(202);

        assertThat(post("/disposition/runs?async=true").statusCode()).isEqualTo(409);

        String runId = runIdOf(first.body());
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(runs.findById(runId).orElseThrow().getStatus())
                        .isEqualTo(DispositionStatus.COMPLETED));
    }

    private HttpResponse<String> post(String path) {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("request failed: " + request.uri(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting on " + request.uri(), ex);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** The run row comes back as JSON; only the id is needed, so do not drag in a parser for it. */
    private String runIdOf(String body) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"runId\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("runId in %s", body).isTrue();
        return matcher.group(1);
    }

    private void insert(int count) {
        for (int i = 0; i < count; i++) {
            ArchiveSchema.insertMessage(archive, "msg-" + i, "EXCH-" + i, "cust-1", "EMAIL",
                    LONG_AGO.plus(Duration.ofSeconds(i)), false);
        }
    }
}
