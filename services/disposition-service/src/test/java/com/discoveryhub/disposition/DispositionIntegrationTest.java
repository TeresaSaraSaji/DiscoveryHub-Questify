package com.discoveryhub.disposition;

import com.discoveryhub.contracts.DeleteCommand;
import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.contracts.Topics;
import com.discoveryhub.disposition.domain.DispositionItemEntity;
import com.discoveryhub.disposition.domain.DispositionOutcome;
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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The disposition sweep against real PostgreSQL — both databases, real Flyway migrations, real
 * entity validation, real SQL.
 *
 * <p>Everything below is only meaningfully testable here. The unit tests pin the decision logic
 * with mocks, which is the right tool for the guards, but they cannot catch the four things most
 * likely to break this service in a demo:
 *
 * <ul>
 *   <li><b>The migrations and the entities disagreeing.</b> {@code ddl-auto: validate} compares
 *       every entity against the live schema at startup, so a column this service's migrations
 *       never created is a startup failure — one that only surfaces when a real Postgres is
 *       behind it. Booting the context here <i>is</i> that assertion.</li>
 *   <li><b>The eligibility SQL.</b> {@code JdbcArchiveGateway} builds a per-type OR-group by hand
 *       against another service's table. Pairing the wrong cutoff with the wrong type would delete
 *       three-year-old email, and no mock would notice.</li>
 *   <li><b>Deletions actually happening.</b> A mocked deleter proves the sweep called it. Only a
 *       real database proves the row is gone — and, for a held message, that it is still there.</li>
 *   <li><b>A runtime policy change taking effect.</b> FR-5.1's headline requirement is that
 *       retention is settable to minutes for the demo without a redeploy. That spans an HTTP call,
 *       a table write and the next sweep's cutoff arithmetic.</li>
 * </ul>
 *
 * <p>Runs in {@code KAFKA} delete mode — the only mode this service ships with, now that P2 splits
 * message content into MongoDB and hold/retention bookkeeping into its own slim Postgres, so a
 * direct {@code ARCHIVE_DB} write has no single table left to target. P2 is not running here, so
 * this test plays P2 with a background consumer that deletes the row and answers on
 * {@code disposition.results}, the same stand-in {@link DeleteLoopKafkaIntegrationTest} uses —
 * which means every assertion about whether a row actually left the archive has to {@code await()}
 * that round trip rather than read it back the instant {@code disposition.run} returns.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DispositionIntegrationTest {

    private static final HoldServiceStub P4 = new HoldServiceStub();

    /** Older than every retention period in play, so eligibility is never accidental. */
    private static final Instant LONG_AGO = Instant.parse("2017-03-01T00:00:00Z");

    /** Recent enough that no sensible policy makes it eligible. */
    private static final Instant YESTERDAY = Instant.now().minus(Duration.ofDays(1));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", Containers.DISPOSITION_DB::getJdbcUrl);
        registry.add("spring.datasource.username", Containers.DISPOSITION_DB::getUsername);
        registry.add("spring.datasource.password", Containers.DISPOSITION_DB::getPassword);

        registry.add("discoveryhub.disposition.archive.datasource.url", Containers.ARCHIVE_DB::getJdbcUrl);
        registry.add("discoveryhub.disposition.archive.datasource.username", Containers.ARCHIVE_DB::getUsername);
        registry.add("discoveryhub.disposition.archive.datasource.password", Containers.ARCHIVE_DB::getPassword);

        registry.add("spring.kafka.bootstrap-servers", Containers.KAFKA::getBootstrapServers);
        registry.add("discoveryhub.disposition.delete-mode", () -> "KAFKA");
        registry.add("discoveryhub.disposition.archive.hold-check-base-url", P4::baseUrl);
        // Every sweep in this class is triggered explicitly. A cron firing mid-test would delete
        // another test's fixtures and the failure would look like a logic bug.
        registry.add("discoveryhub.disposition.schedule.enabled", () -> "false");
        // Small enough that the bounding is observable with a handful of fixtures rather than 500.
        registry.add("discoveryhub.disposition.batch-size", () -> "5");
        // A group of its own per run, so a rerun does not start from another run's committed
        // offsets and quietly skip a command the background P2 stand-in is supposed to answer.
        registry.add("spring.kafka.consumer.group-id", () -> "test-" + UUID.randomUUID());
    }

    @Autowired DispositionService disposition;
    @Autowired RetentionPolicyService retention;
    @Autowired DispositionRunRepository runs;
    @Autowired DispositionItemRepository items;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;

    @Autowired
    @Qualifier("archiveJdbcTemplate")
    JdbcTemplate archive;

    private ExecutorService p2StandIn;
    private KafkaConsumer<String, String> commandConsumer;
    private volatile boolean running;

    @BeforeAll
    void createArchiveSchemaAndStartP2StandIn() {
        ArchiveSchema.create(archive);

        commandConsumer = new KafkaConsumer<>(consumerProps());
        commandConsumer.subscribe(List.of(Topics.DISPOSITION_COMMANDS));
        running = true;
        p2StandIn = Executors.newSingleThreadExecutor();
        p2StandIn.submit(this::runP2StandIn);
    }

    @AfterAll
    void tearDown() {
        running = false;
        commandConsumer.wakeup();
        p2StandIn.shutdown();
        try {
            p2StandIn.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        P4.stop();
    }

    @BeforeEach
    void reset() {
        ArchiveSchema.truncate(archive);
        items.deleteAll();
        runs.deleteAll();
        P4.reset();
        // Back to the shipped defaults: seven years for email, three for chat. Individual tests
        // that care about the boundary set their own.
        retention.updatePeriod(MessageType.EMAIL, Duration.ofDays(2555), "test");
        retention.updatePeriod(MessageType.CHAT, Duration.ofDays(1095), "test");
    }

    /**
     * Stands in for P2 for the whole class: every candidate this service's own guards let through
     * to a publish has already been decided not-held (see {@code DispositionService.decide}), so
     * unlike {@link DeleteLoopKafkaIntegrationTest} this does not need to re-run the hold guard —
     * it only has to prove the row actually leaves {@code message_hold_status} and that a receipt
     * comes back, which is what "deletions actually happening" means for the KAFKA path.
     */
    private void runP2StandIn() {
        try {
            while (running) {
                ConsumerRecords<String, String> records = commandConsumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    DeleteCommand command = json.readValue(record.value(), DeleteCommand.class);
                    int deletedRows = archive.update(
                            "DELETE FROM message_hold_status WHERE message_id = ?", command.messageId());
                    DeleteReceipt.Outcome outcome = deletedRows > 0
                            ? DeleteReceipt.Outcome.DELETED : DeleteReceipt.Outcome.NOT_FOUND;
                    DeleteReceipt receipt = new DeleteReceipt(command.runId(), command.messageId(),
                            command.externalId(), outcome, "past retention", Instant.now());
                    kafka.send(Topics.DISPOSITION_RESULTS, receipt.messageId(),
                            json.writeValueAsString(receipt)).join();
                }
            }
        } catch (WakeupException ex) {
            // Expected: tearDown's wakeup() breaks the poll loop.
        } finally {
            commandConsumer.close();
        }
    }

    private static Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-p2-stand-in-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return props;
    }

    /**
     * The context starting at all is the assertion: Flyway ran this service's migrations against a
     * real Postgres and Hibernate then validated every entity against the result. A column an
     * entity declares and no migration creates fails here, at build time.
     */
    @Test
    void migrationsAndEntitiesAgree() {
        assertThat(retention.currentPeriods())
                .containsKeys(MessageType.EMAIL, MessageType.CHAT);
    }

    @Test
    void seedsAPolicyForEveryCommunicationType() {
        assertThat(retention.findAll()).hasSize(MessageType.values().length);
    }

    @Test
    void deletesMessagesPastRetentionAndRecordsThem() {
        ArchiveSchema.insertMessage(archive, "msg-old", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, false);
        ArchiveSchema.insertMessage(archive, "msg-new", "EXCH-2", "cust-1", "EMAIL", YESTERDAY, false);

        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, false, "test");

        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        // Counted the moment the command is published; the P2 stand-in's receipt settles the row.
        assertThat(run.getDeletedCount()).isEqualTo(1);
        // Not yet past retention, so not even a candidate — this one never left, no need to await.
        assertThat(ArchiveSchema.exists(archive, "msg-new")).isTrue();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(ArchiveSchema.exists(archive, "msg-old")).isFalse();
            assertThat(items.findByMessageIdOrderByOccurredAtAsc("msg-old")).singleElement()
                    .satisfies(item -> assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.DELETED));
        });
    }

    /**
     * The per-type cutoffs have to be paired with their own type. With email at seven years and
     * chat at three, a four-year-old chat is expired and a four-year-old email is not — and a
     * query that crossed the two would destroy the email.
     */
    @Test
    void appliesEachTypesOwnRetentionPeriod() {
        Instant fourYearsAgo = Instant.now().minus(Duration.ofDays(4 * 365));
        ArchiveSchema.insertMessage(archive, "msg-email", "EXCH-1", "cust-1", "EMAIL", fourYearsAgo, false);
        ArchiveSchema.insertMessage(archive, "msg-chat", "EXCH-2", "cust-1", "CHAT", fourYearsAgo, false);

        disposition.run(TriggerSource.MANUAL, false, "test");

        assertThat(ArchiveSchema.exists(archive, "msg-email")).isTrue();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(ArchiveSchema.exists(archive, "msg-chat")).isFalse());
    }

    /** FR-4.6: the demonstrable proof that deletion of a held message is blocked. */
    @Test
    void refusesToDeleteAMessageP2HasFlaggedAsHeld() {
        ArchiveSchema.insertMessage(archive, "msg-held", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, true);

        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, false, "test");

        assertThat(ArchiveSchema.exists(archive, "msg-held")).isTrue();
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        assertThat(items.findByMessageIdOrderByOccurredAtAsc("msg-held")).singleElement()
                .satisfies(item -> assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_HOLD));
    }

    /** The flag is only the fast path. A message P4 alone knows about must survive too. */
    @Test
    void refusesToDeleteAMessageOnlyP4KnowsIsHeld() {
        ArchiveSchema.insertMessage(archive, "msg-p4-held", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, false);
        P4.hold("msg-p4-held");

        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, false, "test");

        assertThat(ArchiveSchema.exists(archive, "msg-p4-held")).isTrue();
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
    }

    /**
     * A hold on a case, not yet expanded down to individual messages (FR-4.3). Neither the archive
     * flag nor the per-message check would report this one as held, and deleting it would destroy
     * the evidence the hold was placed to preserve.
     */
    @Test
    void refusesToDeleteAMessageInsideTheScopeOfAHoldOnACase() {
        ArchiveSchema.insertMessage(archive, "msg-in-scope", "EXCH-1", "cust-7", "EMAIL", LONG_AGO, false);
        P4.activeHolds("""
                [{"holdId":"hold-1","caseId":"case-1","caseName":"SEC Inquiry 2026",
                  "custodianIds":["cust-7"],"from":null,"to":null}]
                """);

        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, false, "test");

        assertThat(ArchiveSchema.exists(archive, "msg-in-scope")).isTrue();
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        // Named, so the ledger can later prove which matter protected it.
        assertThat(items.findByMessageIdOrderByOccurredAtAsc("msg-in-scope")).singleElement()
                .satisfies(item -> {
                    assertThat(item.getBlockingCaseId()).isEqualTo("case-1");
                    assertThat(item.getBlockingHoldId()).isEqualTo("hold-1");
                });
    }

    /** Fail closed. With P4 unreachable nothing can be verified, so nothing may be destroyed. */
    @Test
    void deletesNothingWhenP4IsUnreachable() {
        ArchiveSchema.insertMessage(archive, "msg-old", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, false);
        P4.failing(true);

        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, false, "test");

        assertThat(ArchiveSchema.exists(archive, "msg-old")).isTrue();
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        // The run must record that it was failing closed rather than idle, or "deleted nothing"
        // is indistinguishable from "had nothing to do" weeks later.
        assertThat(run.isHoldScopeAvailable()).isFalse();
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
    }

    @Test
    void dryRunRecordsEveryDecisionAndDeletesNothing() {
        ArchiveSchema.insertMessage(archive, "msg-old", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, false);

        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, true, "test");

        assertThat(ArchiveSchema.exists(archive, "msg-old")).isTrue();
        assertThat(run.isDryRun()).isTrue();
        assertThat(run.getDeletedCount()).isZero();
        assertThat(items.findByMessageIdOrderByOccurredAtAsc("msg-old")).singleElement()
                .satisfies(item -> assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.WOULD_DELETE));
    }

    /**
     * FR-5.1, the requirement the whole demo turns on: drop email retention to minutes and the next
     * sweep picks it up, with no restart and no backfill.
     */
    @Test
    void aRuntimePolicyChangeTakesEffectOnTheNextSweep() {
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        ArchiveSchema.insertMessage(archive, "msg-recent", "EXCH-1", "cust-1", "EMAIL", tenMinutesAgo, false);

        // Seven years: nothing to do.
        assertThat(disposition.run(TriggerSource.MANUAL, false, "test").getDeletedCount()).isZero();
        assertThat(ArchiveSchema.exists(archive, "msg-recent")).isTrue();

        retention.updatePeriod(MessageType.EMAIL, Duration.ofMinutes(2), "investigator");

        assertThat(disposition.run(TriggerSource.MANUAL, false, "test").getDeletedCount()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(ArchiveSchema.exists(archive, "msg-recent")).isFalse());
    }

    /** A shortened period must survive a restart, or a demo silently reverts to seven years. */
    @Test
    void aChangedPolicyIsNotOverwrittenByTheConfiguredSeed() {
        retention.updatePeriod(MessageType.EMAIL, Duration.ofMinutes(2), "investigator");

        // What the ApplicationReadyEvent does on every boot.
        retention.seedMissingPolicies();

        assertThat(retention.currentPeriods().get(MessageType.EMAIL)).isEqualTo(Duration.ofMinutes(2));
    }

    /** Idempotent: the eligible set is gone, so a second sweep has nothing to do. */
    @Test
    void aSecondSweepIsANoop() {
        ArchiveSchema.insertMessage(archive, "msg-old", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, false);

        assertThat(disposition.run(TriggerSource.MANUAL, false, "test").getDeletedCount()).isEqualTo(1);
        // The row has to actually be gone before the second sweep runs, or the P2 stand-in simply
        // has not caught up yet and this would test a race instead of idempotency.
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(ArchiveSchema.exists(archive, "msg-old")).isFalse());

        DispositionRunEntity second = disposition.run(TriggerSource.MANUAL, false, "test");

        assertThat(second.getCandidateCount()).isZero();
        assertThat(second.getDeletedCount()).isZero();
        assertThat(second.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
    }

    /** Bounded by batch-size, oldest first, so successive runs make monotonic progress (NFR-3). */
    @Test
    void aSweepIsBoundedAndTakesTheMostOverdueFirst() {
        for (int i = 0; i < 12; i++) {
            ArchiveSchema.insertMessage(archive, "msg-" + i, "EXCH-" + i, "cust-1", "EMAIL",
                    LONG_AGO.plus(Duration.ofDays(i)), false);
        }

        // batch-size is pinned to 5 for this class in properties() above.
        DispositionRunEntity first = disposition.run(TriggerSource.MANUAL, false, "test");

        assertThat(first.getCandidateCount()).isEqualTo(5);
        // The five oldest went, so the survivors are the five most recent of the twelve.
        assertThat(ArchiveSchema.exists(archive, "msg-11")).isTrue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(ArchiveSchema.countMessages(archive)).isEqualTo(7);
            assertThat(ArchiveSchema.exists(archive, "msg-0")).isFalse();
        });
    }
}
