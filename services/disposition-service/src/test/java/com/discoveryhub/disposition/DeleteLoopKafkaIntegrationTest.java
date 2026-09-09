package com.discoveryhub.disposition;

import com.discoveryhub.contracts.DeleteCommand;
import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.contracts.Topics;
import com.discoveryhub.disposition.domain.DispositionItemEntity;
import com.discoveryhub.disposition.domain.DispositionOutcome;
import com.discoveryhub.disposition.domain.DispositionRunEntity;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The {@code KAFKA} delete path, end to end from this service's side, against a real broker.
 *
 * <p>This is the mode the service ships in, and it is the one that cannot be verified by reading
 * the code: the sweep publishes a command and records {@code DELETE_REQUESTED}, and something else
 * entirely — P2 — decides what actually happens. Two failures are invisible without a broker in
 * the loop. A command that never reaches the topic leaves the ledger claiming a message was dealt
 * with when nothing was even asked. And a receipt that arrives but never settles its row leaves
 * every Kafka-mode sweep permanently stuck at "we asked", which is the state FR-5.3 exists to
 * prevent.
 *
 * <p>P2 is not running here, so the test plays P2: it consumes the command off
 * {@code disposition.commands}, checks it is the documented shape, and answers on
 * {@code disposition.results}. That is a fair stand-in precisely because the contract is now in
 * {@code contracts} and both ends deserialise the same records — the shape asserted here is the
 * shape P2's listener parses.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeleteLoopKafkaIntegrationTest {

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
        // The mode under test: publish a command, let P2 own the write.
        registry.add("discoveryhub.disposition.delete-mode", () -> "KAFKA");
        registry.add("discoveryhub.disposition.archive.hold-check-base-url", P4::baseUrl);
        registry.add("discoveryhub.disposition.schedule.enabled", () -> "false");
        // A group of its own per run, so a rerun does not start from another run's committed
        // offsets and quietly skip the receipt this test is waiting for.
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

    private KafkaConsumer<String, String> commandConsumer;

    @BeforeAll
    void setUpArchiveAndConsumer() {
        ArchiveSchema.create(archive);
        commandConsumer = consumer();
        commandConsumer.subscribe(List.of(Topics.DISPOSITION_COMMANDS));
        // Force the initial assignment now, so the first poll in a test does not spend its whole
        // timeout on a group join and report a missing command that was in fact published.
        commandConsumer.poll(Duration.ofSeconds(5));
    }

    @AfterAll
    void tearDown() {
        commandConsumer.close();
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

    /**
     * The sweep's half of the loop: a command on the topic, a ledger row that says only what this
     * service can honestly claim, and — critically — a message still present in the archive,
     * because in this mode nothing here deletes anything.
     */
    @Test
    void aSweepPublishesADeleteCommandAndRecordsDeleteRequested() {
        ArchiveSchema.insertMessage(archive, "msg-1", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, false);

        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, false, "test");

        DeleteCommand command = nextCommand();
        assertThat(command.messageId()).isEqualTo("msg-1");
        assertThat(command.externalId()).isEqualTo("EXCH-1");
        assertThat(command.custodianId()).isEqualTo("cust-1");
        assertThat(command.runId()).isEqualTo(run.getRunId());
        assertThat(command.requestedAt()).isNotNull();

        assertThat(itemFor("msg-1").getOutcome()).isEqualTo(DispositionOutcome.DELETE_REQUESTED);
        assertThat(itemFor("msg-1").getSettledAt()).isNull();
        // P2 owns the write. Nothing in this service touched the row.
        assertThat(ArchiveSchema.exists(archive, "msg-1")).isTrue();
    }

    /** A held message never reaches the topic at all — the guards run before the publish. */
    @Test
    void aHeldMessageProducesNoCommand() {
        ArchiveSchema.insertMessage(archive, "msg-held", "EXCH-2", "cust-1", "EMAIL", LONG_AGO, true);

        disposition.run(TriggerSource.MANUAL, false, "test");

        assertThat(pollCommands()).isEmpty();
        assertThat(itemFor("msg-held").getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_HOLD);
    }

    @Test
    void aConfirmedReceiptSettlesTheLedgerRowAsDeleted() {
        ArchiveSchema.insertMessage(archive, "msg-1", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, false);
        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, false, "test");
        DeleteCommand command = nextCommand();

        publishReceipt(new DeleteReceipt(command.runId(), command.messageId(), command.externalId(),
                DeleteReceipt.Outcome.DELETED, "past retention", Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            DispositionItemEntity item = itemFor("msg-1");
            assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.DELETED);
            assertThat(item.getSettledAt()).isNotNull();
        });
        // A confirmation matches what the sweep already counted, so the summary does not move.
        assertThat(runs.findById(run.getRunId()).orElseThrow().getDeletedCount()).isEqualTo(1);
    }

    /**
     * The case the whole loop is for. P2 refused because a hold landed between this service's check
     * and P2's delete — so the ledger has to stop claiming the message was deleted, and both the
     * row and the run summary have to move to the refusal.
     */
    @Test
    void aRefusalFromP2SettlesTheRowAsSkippedHoldAndCorrectsTheRunSummary() {
        ArchiveSchema.insertMessage(archive, "msg-1", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, false);
        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, false, "test");
        assertThat(run.getDeletedCount()).isEqualTo(1);
        DeleteCommand command = nextCommand();

        publishReceipt(new DeleteReceipt(command.runId(), command.messageId(), command.externalId(),
                DeleteReceipt.Outcome.REFUSED_HOLD, "hold flag set in the archive", Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            DispositionItemEntity item = itemFor("msg-1");
            assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_HOLD);
            assertThat(item.getReason()).isEqualTo("P2: hold flag set in the archive");

            DispositionRunEntity settled = runs.findById(run.getRunId()).orElseThrow();
            assertThat(settled.getDeletedCount()).isZero();
            assertThat(settled.getSkippedHoldCount()).isEqualTo(1);
        });
    }

    /** {@code disposition.results} is at-least-once; a replay must not rewrite a settled outcome. */
    @Test
    void aRedeliveredReceiptDoesNotChangeASettledRow() {
        ArchiveSchema.insertMessage(archive, "msg-1", "EXCH-1", "cust-1", "EMAIL", LONG_AGO, false);
        DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, false, "test");
        DeleteCommand command = nextCommand();

        DeleteReceipt refusal = new DeleteReceipt(command.runId(), command.messageId(),
                command.externalId(), DeleteReceipt.Outcome.REFUSED_HOLD, "held", Instant.now());
        publishReceipt(refusal);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(itemFor("msg-1").getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_HOLD));

        // The same message again, this time claiming success. It must not win.
        publishReceipt(new DeleteReceipt(command.runId(), command.messageId(), command.externalId(),
                DeleteReceipt.Outcome.DELETED, "past retention", Instant.now()));

        // Nothing to await on for a no-op, so give the listener room to have done the wrong thing.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(itemFor("msg-1").getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_HOLD);
            assertThat(runs.findById(run.getRunId()).orElseThrow().getSkippedHoldCount()).isEqualTo(1);
        });
    }

    private DeleteCommand nextCommand() {
        List<DeleteCommand> commands = await().atMost(Duration.ofSeconds(20))
                .until(this::pollCommands, list -> !list.isEmpty());
        assertThat(commands).hasSize(1);
        return commands.get(0);
    }

    private List<DeleteCommand> pollCommands() {
        ConsumerRecords<String, String> records = commandConsumer.poll(Duration.ofSeconds(2));
        return java.util.stream.StreamSupport.stream(records.spliterator(), false)
                .map(ConsumerRecord::value)
                .map(value -> json.readValue(value, DeleteCommand.class))
                .toList();
    }

    private void publishReceipt(DeleteReceipt receipt) {
        kafka.send(Topics.DISPOSITION_RESULTS, receipt.messageId(), json.writeValueAsString(receipt)).join();
    }

    private DispositionItemEntity itemFor(String messageId) {
        List<DispositionItemEntity> found = items.findByMessageIdOrderByOccurredAtAsc(messageId);
        assertThat(found).as("ledger row for %s", messageId).hasSize(1);
        return found.get(0);
    }

    private KafkaConsumer<String, String> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-p2-stand-in-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
