package com.discoveryhub.disposition.archive;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.domain.ArchiveCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guarded DELETE, against a real database rather than a mock.
 *
 * <p>This is the demonstrable proof FR-4.6 asks for, so it is worth testing where the guard
 * actually lives: {@code AND on_hold = false} is evaluated by the database, atomically with the
 * write, and no amount of stubbing would show that it works. H2 in PostgreSQL mode with the same
 * DDL as {@code V2__messages.sql} — including the {@code ON DELETE CASCADE} the deleter relies on
 * to remove attachments in one statement.
 */
class JdbcMessageDeleterTest {

    private JdbcTemplate archive;
    private JdbcMessageDeleter deleter;
    private JdbcArchiveGateway gateway;

    @BeforeEach
    void setUp() {
        archive = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("archive-" + System.nanoTime())
                .build());
        // The subset of P2's schema this service touches. Kept to the columns actually relied on,
        // which is the same contract JdbcArchiveGateway documents.
        archive.execute("""
                CREATE TABLE messages (
                    message_id   VARCHAR(36)  NOT NULL PRIMARY KEY,
                    external_id  VARCHAR(255) NOT NULL UNIQUE,
                    custodian_id VARCHAR(255) NOT NULL,
                    type         VARCHAR(16)  NOT NULL,
                    sent_at      TIMESTAMP    NOT NULL,
                    on_hold      BOOLEAN      NOT NULL DEFAULT FALSE
                )
                """);
        archive.execute("""
                CREATE TABLE attachments (
                    attachment_id VARCHAR(36) NOT NULL PRIMARY KEY,
                    message_id    VARCHAR(36) NOT NULL
                        REFERENCES messages (message_id) ON DELETE CASCADE
                )
                """);
        deleter = new JdbcMessageDeleter(archive);
        gateway = new JdbcArchiveGateway(archive);
    }

    @Test
    void deletesAnUnheldMessageAndCascadesToItsAttachments() {
        ArchiveCandidate candidate = insert("EXCH-1", false, Duration.ofMinutes(10));
        archive.update("INSERT INTO attachments VALUES ('att-1', ?)", candidate.messageId());

        MessageDeleter.DeleteResult result = deleter.delete("run-1", candidate);

        assertThat(result).isEqualTo(MessageDeleter.DeleteResult.DELETED);
        assertThat(count("messages")).isZero();
        // One statement removed both, so there is no window where the message is gone and its
        // attachments are orphaned.
        assertThat(count("attachments")).isZero();
    }

    @Test
    void refusesToDeleteAHeldMessage() {
        // FR-4.6: held data survives a delete attempt, and the attempt is reported as a refusal.
        ArchiveCandidate candidate = insert("EXCH-2", true, Duration.ofMinutes(10));

        MessageDeleter.DeleteResult result = deleter.delete("run-1", candidate);

        assertThat(result).isEqualTo(MessageDeleter.DeleteResult.REFUSED_HOLD);
        assertThat(count("messages")).isEqualTo(1);
    }

    @Test
    void reportsNotFoundRatherThanRefusedForAnAlreadyDeletedMessage() {
        // Zero rows affected has two very different meanings; conflating them would put a false
        // refusal in the chain of custody.
        ArchiveCandidate absent = new ArchiveCandidate("msg-gone", "EXCH-3", "custodian-1",
                MessageType.EMAIL, Instant.now(), false);

        assertThat(deleter.delete("run-1", absent)).isEqualTo(MessageDeleter.DeleteResult.NOT_FOUND);
    }

    @Test
    void findsOnlyMessagesPastTheirOwnTypesCutoff() {
        insert("EXCH-OLD-EMAIL", false, Duration.ofMinutes(10), MessageType.EMAIL);
        insert("EXCH-NEW-EMAIL", false, Duration.ofSeconds(30), MessageType.EMAIL);
        insert("EXCH-OLD-CHAT", false, Duration.ofMinutes(2), MessageType.CHAT);

        Instant now = Instant.now();
        List<ArchiveCandidate> candidates = gateway.findCandidates(Map.of(
                MessageType.EMAIL, now.minus(Duration.ofMinutes(5)),
                MessageType.CHAT, now.minus(Duration.ofMinutes(1))), 100);

        // The per-type pairing matters: the 30-second-old email is not eligible even though it is
        // older than the chat cutoff.
        assertThat(candidates).extracting(ArchiveCandidate::externalId)
                .containsExactlyInAnyOrder("EXCH-OLD-EMAIL", "EXCH-OLD-CHAT");
    }

    @Test
    void returnsHeldCandidatesSoTheyCanBeRecordedAsSkipped() {
        insert("EXCH-HELD", true, Duration.ofMinutes(10));

        List<ArchiveCandidate> candidates = gateway.findCandidates(
                Map.of(MessageType.EMAIL, Instant.now().minus(Duration.ofMinutes(5))), 100);

        // Filtering held rows out of the query would lose the ledger entry FR-5.3 requires.
        assertThat(candidates).singleElement()
                .extracting(ArchiveCandidate::onHold).isEqualTo(true);
    }

    @Test
    void honoursTheBatchLimitAndReturnsTheOldestFirst() {
        insert("EXCH-A", false, Duration.ofMinutes(10));
        insert("EXCH-B", false, Duration.ofMinutes(30));
        insert("EXCH-C", false, Duration.ofMinutes(20));

        List<ArchiveCandidate> candidates = gateway.findCandidates(
                Map.of(MessageType.EMAIL, Instant.now().minus(Duration.ofMinutes(5))), 2);

        // Oldest first, so bounded runs make monotonic progress instead of revisiting a slice.
        assertThat(candidates).extracting(ArchiveCandidate::externalId)
                .containsExactly("EXCH-B", "EXCH-C");
    }

    @Test
    void noCutoffsMeansNoCandidatesRatherThanEverything() {
        insert("EXCH-1", false, Duration.ofDays(4000));

        // An empty policy table must not be read as "every type is expired".
        assertThat(gateway.findCandidates(Map.of(), 100)).isEmpty();
    }

    private ArchiveCandidate insert(String externalId, boolean onHold, Duration age) {
        return insert(externalId, onHold, age, MessageType.EMAIL);
    }

    private ArchiveCandidate insert(String externalId, boolean onHold, Duration age, MessageType type) {
        String messageId = "msg-" + externalId;
        Instant sentAt = Instant.now().minus(age);
        archive.update("INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?)",
                messageId, externalId, "custodian-1", type.name(), Timestamp.from(sentAt), onHold);
        return new ArchiveCandidate(messageId, externalId, "custodian-1", type, sentAt, onHold);
    }

    private long count(String table) {
        Long count = archive.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
