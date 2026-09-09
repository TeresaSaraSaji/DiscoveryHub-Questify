package com.discoveryhub.archive.storage;

import com.discoveryhub.archive.domain.AttachmentEntity;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.contracts.Attachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The storage orchestrator, which is where the durability promise actually lives. Three properties
 * are load-bearing and each has its own group of tests:
 *
 * <ul>
 *   <li><b>Local disk is the primary guarantee.</b> A local write failure must propagate, so the
 *       Kafka error handler retries instead of committing an offset for bytes that were never
 *       written.</li>
 *   <li><b>S3 is best-effort.</b> An S3 outage must not wedge ingestion, and a failed offload must
 *       leave {@code s3_key} null so a later read never chases a copy that was never written.</li>
 *   <li><b>Reads fall back.</b> A missing local file is served from the S3 offload copy when there
 *       is one, and fails loudly when there is not — never as zero bytes, which would silently
 *       corrupt an export manifest.</li>
 * </ul>
 *
 * <p>{@link LocalBlobStorage} is the real thing against a {@link TempDir} wherever the filesystem
 * is the behaviour under test, and a mock only where a failure has to be injected.
 */
class AttachmentStoreTest {

    @TempDir Path tmp;

    private final MessageMapper mapper = new MessageMapper(new ObjectMapper());

    private LocalBlobStorage local;
    private S3BlobStorage s3;
    private ObjectProvider<S3BlobStorage> s3Provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        local = new LocalBlobStorage(new StorageProperties(
                new StorageProperties.Local(tmp.resolve("attachments").toString()), null));
        s3 = mock(S3BlobStorage.class);
        s3Provider = mock(ObjectProvider.class);
    }

    /** S3 disabled: the bean is absent, so the provider yields null. */
    private AttachmentStore withoutS3() {
        when(s3Provider.getIfAvailable()).thenReturn(null);
        return new AttachmentStore(local, s3Provider);
    }

    private AttachmentStore withS3() {
        when(s3Provider.getIfAvailable()).thenReturn(s3);
        return new AttachmentStore(local, s3Provider);
    }

    private AttachmentEntity attachment(byte[] bytes) {
        String b64 = bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
        return mapper.toEntity(new Attachment("att-1", "invoice.pdf", "application/pdf",
                bytes == null ? 0 : bytes.length, null, b64), "m-1", 0);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ store

    @Test
    void storeWritesLocallyAndRecordsTheRelativeLocation() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(utf8("invoice bytes"));

        store.store(att);

        assertThat(att.getStorageLocation()).isEqualTo("m-1/att-1");
        assertThat(tmp.resolve("attachments/m-1/att-1")).exists();
        assertThat(local.load(att.getStorageLocation())).isEqualTo(utf8("invoice bytes"));
    }

    @Test
    void storeLeavesS3PointersNullWhenS3IsDisabled() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(utf8("bytes"));

        store.store(att);

        // A read must not chase an S3 copy that was never written.
        assertThat(att.getS3Key()).isNull();
        assertThat(att.getS3Bucket()).isNull();
        verifyNoInteractions(s3);
    }

    @Test
    void storeClearsTheTransientBytesSoTheyAreNotHeldAcrossTheJpaSave() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        assertThat(att.getContentBytes()).isNotNull();

        store.store(att);

        assertThat(att.getContentBytes()).isNull();
    }

    @Test
    void storeAlsoOffloadsToS3AndRecordsKeyAndBucketWhenEnabled() {
        AttachmentStore store = withS3();
        when(s3.store("m-1", "att-1", utf8("bytes"))).thenReturn("attachments/m-1/att-1");
        when(s3.bucket()).thenReturn("archive-attachments");
        AttachmentEntity att = attachment(utf8("bytes"));

        store.store(att);

        assertThat(att.getStorageLocation()).isEqualTo("m-1/att-1");
        assertThat(att.getS3Key()).isEqualTo("attachments/m-1/att-1");
        // The bucket is recorded per row so a read is self-describing even if config changes later.
        assertThat(att.getS3Bucket()).isEqualTo("archive-attachments");
        assertThat(tmp.resolve("attachments/m-1/att-1")).exists();
    }

    @Test
    void storeSurvivesAnS3OutageAndLeavesS3KeyNull() {
        AttachmentStore store = withS3();
        when(s3.store(anyString(), anyString(), any())).thenThrow(new RuntimeException("connection refused"));
        AttachmentEntity att = attachment(utf8("bytes"));

        // Ingestion must not fail because the offload target is down.
        store.store(att);

        assertThat(att.getStorageLocation()).isEqualTo("m-1/att-1");
        assertThat(tmp.resolve("attachments/m-1/att-1")).exists();
        assertThat(att.getS3Key()).isNull();
        assertThat(att.getS3Bucket()).isNull();
    }

    @Test
    void storePropagatesALocalWriteFailureSoKafkaRetriesInsteadOfCommitting() {
        LocalBlobStorage failing = mock(LocalBlobStorage.class);
        when(failing.store(anyString(), anyString(), any()))
                .thenThrow(new UncheckedIOException(new java.io.IOException("disk full")));
        AttachmentStore store = new AttachmentStore(failing, s3Provider);

        assertThatThrownBy(() -> store.store(attachment(utf8("bytes"))))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void storeOfAnAttachmentWithNoBytesWritesAnEmptyBlob() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(null);

        store.store(att);

        assertThat(tmp.resolve("attachments/m-1/att-1")).exists();
        assertThat(local.load(att.getStorageLocation())).isEmpty();
    }

    @Test
    void storeIsIdempotentAcrossARedeliveryOfTheSameRecord() {
        AttachmentStore store = withoutS3();

        store.store(attachment(utf8("v1")));
        AttachmentEntity redelivered = attachment(utf8("v2"));
        store.store(redelivered);

        assertThat(redelivered.getStorageLocation()).isEqualTo("m-1/att-1");
        assertThat(local.load("m-1/att-1")).isEqualTo(utf8("v2"));
    }

    // ------------------------------------------------------------------- load

    @Test
    void loadServesTheLocalCopyAndNeverTouchesS3WhenItIsPresent() {
        AttachmentStore store = withS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        when(s3.store(anyString(), anyString(), any())).thenReturn("attachments/m-1/att-1");
        when(s3.bucket()).thenReturn("archive-attachments");
        store.store(att);

        assertThat(store.load(att)).isEqualTo(utf8("bytes"));
        verify(s3, never()).load(anyString());
    }

    @Test
    void loadFallsBackToTheS3OffloadCopyWhenTheLocalFileIsGone() throws Exception {
        AttachmentStore store = withS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        when(s3.store(anyString(), anyString(), any())).thenReturn("attachments/m-1/att-1");
        when(s3.bucket()).thenReturn("archive-attachments");
        store.store(att);

        // Simulate the local copy ageing out from under us.
        Files.delete(tmp.resolve("attachments/m-1/att-1"));
        when(s3.load("attachments/m-1/att-1")).thenReturn(utf8("bytes from s3"));

        assertThat(store.load(att)).isEqualTo(utf8("bytes from s3"));
        verify(s3).load("attachments/m-1/att-1");
    }

    @Test
    void loadFailsLoudlyWhenTheLocalFileIsGoneAndThereIsNoS3Copy() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        store.store(att);
        assertThat(att.getS3Key()).isNull();

        // Deleting the blob out from under the row must not degrade to zero bytes: an export
        // manifest built from an empty file would fail sha256 verification with no explanation.
        assertThatThrownBy(() -> {
            Files.delete(tmp.resolve("attachments/m-1/att-1"));
            store.load(att);
        }).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void loadFailsWhenTheRowNamesAnS3CopyButS3IsNoLongerEnabled() throws Exception {
        AttachmentStore store = withS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        when(s3.store(anyString(), anyString(), any())).thenReturn("attachments/m-1/att-1");
        when(s3.bucket()).thenReturn("archive-attachments");
        store.store(att);
        Files.delete(tmp.resolve("attachments/m-1/att-1"));

        // S3 turned off between the write and the read: no bean, so no fallback is possible.
        when(s3Provider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> store.load(att)).isInstanceOf(UncheckedIOException.class);
    }

    // ----------------------------------------------------------------- delete

    @Test
    void deleteRemovesBothCopies() {
        AttachmentStore store = withS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        when(s3.store(anyString(), anyString(), any())).thenReturn("attachments/m-1/att-1");
        when(s3.bucket()).thenReturn("archive-attachments");
        store.store(att);

        store.delete(att);

        assertThat(tmp.resolve("attachments/m-1/att-1")).doesNotExist();
        verify(s3).delete("attachments/m-1/att-1");
    }

    @Test
    void deleteSkipsS3WhenThereIsNoOffloadCopy() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        store.store(att);

        store.delete(att);

        assertThat(tmp.resolve("attachments/m-1/att-1")).doesNotExist();
        verify(s3, never()).delete(anyString());
    }

    @Test
    void deleteStillRemovesTheS3CopyWhenTheLocalDeleteFails() {
        LocalBlobStorage failing = mock(LocalBlobStorage.class);
        doThrow(new RuntimeException("read-only filesystem")).when(failing).delete(anyString());
        when(s3Provider.getIfAvailable()).thenReturn(s3);
        AttachmentStore store = new AttachmentStore(failing, s3Provider);
        when(failing.store(anyString(), anyString(), any())).thenReturn("m-1/att-1");
        when(s3.store(anyString(), anyString(), any())).thenReturn("attachments/m-1/att-1");
        when(s3.bucket()).thenReturn("archive-attachments");
        AttachmentEntity att = attachment(utf8("bytes"));
        store.store(att);

        // Best-effort: a stuck blob store must never block the row deletion that disposition needs.
        store.delete(att);

        verify(s3).delete("attachments/m-1/att-1");
    }

    @Test
    void deleteSwallowsAnS3FailureSoDispositionIsNeverBlocked() {
        AttachmentStore store = withS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        when(s3.store(anyString(), anyString(), any())).thenReturn("attachments/m-1/att-1");
        when(s3.bucket()).thenReturn("archive-attachments");
        store.store(att);
        doThrow(new RuntimeException("access denied")).when(s3).delete(eq("attachments/m-1/att-1"));

        store.delete(att);

        assertThat(tmp.resolve("attachments/m-1/att-1")).doesNotExist();
    }

    @Test
    void deleteOfAnAlreadyMissingBlobIsNotAnError() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        store.store(att);

        store.delete(att);
        store.delete(att);
    }

    // ------------------------------------------------- delete after commit

    @Test
    void deleteAfterCommitRunsImmediatelyWhenThereIsNoTransaction() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        store.store(att);

        store.deleteAfterCommit(List.of(att));

        assertThat(tmp.resolve("attachments/m-1/att-1")).doesNotExist();
    }

    @Test
    void deleteAfterCommitDefersUntilCommitWhenATransactionIsActive() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        store.store(att);

        TransactionSynchronizationManager.initSynchronization();
        try {
            store.deleteAfterCommit(List.of(att));

            // Still there: the row removal has not committed yet, so the bytes must not be gone.
            assertThat(tmp.resolve("attachments/m-1/att-1")).exists();

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            assertThat(tmp.resolve("attachments/m-1/att-1")).doesNotExist();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteAfterCommitLeavesBytesAloneWhenTheTransactionRollsBack() {
        AttachmentStore store = withoutS3();
        AttachmentEntity att = attachment(utf8("bytes"));
        store.store(att);

        TransactionSynchronizationManager.initSynchronization();
        try {
            store.deleteAfterCommit(List.of(att));
            // Rollback: afterCommit never fires, so the blob survives alongside the row that the
            // rollback restored. An orphan would be a leak; a missing blob would be data loss.
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(tmp.resolve("attachments/m-1/att-1")).exists();
        assertThat(store.load(att)).isEqualTo(utf8("bytes"));
    }

    @Test
    void deleteAfterCommitOfAnEmptyListRegistersNothing() {
        AttachmentStore store = new AttachmentStore(local, s3Provider);

        TransactionSynchronizationManager.initSynchronization();
        try {
            store.deleteAfterCommit(List.of());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
