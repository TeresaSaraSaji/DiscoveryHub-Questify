package com.discoveryhub.tools.corpus;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Custodian;
import com.discoveryhub.contracts.Ids;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the background corpus: realistic but uninteresting traffic that the planted narrative
 * has to be found inside of. Everything is driven by a single seeded {@link Random} consumed in
 * a fixed order, so the same options always produce a byte-identical corpus.
 */
final class CorpusBuilder {

    /** Anything older than this is past the seven-year retention default and disposable (FR-5). */
    private static final LocalDate AGED_WINDOW_START = LocalDate.of(2017, 1, 1);
    private static final LocalDate AGED_WINDOW_END = LocalDate.of(2019, 6, 30);
    private static final LocalDate RECENT_WINDOW_START = LocalDate.of(2022, 1, 1);
    private static final LocalDate RECENT_WINDOW_END = LocalDate.of(2026, 8, 31);

    private static final double AGED_SHARE = 0.18;
    /** Share of the corpus that should be chat rather than email, measured in messages. */
    private static final double CHAT_TARGET = 0.40;
    private static final double ATTACHMENT_SCALE = 0.65;
    private static final double SECOND_MAILBOX_COPY = 0.12;
    private static final Pattern PLACEHOLDER = Pattern.compile("%[ds]");

    private final Options options;
    private final Roster roster;
    private final Random rng;
    private final List<Message> messages = new ArrayList<>();
    private int emailSequence = 0;
    private int chatSequence = 0;
    private int threadSequence = 0;
    private int backgroundTarget = 0;
    private int chatMessages = 0;
    private int emailMessages = 0;

    CorpusBuilder(Options options, Roster roster) {
        this.options = options;
        this.roster = roster;
        this.rng = new Random(options.seed());
    }

    Corpus build() {
        List<Message> narrative = Narrative.build(roster);
        backgroundTarget = Math.max(0, options.count() - narrative.size());
        // The narrative is email throughout, so it counts against the split like anything else.
        emailMessages = narrative.size();

        while (messages.size() < backgroundTarget) {
            buildThread(backgroundTarget - messages.size());
        }

        List<Message> unique = new ArrayList<>(messages.subList(0, backgroundTarget));
        unique.addAll(narrative);
        unique.sort(Comparator.comparing(Message::sentAt).thenComparing(Message::externalId));

        return new Corpus(roster.all(), unique, duplicates(unique));
    }

    /**
     * Re-emitted messages carrying an {@code externalId} that already appeared earlier in the
     * file. Loading the corpus straight through must therefore exercise dedupe (FR-1.6) — this
     * is the fixture the idempotency test asserts against.
     */
    private List<Message> duplicates(List<Message> unique) {
        List<Message> repeats = new ArrayList<>();
        Set<Integer> picked = new LinkedHashSet<>();
        while (picked.size() < options.duplicates() && picked.size() < unique.size()) {
            picked.add(rng.nextInt(unique.size()));
        }
        picked.forEach(i -> repeats.add(unique.get(i)));
        return repeats;
    }

    private void count(boolean chat) {
        if (chat) {
            chatMessages++;
        } else {
            emailMessages++;
        }
    }

    private void buildThread(int remaining) {
        Vocabulary.Topic topic = Vocabulary.TOPICS.get(rng.nextInt(Vocabulary.TOPICS.size()));
        List<Custodian> pool = roster.inDepartment(topic.department());
        if (pool.isEmpty()) {
            pool = roster.all();
        }

        Custodian initiator = pool.get(rng.nextInt(pool.size()));
        List<Custodian> recipients = pick(initiator, 1 + rng.nextInt(3));
        List<Custodian> copied = rng.nextDouble() < 0.35 ? pick(initiator, 1 + rng.nextInt(2)) : List.of();

        // Steered rather than sampled. Thread lengths differ between the two channels, so a fixed
        // per-thread probability lands nowhere near a target expressed in messages. This picks
        // whichever channel is currently behind, which converges on CHAT_TARGET to within one
        // thread and is still fully deterministic.
        boolean chat = chatMessages * (1 - CHAT_TARGET) < emailMessages * CHAT_TARGET;
        if (chat) {
            recipients = List.of(recipients.get(0));
            copied = List.of();
        }

        String threadKey = "bg-thread-" + (++threadSequence);
        String threadId = Ids.threadId(threadKey);
        String subject = chat ? null : fillSubject(topic.subject());
        Instant when = threadStart();

        int length = Math.min(remaining, chat ? 1 + rng.nextInt(9) : 1 + rng.nextInt(6));
        String previousId = null;
        String previousBody = null;
        List<Custodian> participants = new ArrayList<>();
        participants.add(initiator);
        participants.addAll(recipients);

        for (int i = 0; i < length && messages.size() < backgroundTarget; i++) {
            Custodian sender = i == 0 ? initiator : participants.get(rng.nextInt(participants.size()));
            List<String> to = new ArrayList<>();
            for (Custodian c : participants) {
                if (!c.equals(sender)) {
                    to.add(c.email());
                }
            }
            if (to.isEmpty()) {
                to.add(recipients.get(0).email());
            }
            if (!chat && topic.externalLikely() && i == 0 && rng.nextDouble() < 0.4) {
                to.add(Vocabulary.EXTERNAL_CONTACTS[rng.nextInt(Vocabulary.EXTERNAL_CONTACTS.length)]);
            }

            String externalId = chat
                    ? String.format(Locale.ROOT, "TEAMS-%06d", ++chatSequence)
                    : String.format(Locale.ROOT, "EXCH-%06d", ++emailSequence);

            String body = chat
                    ? Vocabulary.CHAT_LINES[rng.nextInt(Vocabulary.CHAT_LINES.length)]
                    : composeBody(sender, participants, topic, i > 0, previousBody);

            List<Attachment> attachments = List.of();
            if (!chat && topic.attachments().length > 0
                    && rng.nextDouble() < topic.attachmentBias() * ATTACHMENT_SCALE) {
                String filename = topic.attachments()[rng.nextInt(topic.attachments().length)];
                attachments = List.of(Attachments.build(externalId, 0, filename, rng));
            }

            Message m = new Message(
                    Ids.messageId(externalId), externalId, chat ? "TEAMS" : "EXCHANGE",
                    chat ? MessageType.CHAT : MessageType.EMAIL,
                    sender.custodianId(), sender.email(), to,
                    copied.stream().map(Custodian::email).filter(e -> !to.contains(e)).toList(),
                    subject == null ? null : (i == 0 ? subject : "Re: " + subject),
                    body, when, threadId, previousId, attachments, List.of());
            messages.add(m);
            count(chat);

            // The same conversation captured from a recipient's mailbox as well. Distinct
            // externalId, distinct messageId, identical content.
            if (!chat && rng.nextDouble() < SECOND_MAILBOX_COPY
                    && messages.size() < backgroundTarget) {
                Custodian other = participants.get(rng.nextInt(participants.size()));
                if (!other.equals(sender)) {
                    String copyId = String.format(Locale.ROOT, "EXCH-%06d", ++emailSequence);
                    messages.add(new Message(
                            Ids.messageId(copyId), copyId, "EXCHANGE", m.type(),
                            other.custodianId(), m.from(), m.to(), m.cc(), m.subject(), m.body(),
                            m.sentAt(), m.threadId(), m.inReplyTo(), m.attachments(), m.labels()));
                    count(false);
                }
            }

            previousId = m.messageId();
            previousBody = body;
            when = when.plus(Duration.ofMinutes(chat ? 1 + rng.nextInt(20) : 12 + rng.nextInt(2600)));
        }
    }

    private List<Custodian> pick(Custodian exclude, int howMany) {
        Set<Custodian> chosen = new LinkedHashSet<>();
        int guard = 0;
        while (chosen.size() < howMany && guard++ < 50) {
            Custodian c = roster.get(rng.nextInt(roster.size()));
            if (!c.equals(exclude)) {
                chosen.add(c);
            }
        }
        return List.copyOf(chosen);
    }

    /** Weekday, business hours, with a small tail of evening and weekend traffic. */
    private Instant threadStart() {
        boolean aged = rng.nextDouble() < AGED_SHARE;
        LocalDate from = aged ? AGED_WINDOW_START : RECENT_WINDOW_START;
        LocalDate to = aged ? AGED_WINDOW_END : RECENT_WINDOW_END;
        long span = to.toEpochDay() - from.toEpochDay();
        LocalDate date = LocalDate.ofEpochDay(from.toEpochDay() + (long) (rng.nextDouble() * span));

        boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        if (weekend && rng.nextDouble() < 0.85) {
            date = date.plusDays(2);
        }

        int hour = rng.nextDouble() < 0.88 ? 8 + rng.nextInt(10) : rng.nextInt(24);
        return LocalDateTime.of(date, java.time.LocalTime.of(hour, rng.nextInt(60), rng.nextInt(60)))
                .toInstant(ZoneOffset.UTC);
    }

    private String fillSubject(String template) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group().equals("%d")
                    ? String.valueOf(1 + rng.nextInt(9))
                    : Vocabulary.SUBJECT_FILLERS[rng.nextInt(Vocabulary.SUBJECT_FILLERS.length)];
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String composeBody(Custodian sender, List<Custodian> participants,
                               Vocabulary.Topic topic, boolean reply, String previousBody) {
        StringBuilder sb = new StringBuilder();
        Custodian addressee = participants.get(rng.nextInt(participants.size()));

        if (reply) {
            String opener = Vocabulary.REPLY_OPENERS[rng.nextInt(Vocabulary.REPLY_OPENERS.length)];
            sb.append(opener.contains("%s")
                    ? String.format(Locale.ROOT, opener, firstName(addressee)) : opener);
            sb.append("\n\n");
        } else {
            String greeting = Vocabulary.GREETINGS[rng.nextInt(Vocabulary.GREETINGS.length)];
            sb.append(greeting.contains("%s")
                    ? String.format(Locale.ROOT, greeting, firstName(addressee)) : greeting);
            sb.append("\n\n");
        }

        // Sample without replacement — a body that repeats the same sentence reads as generated
        // and pollutes relevance scoring in the search demo.
        List<String> pool = new ArrayList<>(Arrays.asList(topic.sentences()));
        int sentences = Math.min(pool.size(), 2 + rng.nextInt(3));
        for (int i = 0; i < sentences; i++) {
            sb.append(pool.remove(rng.nextInt(pool.size()))).append('\n');
        }

        sb.append('\n')
                .append(Vocabulary.CLOSINGS[rng.nextInt(Vocabulary.CLOSINGS.length)])
                .append('\n')
                .append(firstName(sender));

        if (reply && previousBody != null && rng.nextDouble() < 0.3) {
            sb.append("\n\n> ").append(previousBody.replace("\n", "\n> "));
        }
        return sb.toString();
    }

    private static String firstName(Custodian c) {
        return c.displayName().split(" ")[0];
    }
}
