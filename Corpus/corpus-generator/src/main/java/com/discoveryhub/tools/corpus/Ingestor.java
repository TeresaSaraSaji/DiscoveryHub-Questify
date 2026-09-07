package com.discoveryhub.tools.corpus;

import com.discoveryhub.contracts.Message;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Pushes the corpus at P1 Ingestion in batches. Used to load a running stack for the demo and to
 * drive the throughput check in NFR-4.
 *
 * <p>The corpus contains deliberate duplicate {@code externalId}s, so a healthy run reports fewer
 * stored messages than sent. That gap is the dedupe guarantee working (FR-1.6), not a failure.
 */
final class Ingestor {

    private final URI endpoint;
    private final int batchSize;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    Ingestor(String endpoint, int batchSize) {
        this.endpoint = URI.create(endpoint);
        this.batchSize = Math.max(1, batchSize);
    }

    void send(List<Message> messages) throws Exception {
        long started = System.currentTimeMillis();
        int sent = 0;
        int failed = 0;

        for (int from = 0; from < messages.size(); from += batchSize) {
            List<Message> batch = messages.subList(from, Math.min(from + batchSize, messages.size()));
            String payload = CorpusGenerator.MAPPER.writeValueAsString(batch);

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                sent += batch.size();
            } else {
                failed += batch.size();
                System.err.printf(Locale.ROOT, "batch at offset %d failed: HTTP %d %s%n",
                        from, response.statusCode(), truncate(response.body()));
            }
            if ((from / batchSize) % 10 == 0) {
                System.out.printf(Locale.ROOT, "  %,d / %,d%n", sent + failed, messages.size());
            }
        }

        long elapsed = Math.max(1, System.currentTimeMillis() - started);
        System.out.printf(Locale.ROOT, "posted %,d messages (%,d failed) in %,d ms — %,.0f msg/s%n",
                sent, failed, elapsed, sent * 1000.0 / elapsed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String truncate(String body) {
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
