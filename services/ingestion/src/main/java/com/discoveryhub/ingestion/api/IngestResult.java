package com.discoveryhub.ingestion.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestResult(
        String externalId,
        String messageId,
        Outcome outcome,
        String reason) {

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        REJECTED,
        FAILED
    }

    public static IngestResult accepted(String externalId, String messageId) {
        return new IngestResult(externalId, messageId, Outcome.ACCEPTED, null);
    }

    public static IngestResult duplicate(String externalId, String messageId) {
        return new IngestResult(externalId, messageId, Outcome.DUPLICATE, null);
    }

    public static IngestResult rejected(String externalId, String reason) {
        return new IngestResult(externalId, null, Outcome.REJECTED, reason);
    }

    public static IngestResult failed(String externalId, String messageId, String reason) {
        return new IngestResult(externalId, messageId, Outcome.FAILED, reason);
    }
}
