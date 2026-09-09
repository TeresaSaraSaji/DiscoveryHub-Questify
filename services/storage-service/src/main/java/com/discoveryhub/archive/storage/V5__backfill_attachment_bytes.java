package com.discoveryhub.archive.storage;

import com.discoveryhub.archive.domain.MessageMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * V5: move the bytes of any pre-existing attachment out of {@code attachments.content} and onto
 * disk (plus the S3 offload copy when enabled).
 *
 * <p>{@code V4__attachment_storage.sql} adds {@code storage_location NOT NULL DEFAULT ''}, which
 * means every row that already existed points at nothing. Schema-only, V4 turns every attachment in
 * a populated archive into a 500 from {@code GET /messages/{id}/attachments/{attId}} while its bytes
 * sit untouched in the {@code content} column. A fresh install has nothing to move and this is a
 * no-op; an archive with a corpus needs the bytes relocated before it serves a single read, which is
 * why this is a migration rather than a background job — Flyway runs it before the context is up, so
 * no request can observe the half-migrated state.
 *
 * <p><b>The {@code content} column is only cleared for a row whose on-disk copy has been read back
 * and re-hashed to the {@code sha256} already on the row.</b> Until that check passes the database
 * copy is the sole surviving original, so discarding it on the strength of a write that returned
 * without error would put the chain of custody (FR-6.5) on trust rather than on evidence. A row that
 * fails verification keeps both its bytes and its empty {@code storage_location}, and the migration
 * fails so someone looks at it.
 *
 * <p>Deliberately outside a transaction ({@link #canExecuteInTransaction()} is false) and committed
 * in batches: one transaction spanning a large corpus would hold every row for the duration, and
 * batch commits make the work resumable. Rows are selected on {@code storage_location = ''}, so a
 * run that dies part-way resumes where it stopped — after a {@code flyway repair} clears the failed
 * marker — instead of starting over or double-writing.
 */
// Class name, not a filename, is what Flyway parses for the version here: BaseJavaMigration reads
// it in its constructor, so a readable name plus a getVersion() override is not an option.
@Component
@SuppressWarnings("checkstyle:TypeName")
public class V5__backfill_attachment_bytes extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V5__backfill_attachment_bytes.class);

    /** Rows per commit. Bounded so a large corpus never loads all of its bytes into heap at once. */
    private static final int BATCH_SIZE = 200;

    private final AttachmentStore storage;

    public V5__backfill_attachment_bytes(AttachmentStore storage) {
        this.storage = storage;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection cx = context.getConnection();
        boolean autoCommit = cx.getAutoCommit();
        cx.setAutoCommit(false);
        try {
            long moved = 0;
            List<String> failed = new ArrayList<>();
            // Keyset cursor rather than a bare LIMIT. A successfully moved row drops out of the
            // predicate on its own, but a row that cannot be moved does not — paging on the
            // predicate alone re-reads that row forever and the migration never returns, hanging
            // startup instead of failing. The cursor guarantees forward progress either way.
            String cursor = "";
            for (List<Row> batch = fetch(cx, cursor); !batch.isEmpty(); batch = fetch(cx, cursor)) {
                for (Row row : batch) {
                    if (relocate(cx, row)) {
                        moved++;
                    } else {
                        failed.add(row.attachmentId);
                    }
                }
                cursor = batch.get(batch.size() - 1).attachmentId;
                cx.commit();
                log.info("attachment backfill: {} moved so far ({} failed)", moved, failed.size());
            }

            if (moved == 0 && failed.isEmpty()) {
                log.info("attachment backfill: nothing to move (fresh install or already migrated)");
            } else {
                log.info("attachment backfill complete: {} attachments moved to blob storage, {} failed",
                        moved, failed.size());
            }
            if (!failed.isEmpty()) {
                // Fail the migration rather than start up with rows whose bytes could not be
                // verified on disk. Their content column is untouched, so nothing is lost.
                throw new IllegalStateException(
                        "attachment backfill could not verify " + failed.size() + " attachment(s) on disk; "
                                + "their bytes remain in the content column. First few: "
                                + failed.subList(0, Math.min(5, failed.size())));
            }
        } finally {
            cx.setAutoCommit(autoCommit);
        }
    }

    /**
     * The next batch of not-yet-relocated rows with bytes to relocate, starting after
     * {@code cursor}. Ordering by the primary key makes the paging stable and forward-only.
     */
    private List<Row> fetch(Connection cx, String cursor) throws Exception {
        String sql = """
                SELECT attachment_id, message_id, sha256, content
                  FROM attachments
                 WHERE storage_location = '' AND content IS NOT NULL AND attachment_id > ?
                 ORDER BY attachment_id
                 LIMIT ?
                """;
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = cx.prepareStatement(sql)) {
            ps.setString(1, cursor);
            ps.setInt(2, BATCH_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString("attachment_id"), rs.getString("message_id"),
                            rs.getString("sha256"), rs.getBytes("content")));
                }
            }
        }
        return rows;
    }

    /**
     * Write one row's bytes to blob storage, prove the on-disk copy re-hashes to the row's
     * {@code sha256}, then point the row at it and drop the database copy.
     *
     * @return false if the bytes could not be written or the written copy did not verify, in which
     *         case the row is left exactly as it was.
     */
    private boolean relocate(Connection cx, Row row) {
        AttachmentStore.StoredRefs refs;
        try {
            refs = storage.storeBytes(row.messageId, row.attachmentId, row.content);
        } catch (RuntimeException ex) {
            log.error("attachment backfill: cannot write bytes for {}/{}: {}",
                    row.messageId, row.attachmentId, ex.toString());
            return false;
        }

        String actual = MessageMapper.checksum(row.content);
        if (row.sha256 == null || row.sha256.isBlank()) {
            // No anchor means no way to prove the copy is faithful. Refuse rather than relocate
            // evidence that cannot be verified; sha256 is NOT NULL in the schema, so a blank one is
            // itself a defect worth surfacing.
            log.error("attachment backfill: {}/{} has no sha256 to verify against",
                    row.messageId, row.attachmentId);
            return false;
        }
        if (!row.sha256.equalsIgnoreCase(actual)) {
            // The stored sha256 never matched the stored bytes — a pre-existing integrity problem,
            // not something this migration introduced. Surface it instead of baking it into the new
            // layout, and leave the row's content in place as the evidence.
            log.error("attachment backfill: sha256 mismatch for {}/{} (row says {}, bytes hash to {})",
                    row.messageId, row.attachmentId, row.sha256, actual);
            return false;
        }
        if (!MessageMapper.checksum(readBack(refs)).equals(actual)) {
            log.error("attachment backfill: on-disk copy of {}/{} does not match the original bytes",
                    row.messageId, row.attachmentId);
            return false;
        }

        String sql = "UPDATE attachments SET storage_location = ?, s3_key = ?, s3_bucket = ?, content = NULL "
                + "WHERE attachment_id = ?";
        try (PreparedStatement ps = cx.prepareStatement(sql)) {
            ps.setString(1, refs.storageLocation());
            ps.setString(2, refs.s3Key());
            ps.setString(3, refs.s3Bucket());
            ps.setString(4, row.attachmentId);
            ps.executeUpdate();
            return true;
        } catch (Exception ex) {
            log.error("attachment backfill: cannot update row {}: {}", row.attachmentId, ex.toString());
            return false;
        }
    }

    /** Read the copy we just wrote back off the blob store, so verification tests the copy. */
    private byte[] readBack(AttachmentStore.StoredRefs refs) {
        try {
            return storage.load(refs.storageLocation(), refs.s3Key());
        } catch (RuntimeException ex) {
            log.error("attachment backfill: cannot read back {}: {}", refs.storageLocation(), ex.toString());
            return new byte[0];
        }
    }

    private record Row(String attachmentId, String messageId, String sha256, byte[] content) {
    }
}
