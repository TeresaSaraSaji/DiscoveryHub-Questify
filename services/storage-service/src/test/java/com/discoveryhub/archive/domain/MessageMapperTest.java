package com.discoveryhub.archive.domain;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageMapperTest {

    private final MessageMapper mapper = new MessageMapper(new ObjectMapper());

    @Test
    void toEntityPreservesAllScalarFieldsAndSerialisesLists() {
        Message in = sample("EXCH-001", "subj", List.of("a@x.com", "b@x.com"), List.of("PRIVILEGED"));

        MessageEntity e = mapper.toEntity(in);

        assertThat(e.getMessageId()).isNotNull();
        assertThat(e.getExternalId()).isEqualTo("EXCH-001");
        assertThat(e.getType()).isEqualTo(MessageType.EMAIL);
        assertThat(e.getFrom()).isEqualTo("from@x.com");
        assertThat(e.getSubject()).isEqualTo("subj");
        assertThat(e.getThreadId()).isEqualTo("thread-1");
        // Lists are JSON-serialised, not lost.
        assertThat(e.getTo()).contains("a@x.com", "b@x.com");
        assertThat(e.getCc()).isEqualTo("[]");
        assertThat(e.getLabels()).contains("PRIVILEGED");
        assertThat(e.getAttachmentCount()).isEqualTo(1);
        assertThat(e.isOnHold()).isFalse();
        assertThat(e.getArchivedAt()).isNotNull();
    }

    @Test
    void toArchivedDropsAttachmentContentAndKeepsSha256() {
        byte[] bytes = "hello".getBytes();
        AttachmentEntity att = new AttachmentEntity();
        att.setAttachmentId("att-1");
        att.setMessageId("m-1");
        att.setFilename("a.txt");
        att.setContentType("text/plain");
        att.setSizeBytes(bytes.length);
        att.setSha256("deadbeef");
        att.setContentBytes(bytes);

        MessageEntity msg = mapper.toEntity(sample("EXCH-002", "s", List.of(), List.of()));
        msg.setMessageId("m-1");

        Message archived = mapper.toArchived(msg, List.of(att));

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
        Attachment a = new Attachment(null, "f.bin", "application/octet-stream", bytes.length, null, b64);

        AttachmentEntity e = mapper.toEntity(a, "m-1", 0);

        String expected = MessageMapper.checksum(bytes);
        assertThat(e.getSha256()).isEqualTo(expected);
        assertThat(e.getContentBytes()).isEqualTo(bytes);
        // attachmentId is derived from the message id + ordinal when absent.
        assertThat(e.getAttachmentId()).isNotNull();
    }

    @Test
    void roundTripsListsThroughJson() {
        Message in = sample("EXCH-003", "subj", List.of("x@y.com", "z@y.com"), List.of("PRIVILEGED", "SENSITIVE"));
        MessageEntity e = mapper.toEntity(in);
        Message out = mapper.toArchived(e, List.of());

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
