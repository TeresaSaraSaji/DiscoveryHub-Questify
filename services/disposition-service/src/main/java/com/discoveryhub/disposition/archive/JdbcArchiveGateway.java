package com.discoveryhub.disposition.archive;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.domain.ArchiveCandidate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the disposition candidate set from P2's archive.
 *
 * <p>Deliberately SQL over {@link JdbcTemplate} rather than JPA. Mapping P2's
 * {@code message_hold_status} table into an entity here would mean two services carrying a
 * definition of the same table, and the next schema change in P2 would break this service at
 * runtime with an error about a column. A named projection of five columns is a contract this
 * service can survive; a mirrored entity is not. The columns relied on are {@code message_id,
 * external_id, custodian_id, type, sent_at, on_hold} — see {@code V2__messages.sql} in
 * storage-service — and {@code retention_override_at}, from {@code V4__retention_override.sql},
 * which is read in the eligibility predicate but not projected.
 *
 * <p>Reads only this slim bookkeeping table, never the Mongo message store: P2 moved message
 * content (attachments included) to MongoDB, and this gateway never needed content in the first
 * place — only the columns above.
 */
@Component
public class JdbcArchiveGateway implements ArchiveGateway {

    private static final RowMapper<ArchiveCandidate> MAPPER = (rs, rowNum) -> new ArchiveCandidate(
            rs.getString("message_id"),
            rs.getString("external_id"),
            rs.getString("custodian_id"),
            MessageType.valueOf(rs.getString("type")),
            rs.getTimestamp("sent_at").toInstant(),
            rs.getBoolean("on_hold"));

    private final JdbcTemplate archive;

    public JdbcArchiveGateway(@Qualifier("archiveJdbcTemplate") JdbcTemplate archive) {
        this.archive = archive;
    }

    @Override
    public List<ArchiveCandidate> findCandidates(Map<MessageType, Instant> cutoffs, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // Two ways to be past retention, and they are mutually exclusive by construction:
        //
        //  - `retention_override_at` set — P1 tagged the message RetentionLabels.DEMO_RETENTION at
        //    upload and P2 stamped an absolute deadline on that one row. Eligible once that
        //    instant passes, whatever its type's period says.
        //  - `retention_override_at` null, which is every ordinary message — the type cutoff.
        //
        // The type clauses sit behind `IS NULL` so a demoed message cannot also come up early
        // through its type by coincidence. This is deliberately the same shape as P2's own
        // MessageHoldStatusRepository.findDispositionEligible: two services agreeing on what
        // "past retention" means, rather than one of them quietly meaning something narrower.
        //
        // Note the override arm survives an empty `cutoffs`: no retention policy configured is a
        // reason not to delete ordinary messages, but the override is an explicit per-message
        // instruction and does not depend on a policy existing.
        StringBuilder sql = new StringBuilder("""
                SELECT message_id, external_id, custodian_id, type, sent_at, on_hold
                FROM message_hold_status
                WHERE (retention_override_at IS NOT NULL AND retention_override_at <= ?)""");
        List<Object> args = new ArrayList<>();
        // The gateway's own clock rather than a parameter: `cutoffs` already carries the caller's
        // `now` folded into each type's deadline, and threading a second one through the seam
        // buys nothing a sub-millisecond difference could affect.
        args.add(Timestamp.from(Instant.now()));

        // One OR-group per type rather than an ANY over two parallel arrays: each type has its own
        // cutoff, so the predicate has to pair them, and `type = ANY(...) AND sent_at <= ANY(...)`
        // would happily match an email against the chat cutoff. Built by index, values bound —
        // no type name is ever concatenated into the SQL.
        if (!cutoffs.isEmpty()) {
            sql.append("\n   OR (retention_override_at IS NULL AND (");
            boolean first = true;
            for (Map.Entry<MessageType, Instant> entry : cutoffs.entrySet()) {
                sql.append(first ? "" : " OR ").append("(type = ? AND sent_at <= ?)");
                args.add(entry.getKey().name());
                args.add(Timestamp.from(entry.getValue()));
                first = false;
            }
            sql.append("))");
        }

        // Oldest first: a sweep is bounded by batch-size, so when there is more work than one
        // batch the most overdue messages go first and successive runs make monotonic progress
        // rather than revisiting an arbitrary slice.
        sql.append("\nORDER BY sent_at\nLIMIT ?");
        args.add(limit);

        return archive.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public long countMessages() {
        Long count = archive.queryForObject("SELECT count(*) FROM message_hold_status", Long.class);
        return count == null ? 0L : count;
    }
}
