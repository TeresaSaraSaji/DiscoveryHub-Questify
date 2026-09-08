package com.discoveryhub.holds.adapter;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Adapter maps the frozen {@link Message} contract to the hold-service's internal
 * {@link MessageReference}, extracting only the fields scope resolution needs and dropping the
 * heavy ones (attachments, participants). A message with a null subject (a CHAT message) must
 * produce a null subject on the reference, not a default.
 */
class MessageReferenceAdapterTest {

    private final MessageReferenceAdapter adapter = new MessageReferenceAdapter();

    @Test
    void adaptsAllFields() {
        Message message = new Message(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@x.com", List.of("to@x.com"), List.of("cc@x.com"), "subject", "body",
                Instant.parse("2024-06-15T12:00:00Z"), "thread-1", null, List.of(), List.of());

        MessageReference ref = adapter.adapt(message);

        assertThat(ref.messageId()).isEqualTo("msg-1");
        assertThat(ref.custodianId()).isEqualTo("custodian-1");
        assertThat(ref.sentAt()).isEqualTo(Instant.parse("2024-06-15T12:00:00Z"));
        assertThat(ref.subject()).isEqualTo("subject");
        assertThat(ref.body()).isEqualTo("body");
    }

    @Test
    void adaptsChatMessageWithNullSubject() {
        Message message = new Message(
                "msg-2", "ext-2", "TEAMS", MessageType.CHAT, "custodian-2",
                "from@x.com", List.of("to@x.com"), List.of(), null, "chat body",
                Instant.parse("2024-06-15T12:00:00Z"), "thread-2", null, List.of(), List.of());

        MessageReference ref = adapter.adapt(message);

        assertThat(ref.subject()).isNull();
        assertThat(ref.body()).isEqualTo("chat body");
    }

    @Test
    void dropsAttachmentsAndParticipants() {
        Message message = new Message(
                "msg-3", "ext-3", "EXCHANGE", MessageType.EMAIL, "custodian-3",
                "from@x.com", List.of("to@x.com", "external@other.com"), List.of("cc@x.com"),
                "subject", "body", Instant.parse("2024-06-15T12:00:00Z"), "thread-3", null,
                List.of(), List.of("PRIVILEGED"));

        MessageReference ref = adapter.adapt(message);

        // MessageReference has no attachment or label fields — the adapter dropped them.
        assertThat(ref).extracting("messageId", "custodianId", "sentAt", "subject", "body")
                .containsExactly("msg-3", "custodian-3",
                        Instant.parse("2024-06-15T12:00:00Z"), "subject", "body");
    }
}
