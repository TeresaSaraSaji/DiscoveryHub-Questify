package com.discoveryhub.disposition;

import com.discoveryhub.contracts.MessageType;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>Runs in {@code ARCHIVE_DB} delete mode so the assertions can be about rows rather than about
 * published commands; the Kafka path has its own round-trip test.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DispositionIntegrationTest {

    private static final PostgreSQLContainer DISPOSITION_DB = Containers.DISPOSITION_DB;

    private static final PostgreSQLContainer ARCHIVE_DB = Containers.ARCHIVE_DB;

    private static final HoldServiceStub P4 = new HoldServiceStub();

    /** Older than every retention period in play, so eligibility is never accidental. */
    private static final Instant LONG_AGO = Instant.parse("2017-03-01T00:00:00Z");

    /** Recent enough that no sensible policy makes it eligible. */
    private static final Instant YESTERDAY = Instant.now().minus(Duration.ofDays(1));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DISPOSITION_DB::getJdbcUrl);
        registry.add("spring.datasource.username", DISPOSITION_DB::getUsername);
        registry.add("spring.datasource.password", DISPOSITION_DB::getPassword);

        registry.add("discoveryhub.disposition.archive.datasource.url", ARCHIVE_DB::getJdbcUrl);
        registry.add("discoveryhub.disposition.archive.datasource.username", ARCHIVE_DB::getUsername);
        registry.add("discoveryhub.disposition.archive.datasource.password", ARCHIVE_DB::getPassword);

        // Assertions here are about rows in a database, so delete for real rather than publish.
        registry.add("discoveryhub.disposition.delete-mode", () -> "ARCHIVE_DB");
        registry.add("discoveryhub.disposition.archive.hold-check-base-url", P4::baseUrl);
        // Every sweep in this class is triggered explicitly. A cron firing mid-test would delete
        // another test's fixtures and the failure would look like a logic bug.
        registry.add("discoveryhub.disposition.schedule.enabled", () -> "false");
        // Small enough that the bounding is observable with a handful of fixtures rather than 500.
        registry.add("discoveryhub.disposition.batch-size", () -> "5");
        // No broker in this test. Audit sends are fire-and-forget and merely log, but the receipt
        // listener would otherwise spend the run retrying a connection to localhost:9092 and
        // filling the output with noise that looks like a failure. The Kafka path is covered by
        // DeleteLoopKafkaIntegrationTest, against a real broker.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired DispositionService disposition;
    @Autowired RetentionPolicyService retention;
    @Autowired DispositionRunRepository runs;
    @Autowired DispositionItemRepository items;

    @Autowired
    @Qualifier("archiveJdbcTemplate")
    JdbcTemplate archive;

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
        // Back to the shipped defaults: seven years for email, three for chat. Individual tests
        // that care about the boundary set their own.
        retention.updatePeriod(MessageType.EMAIL, Duration.ofDays(2555), "test");
        retention.updatePeriod(MessageType.CHAT, Duration.ofDays(1095), "test");
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
        assertThat(run.getDeletedCount()).isEqualTo(1);
        assertThat(ArchiveSchema.exists(archive, "msg-old")).isFalse();
        // Not yet past retention, so not even a candidate.
        assertThat(ArchiveSchema.exists(archive, "msg-new")).isTrue();

        List<DispositionItemEntity> ledger = items.findByMessageIdOrderByOccurredAtAsc("msg-old");
        assertThat(ledger).singleElement()
                .satisfies(item -> assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.DELETED));
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
        assertThat(ArchiveSchema.exists(archive, "msg-chat")).isFalse();
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
        assertThat(ArchiveSchema.exists(archive, "msg-recent")).isFalse();
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
        assertThat(ArchiveSchema.countMessages(archive)).isEqualTo(7);
        // The five oldest went, so the survivors are the five most recent of the twelve.
        assertThat(ArchiveSchema.exists(archive, "msg-0")).isFalse();
        assertThat(ArchiveSchema.exists(archive, "msg-11")).isTrue();
    }
}
