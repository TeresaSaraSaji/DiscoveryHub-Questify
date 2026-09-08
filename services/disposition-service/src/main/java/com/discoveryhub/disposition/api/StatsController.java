package com.discoveryhub.disposition.api;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.archive.ArchiveGateway;
import com.discoveryhub.disposition.config.DispositionProperties;
import com.discoveryhub.disposition.domain.DispositionOutcome;
import com.discoveryhub.disposition.domain.DispositionRunEntity;
import com.discoveryhub.disposition.repository.DispositionItemRepository;
import com.discoveryhub.disposition.repository.DispositionRunRepository;
import com.discoveryhub.disposition.run.RetentionPolicyService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the sweep would do right now, and what it has done. Feeds the retention panel on the
 * frontend dashboard (FR-8.2).
 */
@RestController
@RequestMapping("/disposition/stats")
public class StatsController {

    private final ArchiveGateway archive;
    private final RetentionPolicyService retention;
    private final DispositionRunRepository runs;
    private final DispositionItemRepository items;
    private final DispositionProperties props;

    public StatsController(ArchiveGateway archive, RetentionPolicyService retention,
                           DispositionRunRepository runs, DispositionItemRepository items,
                           DispositionProperties props) {
        this.archive = archive;
        this.retention = retention;
        this.runs = runs;
        this.items = items;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("archivedMessages", archive.countMessages());
        body.put("deleteMode", props.deleteMode());
        body.put("holdCheckEnabled", props.holdCheck().enabled());
        body.put("holdCheckRequired", props.holdCheck().required());

        Map<String, String> periods = new LinkedHashMap<>();
        for (Map.Entry<MessageType, Duration> entry : retention.currentPeriods().entrySet()) {
            periods.put(entry.getKey().name(), entry.getValue().toString());
        }
        body.put("retentionPeriods", periods);

        body.put("totalRuns", runs.count());
        body.put("totalDeleted", items.countByOutcome(DispositionOutcome.DELETED)
                + items.countByOutcome(DispositionOutcome.DELETE_REQUESTED));
        body.put("totalSkippedByHold", items.countByOutcome(DispositionOutcome.SKIPPED_HOLD));

        List<DispositionRunEntity> latest =
                runs.findAllByOrderByStartedAtDesc(PageRequest.of(0, 1)).getContent();
        body.put("lastRun", latest.isEmpty() ? null : latest.get(0));
        return body;
    }

    /**
     * How many messages the next sweep would consider, without running it.
     *
     * <p>Bounded by {@code batch-size}, exactly as a real run is, so the number answers "what will
     * the next sweep touch?" rather than the less useful "how much is overdue in total?".
     */
    @GetMapping("/candidates")
    public Map<String, Object> candidates() {
        Map<MessageType, Instant> cutoffs = retention.cutoffs(Instant.now());
        int count = archive.findCandidates(cutoffs, props.batchSize()).size();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchSize", props.batchSize());
        body.put("candidatesInNextSweep", count);
        Map<String, String> asStrings = new LinkedHashMap<>();
        cutoffs.forEach((type, cutoff) -> asStrings.put(type.name(), cutoff.toString()));
        body.put("cutoffs", asStrings);
        return body;
    }
}
