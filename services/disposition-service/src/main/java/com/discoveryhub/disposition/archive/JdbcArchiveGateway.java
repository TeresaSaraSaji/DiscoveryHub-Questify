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
 * <p>Deliberately SQL over {@link JdbcTemplate} rather than JPA. Mapping P2's {@code messages}
 * table into an entity here would mean two services carrying a definition of the same table, and
 * the next schema change in P2 would break this service at runtime with an error about a column.
 * A named projection of five columns is a contract this service can survive; a mirrored entity is
 * not. The columns relied on are {@code message_id, external_id, custodian_id, type, sent_at,
 * on_hold} — see {@code V2__messages.sql} in storage-service.
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
        if (cutoffs.isEmpty() || limit <= 0) {
            return List.of();
        }
        // One OR-group per type rather than an ANY over two parallel arrays: each type has its own
        // cutoff, so the predicate has to pair them, and `type = ANY(...) AND sent_at <= ANY(...)`
        // would happily match an email against the chat cutoff. Built by index, values bound —
        // no type name is ever concatenated into the SQL.
        StringBuilder sql = new StringBuilder("""
                SELECT message_id, external_id, custodian_id, type, sent_at, on_hold
                FROM messages
                WHERE (""");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<MessageType, Instant> entry : cutoffs.entrySet()) {
            sql.append(first ? "" : " OR ").append("(type = ? AND sent_at <= ?)");
            args.add(entry.getKey().name());
            args.add(Timestamp.from(entry.getValue()));
            first = false;
        }
        // Oldest first: a sweep is bounded by batch-size, so when there is more work than one
        // batch the most overdue messages go first and successive runs make monotonic progress
        // rather than revisiting an arbitrary slice.
        sql.append(") ORDER BY sent_at LIMIT ?");
        args.add(limit);

        return archive.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public long countMessages() {
        Long count = archive.queryForObject("SELECT count(*) FROM messages", Long.class);
        return count == null ? 0L : count;
    }
}
