package com.discoveryhub.tools.corpus;

import com.discoveryhub.contracts.Ids;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusBuilderTest {

    private static Options options(int count, long seed) {
        return new Options(Path.of("target/test-fixtures"), count, seed, 24, 25, null, 200, false);
    }

    private static Corpus generate(int count, long seed) {
        return new CorpusBuilder(options(count, seed), new Roster(24)).build();
    }

    @Test
    void sameSeedProducesIdenticalCorpus() throws Exception {
        String first = serialise(generate(1200, 7L));
        String second = serialise(generate(1200, 7L));
        assertEquals(first, second, "generator must be deterministic for a fixed seed");
    }

    @Test
    void differentSeedProducesDifferentCorpus() throws Exception {
        assertNotEquals(serialise(generate(1200, 7L)), serialise(generate(1200, 8L)));
    }

    @Test
    void producesRequestedUniqueCount() {
        assertEquals(1200, generate(1200, 7L).unique().size());
    }

    @Test
    void externalIdsAreUniqueAndDuplicatesReuseThem() {
        Corpus corpus = generate(1200, 7L);
        Set<String> ids = new HashSet<>();
        corpus.unique().forEach(m -> assertTrue(ids.add(m.externalId()),
                "duplicate externalId in unique set: " + m.externalId()));

        assertEquals(25, corpus.duplicates().size());
        corpus.duplicates().forEach(m -> assertTrue(ids.contains(m.externalId()),
                "duplicate must re-send an id already in the corpus"));
    }

    @Test
    void messageIdIsDerivedFromExternalId() {
        generate(400, 7L).unique()
                .forEach(m -> assertEquals(Ids.messageId(m.externalId()), m.messageId()));
    }

    @Test
    void meetsAttachmentFloor() {
        List<Message> all = generate(4000, 7L).unique();
        long withAttachments = all.stream().filter(m -> !m.attachments().isEmpty()).count();
        assertTrue(withAttachments >= all.size() * 0.07,
                "corpus must keep at least 7% of messages carrying attachments, got "
                        + withAttachments + " of " + all.size());
    }

    @Test
    void holdsTheSixtyFortyEmailChatSplit() {
        List<Message> all = generate(4000, 7L).unique();
        long emails = all.stream().filter(m -> m.type() == MessageType.EMAIL).count();
        double emailShare = (double) emails / all.size();
        assertTrue(emailShare > 0.58 && emailShare < 0.62,
                "email share should sit at 60%, got " + Math.round(emailShare * 100) + "%");
    }

    @Test
    void attachmentChecksumsMatchTheirBytes() {
        generate(2000, 7L).unique().stream()
                .flatMap(m -> m.attachments().stream())
                .forEach(a -> {
                    byte[] bytes = java.util.Base64.getDecoder().decode(a.contentBase64());
                    assertEquals(a.sha256(), Attachments.sha256(bytes));
                    assertEquals(a.sizeBytes(), bytes.length);
                });
    }

    @Test
    void everyCustodianHasTraffic() {
        Corpus corpus = generate(2000, 7L);
        Set<String> senders = new HashSet<>(corpus.unique().stream().map(Message::custodianId).toList());
        corpus.custodians().forEach(c -> assertTrue(senders.contains(c.custodianId()),
                "custodian with no messages: " + c.custodianId()));
    }

    @Test
    void messagesAreOrderedByTime() {
        List<Message> all = generate(1200, 7L).unique();
        for (int i = 1; i < all.size(); i++) {
            assertFalse(all.get(i).sentAt().isBefore(all.get(i - 1).sentAt()));
        }
    }

    @Test
    void narrativeIsPlantedAndSelfConsistent() {
        List<Message> all = generate(2000, 7L).unique();
        List<Message> halyard = all.stream()
                .filter(m -> m.body().contains(Narrative.CODENAME)
                        || (m.subject() != null && m.subject().contains(Narrative.CODENAME)))
                .toList();
        assertTrue(halyard.size() >= 8, "narrative should be findable by codename");

        assertTrue(all.stream().anyMatch(m -> m.labels().contains("PRIVILEGED")),
                "demo needs a privileged thread to filter on");
        assertTrue(all.stream().anyMatch(m -> m.attachments().stream()
                        .anyMatch(a -> a.filename().equals("bridgeline-indicative-figures.csv"))),
                "the competitor pricing attachment is the centrepiece of the export demo");

        // The recipient's captured copy of the smoking gun exists under a different custodian.
        List<Message> housekeeping = all.stream()
                .filter(m -> "Housekeeping".equals(m.subject())).toList();
        assertEquals(2, housekeeping.size(), "same message captured from two mailboxes");
        assertNotEquals(housekeeping.get(0).custodianId(), housekeeping.get(1).custodianId());
        assertEquals(housekeeping.get(0).body(), housekeeping.get(1).body());
    }

    private static String serialise(Corpus corpus) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Message m : corpus.wire()) {
            sb.append(CorpusGenerator.MAPPER.writeValueAsString(m)).append('\n');
        }
        return sb.toString();
    }
}
