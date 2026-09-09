package com.discoveryhub.archive.storage;

import com.discoveryhub.archive.domain.MessageMapper;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The V4 → V5 upgrade path on an archive that already has a corpus. V4 adds
 * {@code storage_location NOT NULL DEFAULT ''}, which points every pre-existing attachment at
 * nothing; without this migration those rows return 500 from the attachment endpoint while their
 * bytes sit unreachable in the {@code content} column. That is exactly what happened on a populated
 * database, so these tests pin the recovery.
 *
 * <p>Runs against a real H2 database and a real {@link LocalBlobStorage} on a {@link TempDir},
 * because the whole point is the interaction between the rows and the files.
 */
class V5BackfillAttachmentBytesTest {

    @TempDir Path tmp;

    private Connection cx;
    private LocalBlobStorage local;
    private AttachmentStore store;
    private V5__backfill_attachment_bytes migration;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        cx = DriverManager.getConnection(
                "jdbc:h2:mem:backfill-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = cx.createStatement()) {
            // The shape V2 creates plus the three columns V4 adds.
            st.execute("""
                    CREATE TABLE attachments (
                        attachment_id    VARCHAR(36) NOT NULL PRIMARY KEY,
                        message_id       VARCHAR(36) NOT NULL,
                        ordinal          INT NOT NULL DEFAULT 0,
                        filename         VARCHAR(255) NOT NULL DEFAULT 'f',
                        content_type     VARCHAR(255),
                        size_bytes       BIGINT NOT NULL DEFAULT 0,
                        sha256           VARCHAR(64) NOT NULL,
                        content          VARBINARY,
                        storage_location VARCHAR(1024) NOT NULL DEFAULT '',
                        s3_key           VARCHAR(1024),
                        s3_bucket        VARCHAR(255)
                    )
                    """);
        }
        local = new LocalBlobStorage(new StorageProperties(
                new StorageProperties.Local(tmp.resolve("attachments").toString()), null));
        ObjectProvider<S3BlobStorage> noS3 = mock(ObjectProvider.class);
        when(noS3.getIfAvailable()).thenReturn(null);
        store = new AttachmentStore(local, noS3);
        migration = new V5__backfill_attachment_bytes(store);
    }

    private Context context() {
        return new Context() {
            @Override
            public Configuration getConfiguration() {
                return null;
            }

            @Override
            public Connection getConnection() {
                return cx;
            }
        };
    }

    private void insert(String attId, String msgId, byte[] content, String sha256) throws Exception {
        try (var ps = cx.prepareStatement(
                "INSERT INTO attachments (attachment_id, message_id, sha256, content) VALUES (?,?,?,?)")) {
            ps.setString(1, attId);
            ps.setString(2, msgId);
            ps.setString(3, sha256);
            ps.setBytes(4, content);
            ps.executeUpdate();
        }
    }

    private String scalar(String sql) throws Exception {
        try (Statement st = cx.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void isDeclaredAsVersion5AndRunsOutsideATransaction() {
        assertThat(migration.getVersion().getVersion()).isEqualTo("5");
        // Batch commits, so the work is resumable rather than one transaction over the whole corpus.
        assertThat(migration.canExecuteInTransaction()).isFalse();
    }

    @Test
    void movesBytesToDiskPointsTheRowAtThemAndClearsTheDatabaseCopy() throws Exception {
        byte[] bytes = utf8("pre-existing attachment bytes");
        insert("att-1", "m-1", bytes, MessageMapper.checksum(bytes));

        migration.migrate(context());

        assertThat(scalar("SELECT storage_location FROM attachments WHERE attachment_id='att-1'"))
                .isEqualTo("m-1/att-1");
        // The DB copy is only dropped once the on-disk copy has been verified.
        assertThat(scalar("SELECT content FROM attachments WHERE attachment_id='att-1'")).isNull();
        assertThat(local.load("m-1/att-1")).isEqualTo(bytes);
    }

    @Test
    void isANoOpOnAFreshInstall() throws Exception {
        migration.migrate(context());

        assertThat(Files.list(tmp.resolve("attachments")).count()).isZero();
    }

    @Test
    void handlesMoreRowsThanOneBatch() throws Exception {
        // BATCH_SIZE is 200; 450 rows exercises the loop and the final partial batch.
        for (int i = 0; i < 450; i++) {
            byte[] bytes = utf8("attachment number " + i);
            insert("att-" + i, "m-" + (i % 7), bytes, MessageMapper.checksum(bytes));
        }

        migration.migrate(context());

        assertThat(scalar("SELECT count(*) FROM attachments WHERE storage_location=''")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM attachments WHERE content IS NOT NULL")).isEqualTo("0");
        for (int i = 0; i < 450; i++) {
            assertThat(local.load("m-" + (i % 7) + "/att-" + i))
                    .isEqualTo(utf8("attachment number " + i));
        }
    }

    @Test
    void isIdempotentSoARerunAfterAPartialRunDoesNothingExtra() throws Exception {
        byte[] bytes = utf8("bytes");
        insert("att-1", "m-1", bytes, MessageMapper.checksum(bytes));
        migration.migrate(context());

        // Second run: the row no longer matches storage_location = '' so there is nothing to do.
        migration.migrate(context());

        assertThat(scalar("SELECT storage_location FROM attachments WHERE attachment_id='att-1'"))
                .isEqualTo("m-1/att-1");
        assertThat(local.load("m-1/att-1")).isEqualTo(bytes);
    }

    @Test
    void leavesAlreadyMigratedRowsUntouched() throws Exception {
        byte[] bytes = utf8("already on disk");
        local.store("m-9", "att-9", bytes);
        try (var ps = cx.prepareStatement(
                "INSERT INTO attachments (attachment_id, message_id, sha256, content, storage_location)"
                        + " VALUES ('att-9','m-9',?,NULL,'m-9/att-9')")) {
            ps.setString(1, MessageMapper.checksum(bytes));
            ps.executeUpdate();
        }

        migration.migrate(context());

        assertThat(local.load("m-9/att-9")).isEqualTo(bytes);
    }

    @Test
    void failsAndKeepsTheDatabaseCopyWhenTheStoredSha256DoesNotMatchTheStoredBytes() throws Exception {
        // A pre-existing integrity problem must surface, not get baked into the new layout.
        insert("att-bad", "m-1", utf8("real bytes"), "0".repeat(64));

        assertThatThrownBy(() -> migration.migrate(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("att-bad");

        // Bytes and pointer untouched, so nothing is lost and the row can be investigated.
        assertThat(scalar("SELECT storage_location FROM attachments WHERE attachment_id='att-bad'")).isEmpty();
        assertThat(scalar("SELECT content FROM attachments WHERE attachment_id='att-bad'")).isNotNull();
    }

    @Test
    void failsWithoutClearingContentWhenTheBlobStoreCannotBeWritten() throws Exception {
        LocalBlobStorage failing = mock(LocalBlobStorage.class);
        when(failing.store(anyString(), anyString(), any()))
                .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("read-only fs")));
        @SuppressWarnings("unchecked")
        ObjectProvider<S3BlobStorage> noS3 = mock(ObjectProvider.class);
        var brokenMigration = new V5__backfill_attachment_bytes(new AttachmentStore(failing, noS3));
        byte[] bytes = utf8("bytes");
        insert("att-1", "m-1", bytes, MessageMapper.checksum(bytes));

        assertThatThrownBy(() -> brokenMigration.migrate(context()))
                .isInstanceOf(IllegalStateException.class);

        // The only surviving copy is still in the database.
        assertThat(scalar("SELECT content FROM attachments WHERE attachment_id='att-1'")).isNotNull();
        assertThat(scalar("SELECT storage_location FROM attachments WHERE attachment_id='att-1'")).isEmpty();
    }

    @Test
    void migratesTheGoodRowsEvenWhenOneIsBad() throws Exception {
        byte[] good = utf8("good bytes");
        insert("att-good", "m-1", good, MessageMapper.checksum(good));
        insert("att-bad", "m-1", utf8("bytes"), "f".repeat(64));

        assertThatThrownBy(() -> migration.migrate(context())).isInstanceOf(IllegalStateException.class);

        // The batch commits what it could, so a single bad row does not block the rest.
        assertThat(scalar("SELECT storage_location FROM attachments WHERE attachment_id='att-good'"))
                .isEqualTo("m-1/att-good");
        assertThat(local.load("m-1/att-good")).isEqualTo(good);
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void terminatesEvenWhenEveryRowIsUnmovable() throws Exception {
        // Regression: paging on "storage_location = ''" alone re-read an unmovable row forever, so
        // one bad attachment hung startup instead of failing it. More rows than one batch, all bad.
        for (int i = 0; i < 250; i++) {
            insert(String.format("att-%04d", i), "m-1", utf8("bytes " + i), "0".repeat(64));
        }

        assertThatThrownBy(() -> migration.migrate(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("250");
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void terminatesWithAMixOfMovableAndUnmovableRowsAcrossBatches() throws Exception {
        for (int i = 0; i < 250; i++) {
            byte[] bytes = utf8("bytes " + i);
            boolean bad = i % 3 == 0;
            insert(String.format("att-%04d", i), "m-1", bytes,
                    bad ? "0".repeat(64) : MessageMapper.checksum(bytes));
        }

        assertThatThrownBy(() -> migration.migrate(context())).isInstanceOf(IllegalStateException.class);

        // Every good row moved; only the bad ones are left holding their database copy.
        assertThat(scalar("SELECT count(*) FROM attachments WHERE storage_location <> ''")).isEqualTo("166");
        assertThat(scalar("SELECT count(*) FROM attachments WHERE content IS NOT NULL")).isEqualTo("84");
    }

    @Test
    void refusesARowWithNoSha256BecauseTheCopyCannotBeVerified() throws Exception {
        // sha256 is the only anchor proving the on-disk copy is faithful. Without one there is
        // nothing to check against, so the bytes stay in the column and the row gets flagged
        // rather than being quietly relocated as unverified evidence.
        insert("att-1", "m-1", utf8("bytes"), "");

        assertThatThrownBy(() -> migration.migrate(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("att-1");

        assertThat(scalar("SELECT content FROM attachments WHERE attachment_id='att-1'")).isNotNull();
        assertThat(scalar("SELECT storage_location FROM attachments WHERE attachment_id='att-1'")).isEmpty();
    }
}
