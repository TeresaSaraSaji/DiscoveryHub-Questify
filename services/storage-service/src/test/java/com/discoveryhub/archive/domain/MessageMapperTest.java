package com.discoveryhub.archive.domain;

import com.discoveryhub.archive.config.RetentionProperties;
import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.contracts.RetentionLabels;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageMapperTest {

    private final RetentionProperties retention =
            new RetentionProperties(null, Map.of(), Duration.ofMinutes(2));
    private final MessageMapper mapper = new MessageMapper(retention);

    @Test
    void toDocumentPreservesAllFieldsAndEmbedsAttachments() {
        Message in = sample("EXCH-001", "subj", List.of("a@x.com", "b@x.com"), List.of("PRIVILEGED"));

        ArchivedMessageDocument doc = mapper.toDocument(in);

        assertThat(doc.messageId()).isNotNull();
        assertThat(doc.externalId()).isEqualTo("EXCH-001");
        assertThat(doc.type()).isEqualTo(MessageType.EMAIL);
        assertThat(doc.from()).isEqualTo("from@x.com");
        assertThat(doc.subject()).isEqualTo("subj");
        assertThat(doc.threadId()).isEqualTo("thread-1");
        assertThat(doc.to()).containsExactly("a@x.com", "b@x.com");
        assertThat(doc.cc()).isEmpty();
        assertThat(doc.labels()).containsExactly("PRIVILEGED");
        assertThat(doc.attachments()).hasSize(1);
        assertThat(doc.archivedAt()).isNotNull();
    }

    @Test
    void toHoldStatusStartsUnheldWithZeroCount() {
        Message in = sample("EXCH-001", "subj", List.of(), List.of());

        MessageHoldStatus status = mapper.toHoldStatus(in);

        assertThat(status.getExternalId()).isEqualTo("EXCH-001");
        assertThat(status.getCustodianId()).isEqualTo("custodian-1");
        assertThat(status.isOnHold()).isFalse();
        assertThat(status.getHoldCount()).isEqualTo(0);
        assertThat(status.getArchivedAt()).isNotNull();
        // No RetentionLabels.DEMO_RETENTION label -> normal, type-based retention applies.
        assertThat(status.getRetentionOverrideAt()).isNull();
    }

    @Test
    void toHoldStatusSetsAShortOverrideForADemoTaggedMessage() {
        Message in = sample("EXCH-DEMO", "subj", List.of(), List.of(RetentionLabels.DEMO_RETENTION));
        Instant before = Instant.now();

        MessageHoldStatus status = mapper.toHoldStatus(in);

        Instant after = Instant.now();
        assertThat(status.getRetentionOverrideAt()).isNotNull();
        // Roughly archivedAt + demoPeriod (2 minutes here), not years away.
        assertThat(status.getRetentionOverrideAt())
                .isAfter(before.plus(retention.demoPeriod()).minusSeconds(1))
                .isBefore(after.plus(retention.demoPeriod()).plusSeconds(1));
    }

    @Test
    void toArchivedDropsAttachmentContentAndKeepsSha256() {
        byte[] bytes = "hello".getBytes();
        byte[] decodedBack = Base64.getDecoder().decode(Base64.getEncoder().encodeToString(bytes));
        AttachmentDocument att = new AttachmentDocument(
                "att-1", 0, "a.txt", "text/plain", decodedBack.length, "deadbeef",
                Base64.getEncoder().encodeToString(bytes));

        ArchivedMessageDocument doc = mapper.toDocument(sample("EXCH-002", "s", List.of(), List.of()));
        ArchivedMessageDocument withAttachment = new ArchivedMessageDocument(
                doc.messageId(), doc.externalId(), doc.source(), doc.type(), doc.custodianId(),
                doc.from(), doc.to(), doc.cc(), doc.subject(), doc.body(), doc.sentAt(),
                doc.threadId(), doc.inReplyTo(), doc.labels(), List.of(att), doc.archivedAt());

        Message archived = mapper.toArchived(withAttachment);

        // contentBase64 must be null — that is the chain-of-custody handoff (message-schema.md).
        assertThat(archived.attachments()).hasSize(1);
        Attachment wire = archived.attachments().get(0);
        assertThat(wire.sha256()).isEqualTo("deadbeef");
        assertThat(wire.contentBase64()).isNull();
        assertThat(wire.sizeBytes()).isEqualTo(bytes.length);
    }

    @Test
    void attachmentSha256IsDerivedFromBytesWhenSourceOmitsIt() {
        byte[] bytes = "verify-me".getBytes();
        String b64 = Base64.getEncoder().encodeToString(bytes);
        Message in = new Message(
                null, "EXCH-004", "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@x.com", List.of(), List.of(), "s", "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null,
                List.of(new Attachment(null, "f.bin", "application/octet-stream", bytes.length, null, b64)),
                List.of());

        ArchivedMessageDocument doc = mapper.toDocument(in);

        String expected = MessageMapper.checksum(bytes);
        AttachmentDocument att = doc.attachments().get(0);
        assertThat(att.sha256()).isEqualTo(expected);
        assertThat(att.contentBase64()).isEqualTo(b64);
        // attachmentId is derived from the message id + ordinal when absent.
        assertThat(att.attachmentId()).isNotNull();
    }

    @Test
    void roundTripsListsThroughDocument() {
        Message in = sample("EXCH-003", "subj", List.of("x@y.com", "z@y.com"), List.of("PRIVILEGED", "SENSITIVE"));
        ArchivedMessageDocument doc = mapper.toDocument(in);
        Message out = mapper.toArchived(doc);

        assertThat(out.to()).containsExactly("x@y.com", "z@y.com");
        assertThat(out.labels()).containsExactly("PRIVILEGED", "SENSITIVE");
        assertThat(out.cc()).isEmpty();
    }

    private static Message sample(String externalId, String subject, List<String> to, List<String> labels) {
        return new Message(
                null, externalId, "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@x.com", to, List.of(), subject, "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null,
                List.of(new Attachment(null, "a.txt", "text/plain", 0, null, null)),
                labels);
    }
}
