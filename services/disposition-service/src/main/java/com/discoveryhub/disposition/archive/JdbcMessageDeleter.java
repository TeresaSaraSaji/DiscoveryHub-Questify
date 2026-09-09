package com.discoveryhub.disposition.archive;

import com.discoveryhub.disposition.domain.ArchiveCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes from P2's archive database directly. Active when
 * {@code discoveryhub.disposition.delete-mode} is {@code ARCHIVE_DB} (the default).
 *
 * <p><b>Why this exists.</b> P2's HTTP API is read-only and P2 is owned by someone else, so there
 * is currently no way to ask it to delete anything. A retention service that identifies expired
 * data and then cannot dispose of it does not satisfy FR-5.2 — so this mode writes to P2's tables,
 * and does so through exactly one statement in exactly one class. It is the pragmatic option, not
 * the pretty one, and {@link KafkaMessageDeleter} is where it goes to die.
 *
 * <p><b>The guard.</b> {@code AND on_hold = false} is in the statement itself, not in the service
 * that calls it. The sweep already asked P4, but that answer is a few milliseconds old by the time
 * the delete runs; a hold placed in that window has to win. With the predicate in the {@code
 * WHERE} clause the database arbitrates, and a hold that landed first means zero rows affected —
 * which is reported as {@link DeleteResult#REFUSED_HOLD} and audited as a refusal. That makes this
 * the demonstrable proof FR-4.6 asks for: point the delete at a held message and watch it decline.
 *
 * <p>Attachments are removed by {@code ON DELETE CASCADE} on {@code fk_attachments_message}
 * (V2__messages.sql), so one statement is the whole delete and there is no window in which a
 * message is gone but its attachments are not.
 */
@Component
@ConditionalOnProperty(prefix = "discoveryhub.disposition", name = "delete-mode",
        havingValue = "ARCHIVE_DB", matchIfMissing = true)
public class JdbcMessageDeleter implements MessageDeleter {

    private static final Logger log = LoggerFactory.getLogger(JdbcMessageDeleter.class);

    private static final String DELETE = "DELETE FROM messages WHERE message_id = ? AND on_hold = false";

    private static final String EXISTS = "SELECT count(*) FROM messages WHERE message_id = ?";

    private final JdbcTemplate archive;

    public JdbcMessageDeleter(@Qualifier("archiveJdbcTemplate") JdbcTemplate archive) {
        this.archive = archive;
    }

    @Override
    public DeleteResult delete(String runId, ArchiveCandidate candidate) {
        try {
            int affected = archive.update(DELETE, candidate.messageId());
            if (affected > 0) {
                return DeleteResult.DELETED;
            }
            // Zero rows means one of two very different things, and conflating them would put a
            // false refusal in the chain of custody. Ask which.
            Long present = archive.queryForObject(EXISTS, Long.class, candidate.messageId());
            if (present != null && present > 0) {
                log.info("refused to delete {}: on_hold was set at the moment of deletion",
                        candidate.messageId());
                return DeleteResult.REFUSED_HOLD;
            }
            return DeleteResult.NOT_FOUND;
        } catch (Exception ex) {
            log.warn("delete failed for {}: {}", candidate.messageId(), ex.toString());
            return DeleteResult.FAILED;
        }
    }
}
