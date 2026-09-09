package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.ContentHash;
import com.discoveryhub.contracts.Ids;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.ingestion.api.IngestResponse;
import com.discoveryhub.ingestion.api.IngestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestServiceTest {

    private InMemoryDedupeStore dedupe;
    private RecordingPublisher publisher;
    private IngestService service;

    @BeforeEach
    void setUp() {
        dedupe = new InMemoryDedupeStore();
        publisher = new RecordingPublisher();
        service = new IngestService(dedupe, publisher,
                new InMemoryMessageIdMappingStore(),
                Clock.fixed(Instant.parse("2024-05-11T21:37:00Z"), ZoneOffset.UTC));
    }

    @Test
    void acceptsAMessageAndDerivesItsIds() {
        IngestResponse response = service.ingest(List.of(email("EXCH-1", "alice")));

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(publisher.ingested).hasSize(1);
        assertThat(publisher.ingested.getFirst().messageId())
                .isEqualTo(Ids.messageId("EXCH-1"));
        assertThat(publisher.auditActions()).containsExactly("message.ingested");
    }

    @Test
    void derivesAttachmentIdsFromExternalIdAndIndex() {
        Message withAttachment = new Message(
                null, "EXCH-att", "EXCHANGE", MessageType.EMAIL, "alice", "alice@firm.test",
                List.of("bob@firm.test"), List.of(), "Q2 numbers", "see attached",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null,
                List.of(new Attachment(null, "q2.xlsx", "application/vnd.ms-excel", 3,
                        "709e80c88487a2411e1ee4dfb9f22a861492d20c4765150c0c794abd70f8147c", "AAAA")),
                List.of());

        service.ingest(List.of(withAttachment));

        assertThat(publisher.ingested.getFirst().attachments().getFirst().attachmentId())
                .isEqualTo(Ids.attachmentId("EXCH-att", 0));
    }

    @Test
    void resubmittingTheSameExternalIdIsADuplicateNotAnError() {
        service.ingest(List.of(email("EXCH-1", "alice")));
        IngestResponse second = service.ingest(List.of(email("EXCH-1", "alice")));

        assertThat(second.duplicates()).isEqualTo(1);
        assertThat(second.results().getFirst().outcome()).isEqualTo(IngestResult.Outcome.DUPLICATE);
        assertThat(publisher.ingested).hasSize(1);
        assertThat(publisher.auditActions()).containsExactly("message.ingested", "message.deduped");
    }

    @Test
    void aDuplicateInTheMiddleOfABatchDoesNotRejectTheRest() {
        service.ingest(List.of(email("EXCH-2", "alice")));
        publisher.ingested.clear();

        IngestResponse response = service.ingest(List.of(
                distinctEmail("EXCH-1", "alice"),
                email("EXCH-2", "alice"),
                distinctEmail("EXCH-3", "alice")));

        assertThat(response.accepted()).isEqualTo(2);
        assertThat(response.duplicates()).isEqualTo(1);
        assertThat(publisher.ingested).hasSize(2);
    }

    @Test
    void sameConversationFromTwoMailboxesIsNotADuplicate() {
        // The trap in docs/message-schema.md: identical body, two externalIds, two custodians.
        // A hold on one custodian must preserve their copy independently of the other's, so an
        // implementation keying dedupe on body or from+subject+sentAt must fail here.
        Message alicesCopy = email("EXCH-alice-77", "alice");
        Message bobsCopy = email("EXCH-bob-77", "bob");

        IngestResponse response = service.ingest(List.of(alicesCopy, bobsCopy));

        assertThat(response.accepted()).isEqualTo(2);
        assertThat(response.duplicates()).isZero();
        assertThat(publisher.ingested)
                .extracting(Message::custodianId)
                .containsExactly("alice", "bob");
    }

    @Test
    void invalidMessagesAreRejectedPerItemWithAReason() {
        Message noCustodian = new Message(
                null, "EXCH-bad", "EXCHANGE", MessageType.EMAIL, null, "alice@firm.test",
                List.of(), List.of(), "subject", "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(), List.of());

        IngestResponse response = service.ingest(List.of(noCustodian, email("EXCH-ok", "alice")));

        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.results().getFirst().reason()).contains("custodianId");
        assertThat(publisher.auditActions()).contains("message.rejected");
    }

    @Test
    void chatWithoutSubjectIsValidButEmailWithoutSubjectIsNot() {
        Message chat = new Message(
                null, "TEAMS-1", "TEAMS", MessageType.CHAT, "alice", "alice@firm.test",
                List.of("bob@firm.test"), List.of(), null, "ping",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-2", null, List.of(), List.of());
        Message emailNoSubject = new Message(
                null, "EXCH-nosubj", "EXCHANGE", MessageType.EMAIL, "alice", "alice@firm.test",
                List.of("bob@firm.test"), List.of(), null, "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-3", null, List.of(), List.of());

        IngestResponse response = service.ingest(List.of(chat, emailNoSubject));

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
    }

    @Test
    void aFailedPublishReleasesTheClaimSoARetryIsNotSwallowed() {
        publisher.failNextPublishes(new IllegalStateException("broker down"));

        IngestResponse first = service.ingest(List.of(email("EXCH-9", "alice")));

        assertThat(first.failed()).isEqualTo(1);
        assertThat(dedupe.hasClaim(DedupeStore.EXTERNAL_ID, "EXCH-9")).isFalse();
        assertThat(dedupe.hasClaim(DedupeStore.CONTENT_HASH,
                ContentHash.of(email("EXCH-9", "alice")))).isFalse();
        assertThat(publisher.auditActions()).containsExactly("message.ingest_failed");

        publisher.failNextPublishes(null);
        IngestResponse retry = service.ingest(List.of(email("EXCH-9", "alice")));

        assertThat(retry.accepted()).isEqualTo(1);
        assertThat(publisher.ingested).hasSize(1);
    }

    @Test
    void identicalContentUnderADifferentExternalIdIsADuplicate() {
        // A re-export or a second connector on one mailbox produces this: same message, new
        // source key. externalId alone cannot see it.
        IngestResponse response = service.ingest(List.of(
                email("EXCH-first", "alice"),
                email("EXCH-reexported", "alice")));

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.duplicates()).isEqualTo(1);
        assertThat(publisher.ingested).hasSize(1);
        assertThat(publisher.auditActions())
                .containsExactly("message.ingested", "message.deduped");
    }

    @Test
    void contentHashCoversTheCustodianSoTwoMailboxesStayDistinct() {
        // The regression guard for ContentHash. If custodianId ever leaves the fingerprint, these
        // two collapse into one and a hold on bob preserves nothing.
        assertThat(ContentHash.of(email("EXCH-a", "alice")))
                .isNotEqualTo(ContentHash.of(email("EXCH-b", "bob")));
    }

    @Test
    void contentHashIgnoresTheSourceKeyButNotTheContent() {
        assertThat(ContentHash.of(email("EXCH-1", "alice")))
                .isEqualTo(ContentHash.of(email("EXCH-2", "alice")));

        Message edited = new Message(
                null, "EXCH-3", "EXCHANGE", MessageType.EMAIL, "alice", "alice@firm.test",
                List.of("bob@firm.test"), List.of(), "Q2 numbers", "a different body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-77", null, List.of(), List.of());
        assertThat(ContentHash.of(edited))
                .isNotEqualTo(ContentHash.of(email("EXCH-1", "alice")));
    }

    @Test
    void fieldBoundariesCannotBeForgedByContent() {
        // Length-prefixing exists for this: without it, shifting a character between two adjacent
        // fields would produce the same canonical string and the same fingerprint.
        Message a = new Message(null, "EXCH-a", "EXCHANGE", MessageType.EMAIL, "alice",
                "alice@firm.test", List.of(), List.of(), "AB", "C",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(), List.of());
        Message b = new Message(null, "EXCH-b", "EXCHANGE", MessageType.EMAIL, "alice",
                "alice@firm.test", List.of(), List.of(), "A", "BC",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(), List.of());

        assertThat(ContentHash.of(a)).isNotEqualTo(ContentHash.of(b));
    }

    /**
     * Identical content whatever the external id. Several tests depend on that: it is what makes
     * the two-mailbox case and the content-fingerprint cases meaningful.
     */
    private static Message email(String externalId, String custodianId) {
        return new Message(
                null, externalId, "EXCHANGE", MessageType.EMAIL, custodianId,
                "alice@firm.test", List.of("bob@firm.test"), List.of(),
                "Q2 numbers", "the same body text in both mailboxes",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-77", null, List.of(), List.of());
    }

    /** A genuinely different message, for tests about batching rather than about dedupe. */
    private static Message distinctEmail(String externalId, String custodianId) {
        return new Message(
                null, externalId, "EXCHANGE", MessageType.EMAIL, custodianId,
                "alice@firm.test", List.of("bob@firm.test"), List.of(),
                "Q2 numbers", "body of " + externalId,
                Instant.parse("2024-05-11T21:37:00Z"), "thread-77", null, List.of(), List.of());
    }
}
