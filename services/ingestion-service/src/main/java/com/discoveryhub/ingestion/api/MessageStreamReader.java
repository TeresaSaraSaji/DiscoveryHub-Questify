package com.discoveryhub.ingestion.api;

import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Streams an uploaded file into fixed-size chunks of raw JSON elements.
 *
 * <p>Streamed rather than read whole, because the corpus fixture alone is 10.7 MB and an upload
 * of it would otherwise sit on the heap twice over — once as bytes, once as a parsed tree — before
 * a single message reached the broker.
 *
 * <p>Accepts both shapes people actually have. A JSON array is what {@code POST /messages} takes
 * and what a frontend will assemble; NDJSON, one object per line, is what the corpus generator
 * writes and what any export tool produces. Rejecting one of them would mean telling users to
 * reformat a file we can perfectly well read, so the format is detected from the first token
 * rather than declared.
 */
@Component
public class MessageStreamReader {

    private final ObjectMapper mapper;

    public MessageStreamReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Raised when a file carries more messages than one upload is allowed to. */
    public static class TooManyMessagesException extends RuntimeException {
        public TooManyMessagesException(String message) {
            super(message);
        }
    }

    /**
     * @return the number of elements read
     * @throws TooManyMessagesException if the file exceeds {@code maxMessages}
     */
    public int readInChunks(InputStream in, int chunkSize, int maxMessages,
                            Consumer<List<JsonNode>> onChunk) throws IOException {
        int total = 0;
        List<JsonNode> chunk = new ArrayList<>(chunkSize);

        try (JsonParser parser = mapper.createParser(in)) {
            JsonToken token = parser.nextToken();
            boolean wrappedInArray = token == JsonToken.START_ARRAY;
            if (wrappedInArray) {
                token = parser.nextToken();
            }

            while (token != null && token != JsonToken.END_ARRAY) {
                chunk.add(parser.readValueAsTree());
                total++;
                if (total > maxMessages) {
                    throw new TooManyMessagesException(
                            "file contains more than " + maxMessages + " messages; split it");
                }
                if (chunk.size() == chunkSize) {
                    onChunk.accept(List.copyOf(chunk));
                    chunk.clear();
                }
                token = parser.nextToken();
            }
        }

        if (!chunk.isEmpty()) {
            onChunk.accept(List.copyOf(chunk));
        }
        return total;
    }
}
