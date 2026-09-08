package com.discoveryhub.export.service;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageBuilderTest {

    @Mock ArchiveClient archive;

    private final ObjectMapper json = new ObjectMapper();
    private PackageBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new PackageBuilder(archive, json);
    }

    @Test
    void writesEachMessageAndAttachmentWithARecomputedChecksum() throws IOException {
        byte[] attachmentBytes = "ref,amount\nNG-1,100\n".getBytes();
        String attachmentSha256 = sha256(attachmentBytes);
        Message message = message("m-1", attachmentSha256);
        when(archive.findMessage("m-1")).thenReturn(Optional.of(message));
        when(archive.fetchAttachmentBytes("m-1", "att-1")).thenReturn(attachmentBytes);

        PackageResult result = builder.build("job-1", "case-1", List.of("m-1"));

        assertThat(result.itemCount()).isEqualTo(2); // one message + one attachment
        assertThat(result.packageSha256()).hasSize(64);

        Manifest manifest = readManifest(result.zipBytes());
        assertThat(manifest.items()).hasSize(2);
        ManifestItem attachmentItem = manifest.items().stream()
                .filter(i -> "attachment".equals(i.type())).findFirst().orElseThrow();
        assertThat(attachmentItem.sha256()).isEqualToIgnoringCase(attachmentSha256);
    }

    @Test
    void refusesToPackageAnAttachmentWhoseFetchedBytesDoNotMatchItsRecordedChecksum() {
        Message message = message("m-2", "0".repeat(64)); // a checksum the real bytes will never match
        when(archive.findMessage("m-2")).thenReturn(Optional.of(message));
        when(archive.fetchAttachmentBytes("m-2", "att-1")).thenReturn("actual bytes".getBytes());

        assertThatThrownBy(() -> builder.build("job-2", "case-1", List.of("m-2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sha256 mismatch");
    }

    @Test
    void aMessageNoLongerInTheArchiveFailsTheWholeBuild() {
        when(archive.findMessage("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> builder.build("job-3", "case-1", List.of("gone")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gone");
    }

    private Manifest readManifest(byte[] zipBytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName())) {
                    return json.readValue(zip.readAllBytes(), Manifest.class);
                }
            }
        }
        throw new IllegalStateException("manifest.json not found in built package");
    }

    private static Message message(String messageId, String attachmentSha256) {
        Attachment attachment = new Attachment("att-1", "figures.csv", "text/csv", 20, attachmentSha256, null);
        return new Message(messageId, "EXCH-" + messageId, "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@x.com", List.of("to@x.com"), List.of(), "subj", "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(attachment), List.of());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
