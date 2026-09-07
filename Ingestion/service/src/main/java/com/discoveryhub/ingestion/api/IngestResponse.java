package com.discoveryhub.ingestion.api;

import java.util.List;

public record IngestResponse(
        int accepted,
        int duplicates,
        int rejected,
        int failed,
        List<IngestResult> results) {

    public static IngestResponse of(List<IngestResult> results) {
        int accepted = count(results, IngestResult.Outcome.ACCEPTED);
        int duplicates = count(results, IngestResult.Outcome.DUPLICATE);
        int rejected = count(results, IngestResult.Outcome.REJECTED);
        int failed = count(results, IngestResult.Outcome.FAILED);
        return new IngestResponse(accepted, duplicates, rejected, failed, List.copyOf(results));
    }

    private static int count(List<IngestResult> results, IngestResult.Outcome outcome) {
        return (int) results.stream().filter(r -> r.outcome() == outcome).count();
    }
}
