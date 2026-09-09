package com.discoveryhub.search.mapper;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.search.config.SearchProperties;
import com.discoveryhub.search.model.CommunicationDocument;
import com.discoveryhub.search.model.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapper is pure translation — no Elasticsearch, no Spring context — so it is the cheapest
 * place to lock down the two things that matter: the archived shape maps to the index document with
 * the right hold default and attachment filenames (no bytes), and the snippet window lands around
 * the query term and marks its edges. The highlight path — where an Elasticsearch body fragment
 * (already wrapped in {@code <em>}) is preferred over the computed snippet — is covered here too.
 */
class CommunicationDocumentMapperTest {

    // A small snippet window makes the window arithmetic legible in the assertions.
    private final CommunicationDocumentMapper mapper =
            new CommunicationDocumentMapper(new SearchProperties("communications", 20, 100, 10000));

    private Message message;

    @BeforeEach
    void setUp() {
        message = new Message(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@firm.test", List.of("to@firm.test"), List.of("cc@firm.test"), "subject",
                "the body talks about fraud here",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null,
                List.of(new Attachment("att-1", "report.pdf", "application/pdf", 1024, "sha", null)),
                List.of("PRIVILEGED"));
    }

    @Test
    void fromMessageLowerCasesFromForCaseInsensitiveMatching() {
        // M4 regression: `from` is a Keyword field, so its wildcard/exact match is case-sensitive
        // against the raw stored string, unlike to/cc's analyzed Text match. Lower-casing at
        // index time (matched by SearchQueryBuilder lower-casing the query term) keeps a search
        // for "alice" matching From: Alice@Firm.Test the same way it already matches to/cc.
        Message mixedCase = new Message(
                "msg-2", "ext-2", "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "Alice@Firm.Test", List.of("to@firm.test"), List.of(), "subject", "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(), List.of());

        CommunicationDocument doc = mapper.fromMessage(mixedCase);

        assertThat(doc.getFrom()).isEqualTo("alice@firm.test");
    }

    @Test
    void fromMessageMapsEveryFieldAndDefaultsOnHoldToFalse() {
        CommunicationDocument doc = mapper.fromMessage(message);

        assertThat(doc.getMessageId()).isEqualTo("msg-1");
        assertThat(doc.getExternalId()).isEqualTo("ext-1");
        assertThat(doc.getSource()).isEqualTo("EXCHANGE");
        assertThat(doc.getType()).isEqualTo(MessageType.EMAIL);
        assertThat(doc.getCustodianId()).isEqualTo("custodian-1");
        assertThat(doc.getFrom()).isEqualTo("from@firm.test");
        assertThat(doc.getTo()).containsExactly("to@firm.test");
        assertThat(doc.getCc()).containsExactly("cc@firm.test");
        assertThat(doc.getSubject()).isEqualTo("subject");
        assertThat(doc.getBody()).isEqualTo("the body talks about fraud here");
        assertThat(doc.getSentAt()).isEqualTo(Instant.parse("2024-05-11T21:37:00Z"));
        assertThat(doc.getThreadId()).isEqualTo("thread-1");
        // A new message is never indexed as already held — holds arrive later, as events.
        assertThat(doc.isOnHold()).isFalse();
        // Attachment bytes are gone (message-schema.md); only the count and the filenames remain.
        assertThat(doc.getAttachmentCount()).isEqualTo(1);
        assertThat(doc.getAttachmentFilenames()).containsExactly("report.pdf");
        assertThat(doc.getLabels()).containsExactly("PRIVILEGED");
    }

    @Test
    void toSearchResultCarriesFieldsAndASnippetAroundTheQuery() {
        CommunicationDocument doc = new CommunicationDocument(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1", "from@firm.test",
                List.of("to@firm.test"), List.of(), "subject", "the body talks about fraud here",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, 1,
                List.of("report.pdf"), List.of("PRIVILEGED"), false);

        SearchResult result = mapper.toSearchResult(doc, 2.5f, "fraud");

        assertThat(result.messageId()).isEqualTo("msg-1");
        assertThat(result.custodianId()).isEqualTo("custodian-1");
        assertThat(result.from()).isEqualTo("from@firm.test");
        assertThat(result.to()).containsExactly("to@firm.test");
        assertThat(result.subject()).isEqualTo("subject");
        assertThat(result.sentAt()).isEqualTo(Instant.parse("2024-05-11T21:37:00Z"));
        assertThat(result.score()).isEqualTo(2.5f);
        assertThat(result.onHold()).isFalse();
        assertThat(result.attachmentCount()).isEqualTo(1);
        assertThat(result.attachmentFilenames()).containsExactly("report.pdf");
        // The window is centred on the match and marked at its truncated left edge.
        assertThat(result.snippet()).contains("fraud");
        assertThat(result.snippet()).startsWith("...");
    }

    @Test
    void toSearchResultPrefersTheElasticsearchBodyHighlightOverTheComputedSnippet() {
        // Req 3: when the server returns a highlighted body fragment (matched terms wrapped in
        // <em>), that fragment is used as the snippet so a UI shows one consistent highlighted
        // preview. The computed snippet is the fallback when there is no highlight.
        CommunicationDocument doc = new CommunicationDocument(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1", "from@firm.test",
                List.of("to@firm.test"), List.of(), "subject", "the body talks about fraud here",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, 0, List.of(), List.of(), false);

        // A LinkedHashMap keeps the body-first iteration order deterministic for the highlights
        // assertion — Map.of does not guarantee order.
        Map<String, List<String>> highlightFields = new java.util.LinkedHashMap<>();
        highlightFields.put("body", List.of("the body talks about <em>fraud</em> here"));
        highlightFields.put("subject", List.of("<em>fraud</em>"));

        SearchResult result = mapper.toSearchResult(doc, 1.0f, "fraud", highlightFields);

        assertThat(result.snippet()).isEqualTo("the body talks about <em>fraud</em> here");
        assertThat(result.highlights())
                .containsExactly("the body talks about <em>fraud</em> here", "<em>fraud</em>");
    }

    @Test
    void toSearchResultWithNoBodyHighlightFallsBackToTheComputedSnippet() {
        CommunicationDocument doc = new CommunicationDocument(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1", "from@firm.test",
                List.of("to@firm.test"), List.of(), "subject", "the body talks about fraud here",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, 0, List.of(), List.of(), false);

        SearchResult result = mapper.toSearchResult(doc, 1.0f, "fraud",
                Map.of("subject", List.of("<em>fraud</em>")));

        // No body highlight -> the computed snippet (still contains "fraud"), plus the subject
        // highlight carried in the highlights list.
        assertThat(result.snippet()).contains("fraud");
        assertThat(result.highlights()).containsExactly("<em>fraud</em>");
    }

    @Test
    void snippetCentresOnFirstMatchAndMarksTruncatedEdges() {
        // 30 chars; "fraud" at index 21; window of 20 -> substring(10,30) with a leading ellipsis
        // (the left edge is cut) and no trailing one (the body ends exactly at the window's right edge).
        String snippet = CommunicationDocumentMapper.snippet(
                "the body talks about fraud here", "fraud", 20);
        assertThat(snippet).isEqualTo("...lks about fraud here");
    }

    @Test
    void snippetDoesNotPrefixEllipsisWhenTheMatchIsAtTheStart() {
        // Match at 0, start clamped to 0 -> no leading ellipsis. The whole 18-char body fits in the
        // 20-char window, so there is no trailing ellipsis either.
        String snippet = CommunicationDocumentMapper.snippet("fraud is the topic", "fraud", 20);
        assertThat(snippet).isEqualTo("fraud is the topic");
    }

    @Test
    void snippetFallsBackToHeadWhenThereIsNoMatch() {
        String body = "a message with no relevant word at all here";
        assertThat(CommunicationDocumentMapper.snippet(body, "fraud", 20))
                .isEqualTo(body.substring(0, 20) + "...");
    }

    @Test
    void snippetReturnsWholeBodyWhenItFits() {
        String body = "short body";
        assertThat(CommunicationDocumentMapper.snippet(body, "short", 20)).isEqualTo("short body");
    }

    @Test
    void snippetIsCaseInsensitive() {
        assertThat(CommunicationDocumentMapper.snippet("We discuss FRAUD now", "fraud", 20))
                .contains("FRAUD");
    }

    @Test
    void toSearchResultWithEmptyHighlightMapUsesComputedSnippet() {
        CommunicationDocument doc = new CommunicationDocument(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1", "from@firm.test",
                List.of("to@firm.test"), List.of(), "subject", "the fraud body text here",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, 0, List.of(), List.of(), false);

        SearchResult result = mapper.toSearchResult(doc, 1.0f, "fraud", Map.of());

        // Empty highlight map -> computed snippet, empty highlights list.
        assertThat(result.snippet()).contains("fraud");
        assertThat(result.highlights()).isEmpty();
    }

    @Test
    void toSearchResultWithNullHighlightMapUsesComputedSnippet() {
        CommunicationDocument doc = new CommunicationDocument(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1", "from@firm.test",
                List.of("to@firm.test"), List.of(), "subject", "the fraud body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, 0, List.of(), List.of(), false);

        SearchResult result = mapper.toSearchResult(doc, 1.0f, "fraud", null);

        assertThat(result.snippet()).contains("fraud");
        assertThat(result.highlights()).isEmpty();
    }

    @Test
    void toSearchResultWithMultipleBodyHighlightsUsesTheFirst() {
        CommunicationDocument doc = new CommunicationDocument(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1", "from@firm.test",
                List.of("to@firm.test"), List.of(), "subject", "body with fraud",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, 0, List.of(), List.of(), false);

        SearchResult result = mapper.toSearchResult(doc, 1.0f, "fraud",
                Map.of("body", List.of("first <em>fraud</em> hit", "second <em>fraud</em> hit")));

        // The first body highlight becomes the snippet.
        assertThat(result.snippet()).isEqualTo("first <em>fraud</em> hit");
        // Both highlights are in the list.
        assertThat(result.highlights()).hasSize(2);
    }

    @Test
    void fromMessageWithNoAttachmentsMapsAttachmentCountToZero() {
        Message noAttachments = new Message(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@firm.test", List.of("to@firm.test"), List.of(), "subject", "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(), List.of());

        CommunicationDocument doc = mapper.fromMessage(noAttachments);

        assertThat(doc.getAttachmentCount()).isZero();
        assertThat(doc.getAttachmentFilenames()).isEmpty();
    }

    @Test
    void fromMessageWithMultipleAttachmentsMapsAllFilenames() {
        Message withAttachments = new Message(
                "msg-1", "ext-1", "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@firm.test", List.of("to@firm.test"), List.of(), "subject", "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null,
                List.of(
                        new Attachment("att-1", "report.pdf", "application/pdf", 1024, "sha1", null),
                        new Attachment("att-2", "data.xlsx", "application/vnd.ms-excel", 2048, "sha2", null),
                        new Attachment("att-3", "photo.jpg", "image/jpeg", 512, "sha3", null)),
                List.of());

        CommunicationDocument doc = mapper.fromMessage(withAttachments);

        assertThat(doc.getAttachmentCount()).isEqualTo(3);
        assertThat(doc.getAttachmentFilenames()).containsExactly("report.pdf", "data.xlsx", "photo.jpg");
    }

    @Test
    void fromMessageWithChatTypeHasNullSubjectInDocument() {
        Message chat = new Message(
                "msg-1", "ext-1", "TEAMS", MessageType.CHAT, "custodian-1",
                "from@firm.test", List.of("to@firm.test"), List.of(), null, "chat body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null, List.of(), List.of());

        CommunicationDocument doc = mapper.fromMessage(chat);

        // CHAT messages have no subject (message-schema.md) — the field is null, not "".
        assertThat(doc.getSubject()).isNull();
        assertThat(doc.getType()).isEqualTo(MessageType.CHAT);
    }

    @Test
    void snippetHandlesNullAndBlank() {
        assertThat(CommunicationDocumentMapper.snippet(null, "fraud", 20)).isEmpty();
        assertThat(CommunicationDocumentMapper.snippet("", "fraud", 20)).isEmpty();
        // A blank query falls back to the head of the body.
        assertThat(CommunicationDocumentMapper.snippet("a body that is long enough", "", 10))
                .isEqualTo("a body tha" + "...");
        // A non-positive size is meaningless; return nothing rather than an unbounded slice.
        assertThat(CommunicationDocumentMapper.snippet("a body", "body", 0)).isEmpty();
    }
}
