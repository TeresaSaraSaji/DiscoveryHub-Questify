package com.discoveryhub.disposition.api;

import com.discoveryhub.disposition.domain.DispositionItemEntity;
import com.discoveryhub.disposition.domain.DispositionOutcome;
import com.discoveryhub.disposition.domain.DispositionRunEntity;
import com.discoveryhub.disposition.domain.TriggerSource;
import com.discoveryhub.disposition.repository.DispositionItemRepository;
import com.discoveryhub.disposition.repository.DispositionRunRepository;
import com.discoveryhub.disposition.run.DispositionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Disposition runs: trigger one, and read the ledger (FR-5.3).
 *
 * <p>The scheduled sweep is the normal path. The manual trigger exists because a demo cannot wait
 * for a cron, and {@code dryRun} exists because "show me what this would destroy" should not
 * require destroying it.
 */
@RestController
@RequestMapping("/disposition")
public class DispositionController {

    private final DispositionService disposition;
    private final DispositionRunRepository runs;
    private final DispositionItemRepository items;

    public DispositionController(DispositionService disposition, DispositionRunRepository runs,
                                 DispositionItemRepository items) {
        this.disposition = disposition;
        this.runs = runs;
        this.items = items;
    }

    /**
     * Fire a sweep now and return the finished run. Synchronous: a run is bounded by
     * {@code batch-size} and the caller wants the counts, not a job id.
     *
     * @param dryRun record every decision, delete nothing
     */
    @PostMapping("/runs")
    public ResponseEntity<DispositionRunEntity> trigger(
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestParam(name = "actor", defaultValue = "investigator") String actor) {
        try {
            DispositionRunEntity run = disposition.run(TriggerSource.MANUAL, dryRun, actor);
            return ResponseEntity.status(HttpStatus.CREATED).body(run);
        } catch (DispositionService.DispositionRunInProgressException ex) {
            // 409 rather than a queue: two sweeps would race on the same rows, and the caller
            // should know their run did not happen.
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @GetMapping("/runs")
    public Page<DispositionRunEntity> recentRuns(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return runs.findAllByOrderByStartedAtDesc(PageRequest.of(page, Math.min(size, 200)));
    }

    @GetMapping("/runs/{runId}")
    public DispositionRunEntity runDetail(@PathVariable("runId") String runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId));
    }

    /**
     * The per-item ledger for one run, optionally filtered by outcome.
     *
     * <p>Paginated, and separate from the run itself, because a sweep over the full corpus writes
     * one row per candidate — returning them inline would make the run summary unusable.
     * {@code ?outcome=SKIPPED_HOLD} is the FR-4.6 view: everything a legal hold saved.
     */
    @GetMapping("/runs/{runId}/items")
    public Page<DispositionItemEntity> runItems(
            @PathVariable("runId") String runId,
            @RequestParam(name = "outcome", required = false) DispositionOutcome outcome,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        if (runs.findById(runId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId);
        }
        PageRequest pageable = PageRequest.of(page, Math.min(size, 500));
        return outcome == null
                ? items.findByRunIdOrderByOccurredAtAsc(runId, pageable)
                : items.findByRunIdAndOutcomeOrderByOccurredAtAsc(runId, outcome, pageable);
    }

    /**
     * Every disposition decision ever recorded about one message.
     *
     * <p>Answers both halves of the awkward question: why a message is missing, and why one that
     * should have expired is still there. Survives the message itself, which is the point.
     */
    @GetMapping("/messages/{messageId}")
    public List<DispositionItemEntity> messageHistory(@PathVariable("messageId") String messageId) {
        return items.findByMessageIdOrderByOccurredAtAsc(messageId);
    }

    /**
     * Everything the holds on one case have protected from disposition, across every run.
     *
     * <p>The per-matter FR-4.6 evidence: "show that the hold on this case stopped these messages
     * from being destroyed". Outlives both the release of the hold and the closing of the case,
     * which is what makes it usable as proof rather than as a status display.
     */
    @GetMapping("/cases/{caseId}/protected")
    public Page<DispositionItemEntity> protectedByCase(
            @PathVariable("caseId") String caseId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return items.findByBlockingCaseIdOrderByOccurredAtDesc(
                caseId, PageRequest.of(page, Math.min(size, 500)));
    }
}
