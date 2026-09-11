package com.discoveryhub.disposition.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Builds P2's schema in the test's archive container, from P2's own migration file.
 *
 * <p>The file is read from {@code services/storage-service} rather than copied into this module's
 * test resources, and that is the point. {@code JdbcArchiveGateway} hand-writes SQL against
 * another service's table; the failure mode it is exposed to is P2 renaming a column, which
 * compiles perfectly here and fails at run time in the demo. A copied fixture would keep passing
 * after such a rename — it would be testing this service against a schema that no longer exists.
 * Reading the real migration means the integration test breaks the moment the contract does, which
 * is the earliest anyone could reasonably find out.
 *
 * <p>If P2's module is ever moved or renamed, the loud failure here is correct: the coupling
 * documented in DISPOSITION.md is real, and a test that quietly stopped covering it would be
 * worse than one that stops compiling.
 */
public final class ArchiveSchema {

    /** Relative to this module's directory, which is Surefire's working directory. */
    private static final Path P2_MIGRATIONS =
            Path.of("..", "storage-service", "src", "main", "resources", "db", "migration");

    private ArchiveSchema() {
    }

    /**
     * (Re)build P2's schema. Idempotent because the archive container is shared across test
     * classes — the second class to run would otherwise get "relation message_hold_status already
     * exists", a failure about test plumbing that looks like a migration bug.
     */
    public static void create(JdbcTemplate archive) {
        archive.execute("DROP TABLE IF EXISTS message_hold_status CASCADE");
        archive.execute(read("V2__messages.sql"));
        // V4 adds retention_override_at, which the eligibility query reads. Only the migrations
        // that shape the one table this service reads are applied — V3 and V6 create and then drop
        // P2's own disposition tables, which this service has never touched.
        archive.execute(read("V4__retention_override.sql"));
    }

    /** Wipe the table between tests. */
    public static void truncate(JdbcTemplate archive) {
        archive.execute("TRUNCATE TABLE message_hold_status");
    }

    /**
     * Insert one message's hold/retention row. {@code sentAt} drives eligibility, {@code onHold}
     * is P2's mirror of P4's hold state — the two inputs every disposition decision turns on.
     */
    public static void insertMessage(JdbcTemplate archive, String messageId, String externalId,
                                     String custodianId, String type, Instant sentAt, boolean onHold) {
        insertMessage(archive, messageId, externalId, custodianId, type, sentAt, onHold, null);
    }

    /**
     * As above, with the per-message retention override P2 stamps on a message P1 tagged
     * {@code RetentionLabels.DEMO_RETENTION} — the absolute instant it becomes eligible,
     * regardless of its type's period. {@code null} is every ordinary message.
     */
    public static void insertMessage(JdbcTemplate archive, String messageId, String externalId,
                                     String custodianId, String type, Instant sentAt, boolean onHold,
                                     Instant retentionOverrideAt) {
        archive.update("""
                        INSERT INTO message_hold_status (message_id, external_id, custodian_id,
                                                          type, sent_at, on_hold, hold_count,
                                                          retention_override_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                messageId, externalId, custodianId, type, java.sql.Timestamp.from(sentAt),
                onHold, onHold ? 1 : 0,
                retentionOverrideAt == null ? null : java.sql.Timestamp.from(retentionOverrideAt));
    }

    public static long countMessages(JdbcTemplate archive) {
        Long count = archive.queryForObject("SELECT count(*) FROM message_hold_status", Long.class);
        return count == null ? 0L : count;
    }

    public static boolean exists(JdbcTemplate archive, String messageId) {
        Long count = archive.queryForObject(
                "SELECT count(*) FROM message_hold_status WHERE message_id = ?", Long.class, messageId);
        return count != null && count > 0;
    }

    private static String read(String migration) {
        Path path = P2_MIGRATIONS.resolve(migration);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "could not read P2's migration at " + path.toAbsolutePath()
                            + ". This test builds the archive schema from storage-service's own "
                            + "migration on purpose, so that a schema change there fails here "
                            + "rather than in the demo.", ex);
        }
    }
}
