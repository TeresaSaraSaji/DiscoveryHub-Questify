package com.discoveryhub.tools.corpus;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Entry point. Generates the corpus and either writes it as NDJSON or pushes it at P1 Ingestion.
 *
 * <p>The output is a pure function of the options, so the committed fixture can be regenerated
 * and diffed. If a change to this tool moves a single byte of {@code messages.ndjson}, that shows
 * up in review rather than silently invalidating everyone's test expectations.
 */
public final class CorpusGenerator {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Duration RETENTION_DEFAULT = Duration.ofDays(365 * 7);

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.println(Options.USAGE);
            System.exit(2);
            return;
        }

        long started = System.currentTimeMillis();
        Corpus corpus = new CorpusBuilder(options, new Roster(options.custodians())).build();
        System.out.printf(Locale.ROOT, "generated %,d messages in %,d ms%n",
                corpus.wire().size(), System.currentTimeMillis() - started);
        printStats(corpus);

        if (options.statsOnly()) {
            return;
        }
        if (options.postUrl() != null) {
            new Ingestor(options.postUrl(), options.batchSize()).send(corpus.wire());
        } else {
            write(options, corpus);
        }
    }

    private static void write(Options options, Corpus corpus) throws IOException {
        Files.createDirectories(options.outDir());
        Path messages = options.outDir().resolve("messages.ndjson");
        Path custodians = options.outDir().resolve("custodians.json");
        Path manifest = options.outDir().resolve("manifest.json");

        try (BufferedWriter out = Files.newBufferedWriter(messages, StandardCharsets.UTF_8)) {
            for (Message m : corpus.wire()) {
                out.write(MAPPER.writeValueAsString(m));
                out.write('\n');
            }
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(custodians.toFile(), corpus.custodians());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("seed", options.seed());
        summary.put("uniqueMessages", corpus.unique().size());
        summary.put("duplicateMessages", corpus.duplicates().size());
        summary.put("custodians", corpus.custodians().size());
        summary.put("messagesSha256", Attachments.sha256(Files.readAllBytes(messages)));
        summary.putAll(stats(corpus));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), summary);

        System.out.printf(Locale.ROOT, "wrote %s (%,d bytes)%n", messages, Files.size(messages));
        System.out.printf(Locale.ROOT, "wrote %s%n", custodians);
        System.out.printf(Locale.ROOT, "wrote %s%n", manifest);
    }

    private static void printStats(Corpus corpus) {
        stats(corpus).forEach((k, v) -> System.out.printf(Locale.ROOT, "  %-24s %s%n", k, v));
    }

    private static Map<String, Object> stats(Corpus corpus) {
        List<Message> all = corpus.unique();
        long emails = all.stream().filter(m -> m.type() == MessageType.EMAIL).count();
        long withAttachments = all.stream().filter(m -> !m.attachments().isEmpty()).count();
        long attachmentBytes = all.stream().flatMap(m -> m.attachments().stream())
                .mapToLong(a -> a.sizeBytes()).sum();
        Instant disposableBefore = Instant.now().minus(RETENTION_DEFAULT);
        long aged = all.stream().filter(m -> m.sentAt().isBefore(disposableBefore)).count();
        long privileged = all.stream().filter(m -> m.labels().contains("PRIVILEGED")).count();
        long halyard = all.stream()
                .filter(m -> m.body().contains(Narrative.CODENAME)
                        || (m.subject() != null && m.subject().contains(Narrative.CODENAME)))
                .count();

        Map<String, Long> perCustodian = new TreeMap<>();
        all.forEach(m -> perCustodian.merge(m.custodianId(), 1L, Long::sum));

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("emails", emails);
        s.put("chats", all.size() - emails);
        s.put("withAttachments", withAttachments);
        s.put("attachmentRatePct", round(100.0 * withAttachments / all.size()));
        s.put("attachmentBytes", attachmentBytes);
        s.put("distinctThreads", all.stream().map(Message::threadId).distinct().count());
        s.put("earliestSentAt", all.get(0).sentAt().toString());
        s.put("latestSentAt", all.get(all.size() - 1).sentAt().toString());
        s.put("pastRetentionDefault", aged);
        s.put("privilegedMessages", privileged);
        s.put("halyardMentions", halyard);
        s.put("minMessagesPerCustodian", perCustodian.values().stream().mapToLong(v -> v).min().orElse(0));
        s.put("maxMessagesPerCustodian", perCustodian.values().stream().mapToLong(v -> v).max().orElse(0));
        return s;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private CorpusGenerator() {
    }
}
