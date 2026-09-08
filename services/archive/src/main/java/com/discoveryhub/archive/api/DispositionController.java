package com.discoveryhub.archive.api;

import com.discoveryhub.archive.domain.DispositionItemEntity;
import com.discoveryhub.archive.domain.DispositionRunEntity;
import com.discoveryhub.archive.repository.DispositionItemRepository;
import com.discoveryhub.archive.repository.DispositionRunRepository;
import com.discoveryhub.archive.retention.DispositionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Disposition run history and a manual trigger (FR-5.3). The scheduled job is the normal path; the
 * manual endpoint exists so the demo can fire a sweep on demand after setting retention to minutes
 * (FR-5.1), and so a test can exercise the fail-closed logic deterministically.
 */
@RestController
@RequestMapping("/disposition")
public class DispositionController {

    private final DispositionRunRepository runs;
    private final DispositionItemRepository items;
    private final DispositionService disposition;

    public DispositionController(DispositionRunRepository runs, DispositionItemRepository items,
                                 DispositionService disposition) {
        this.runs = runs;
        this.items = items;
        this.disposition = disposition;
    }

    @GetMapping("/runs")
    public List<DispositionRunEntity> recentRuns() {
        return runs.findTop20ByOrderByStartedAtDesc();
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<Map<String, Object>> runDetail(@PathVariable("runId") String runId) {
        DispositionRunEntity run = runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId));
        List<DispositionItemEntity> runItems = items.findByRunIdOrderByOccurredAtAsc(runId);
        Map<String, Object> body = new HashMap<>();
        body.put("run", run);
        body.put("items", runItems);
        return ResponseEntity.ok(body);
    }

    /** Fire a sweep now. Returns the completed (or failed) run record. */
    @PostMapping("/runs")
    public ResponseEntity<DispositionRunEntity> triggerRun() {
        DispositionRunEntity run = disposition.runOnce();
        return ResponseEntity.status(HttpStatus.CREATED).body(run);
    }
}
