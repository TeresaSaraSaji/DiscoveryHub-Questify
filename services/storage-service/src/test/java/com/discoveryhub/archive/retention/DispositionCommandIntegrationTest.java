package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.archive.support.HoldServiceStub;
import com.discoveryhub.contracts.DeleteCommand;
import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.contracts.Topics;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P2's half of the disposition loop, against a real broker and a real archive database.
 *
 * <p>This service is the only thing that deletes from {@code messages}, and it does so on another
 * service's instruction. The instruction arrives over Kafka and the answer goes back over Kafka, so
 * the interesting behaviour is entirely in the wiring: does a command actually reach the listener,
 * does the row actually go, and does a receipt actually come back. Mocks answer none of those.
 *
 * <p>The refusal cases matter most. P2.2 has already decided a message is past retention and not
 * held; this service checking again looks redundant until a hold lands in the window between the
 * two, which is exactly the scenario {@link #refusesToDeleteAHeldMessageAndSaysSo()} sets up. The
 * message survives, and P2.2 is told why (FR-4.2, FR-4.6).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DispositionCommandIntegrationTest {

    private static final PostgreSQLContainer ARCHIVE_DB = new PostgreSQLContainer("postgres:17.11")
            .withDatabaseName("archive").withUsername("archive").withPassword("archive");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    private static final HoldServiceStub P4 = new HoldServiceStub();

    static {
        ARCHIVE_DB.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ARCHIVE_DB::getJdbcUrl);
        registry.add("spring.datasource.username", ARCHIVE_DB::getUsername);
        registry.add("spring.datasource.password", ARCHIVE_DB::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("discoveryhub.archive.hold-check-base-url", P4::baseUrl);
        // Fresh group per run: a rerun must not start from the previous run's committed offsets
        // and skip the command the test is waiting on.
        registry.add("spring.kafka.consumer.group-id", () -> "test-" + UUID.randomUUID());
    }

    @Autowired MessageRepository messages;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private KafkaConsumer<String, String> receipts;

    @BeforeAll
    void subscribe() {
        receipts = consumer();
        receipts.subscribe(List.of(Topics.DISPOSITION_RESULTS));
        // Join the group now, so the first poll in a test is not spent on the rebalance.
        receipts.poll(Duration.ofSeconds(5));
    }

    @AfterAll
    void tearDown() {
        receipts.close();
        P4.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE attachments, messages");
        P4.reset();
        drainReceipts();
    }

    /**
     * The topic is shared across the class, so a receipt left by the previous test would be the
     * first record the next one polls — and it would assert against the wrong outcome entirely.
     * Poll until the topic is quiet before each test rather than filtering afterwards, so
     * {@code nextReceipt()} can stay "the receipt this test caused".
     */
    private void drainReceipts() {
        while (!receipts.poll(Duration.ofMillis(500)).isEmpty()) {
            // keep draining
        }
    }

    @Test
    void deletesAMessageOnCommandAndConfirms() {
        insert("msg-1", "EXCH-1", false);

        send(command("msg-1", "EXCH-1"));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(messages.findById("msg-1")).isEmpty());
        DeleteReceipt receipt = nextReceipt();
        assertThat(receipt.outcome()).isEqualTo(DeleteReceipt.Outcome.DELETED);
        assertThat(receipt.messageId()).isEqualTo("msg-1");
        assertThat(receipt.runId()).isEqualTo("run-1");
    }

    /** Attachments go with the message via ON DELETE CASCADE, so no bytes are left orphaned. */
    @Test
    void deletingAMessageTakesItsAttachmentsWithIt() {
        insert("msg-1", "EXCH-1", false);
        jdbc.update("""
                INSERT INTO attachments (attachment_id, message_id, ordinal, filename, content_type,
                                         size_bytes, sha256, content)
                VALUES ('att-1', 'msg-1', 0, 'a.pdf', 'application/pdf', 3, 'abc', ?)
                """, (Object) new byte[] { 1, 2, 3 });

        send(command("msg-1", "EXCH-1"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM attachments")).isZero());
    }

    /** FR-4.6: the demonstrable proof, on the side that owns the data. */
    @Test
    void refusesToDeleteAHeldMessageAndSaysSo() {
        insert("msg-held", "EXCH-2", true);

        send(command("msg-held", "EXCH-2"));

        DeleteReceipt receipt = nextReceipt();
        assertThat(receipt.outcome()).isEqualTo(DeleteReceipt.Outcome.REFUSED_HOLD);
        assertThat(messages.findById("msg-held")).isPresent();
    }

    /** The archive's flag was clear, but P4 knows better. Still refused. */
    @Test
    void refusesWhenOnlyP4KnowsTheMessageIsHeld() {
        insert("msg-p4", "EXCH-3", false);
        P4.hold("msg-p4");

        send(command("msg-p4", "EXCH-3"));

        assertThat(nextReceipt().outcome()).isEqualTo(DeleteReceipt.Outcome.REFUSED_HOLD);
        assertThat(messages.findById("msg-p4")).isPresent();
    }

    /**
     * Fail closed. P2.2 cleared this message, but if P4 cannot be reached now, this service cannot
     * confirm the clearance and must not act on it.
     */
    @Test
    void refusesWhenP4CannotBeReached() {
        insert("msg-unverifiable", "EXCH-4", false);
        P4.failing(true);

        send(command("msg-unverifiable", "EXCH-4"));

        assertThat(nextReceipt().outcome()).isEqualTo(DeleteReceipt.Outcome.REFUSED_HOLD);
        assertThat(messages.findById("msg-unverifiable")).isPresent();
    }

    /** At-least-once delivery: a replayed command is a no-op, reported honestly. */
    @Test
    void aReplayedCommandReportsNotFoundRatherThanFailing() {
        send(command("msg-never-existed", "EXCH-5"));

        assertThat(nextReceipt().outcome()).isEqualTo(DeleteReceipt.Outcome.NOT_FOUND);
    }

    /** One bad record must not wedge the consumer for every message behind it. */
    @Test
    void anUnparseableCommandIsSkippedAndTheNextOneStillWorks() {
        insert("msg-1", "EXCH-1", false);

        kafka.send(Topics.DISPOSITION_COMMANDS, "junk", "{not json").join();
        send(command("msg-1", "EXCH-1"));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(messages.findById("msg-1")).isEmpty());
        assertThat(nextReceipt().outcome()).isEqualTo(DeleteReceipt.Outcome.DELETED);
    }

    private DeleteCommand command(String messageId, String externalId) {
        return new DeleteCommand("run-1", messageId, externalId, "cust-1",
                "past retention", Instant.now());
    }

    private void send(DeleteCommand command) {
        kafka.send(Topics.DISPOSITION_COMMANDS, command.messageId(),
                json.writeValueAsString(command)).join();
    }

    private DeleteReceipt nextReceipt() {
        return await().atMost(Duration.ofSeconds(20))
                .until(this::pollReceipts, list -> !list.isEmpty())
                .get(0);
    }

    private List<DeleteReceipt> pollReceipts() {
        ConsumerRecords<String, String> records = receipts.poll(Duration.ofSeconds(2));
        return java.util.stream.StreamSupport.stream(records.spliterator(), false)
                .map(ConsumerRecord::value)
                .map(value -> json.readValue(value, DeleteReceipt.class))
                .toList();
    }

    private void insert(String messageId, String externalId, boolean onHold) {
        jdbc.update("""
                        INSERT INTO messages (message_id, external_id, source, type, custodian_id,
                                              from_addr, to_list, cc_list, subject, body, sent_at,
                                              thread_id, labels, on_hold, hold_count)
                        VALUES (?, ?, 'EXCHANGE', 'EMAIL', 'cust-1', 'from@x.com', '[]', '[]',
                                'subj', 'body', now(), 'thread-1', '[]', ?, ?)
                        """,
                messageId, externalId, onHold, onHold ? 1 : 0);
    }

    private long count(String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0L : count;
    }

    private KafkaConsumer<String, String> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-p2.2-stand-in-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
