package com.discoveryhub.disposition.run;

import com.discoveryhub.disposition.domain.DispositionOutcome;
import com.discoveryhub.disposition.domain.DispositionRunEntity;
import com.discoveryhub.disposition.domain.DispositionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live progress for the sweep, held in memory and pushed to whoever is watching.
 *
 * <p>A sweep makes one HTTP call to P4 per candidate and can consider {@code batch-size} of them,
 * so it is not a request-sized unit of work. Run synchronously behind {@code POST
 * /disposition/runs} it holds the connection open for as long as it takes and the browser gives up
 * first — NFR-3 asks specifically that bulk work over the corpus not time out the UI. So a run can
 * be started asynchronously, and this is how the caller finds out what it is doing.
 *
 * <p><b>In memory, and only the current run.</b> Progress is worth nothing once a run has
 * finished — the ledger and the run row are the durable answers, and they are better ones.
 * Persisting a progress counter would add write traffic per message to the table the chain of
 * custody depends on, to store something no one will read tomorrow. A restart loses the live view
 * of a sweep that the restart also killed, which is the right thing to lose.
 *
 * <p><b>One run at a time</b>, matching the {@code AtomicBoolean} guard in
 * {@link DispositionService}: two concurrent sweeps would race on the same rows, so there is
 * exactly one slot here rather than a map that could imply otherwise.
 */
@Component
public class DispositionProgress {

    private static final Logger log = LoggerFactory.getLogger(DispositionProgress.class);

    private final AtomicReference<RunProgress> current = new AtomicReference<>();

    private final List<SseEmitter> watchers = new CopyOnWriteArrayList<>();

    /** The run in flight, or the last one to finish. Empty before the first sweep of a boot. */
    public Optional<RunProgress> current() {
        return Optional.ofNullable(current.get());
    }

    /** Accepted but not started: what an async trigger returns before the executor picks it up. */
    void queued(String runId, boolean dryRun) {
        publish(new RunProgress(runId, DispositionStatus.QUEUED, dryRun, 0, 0, 0, 0, 0,
                null, true, null, Instant.now()));
    }

    /** Candidates counted and the hold context established — the point the total becomes known. */
    void started(String runId, boolean dryRun, int total, int activeHolds, boolean holdScopeAvailable) {
        publish(new RunProgress(runId, DispositionStatus.RUNNING, dryRun, 0, total, 0, 0, 0,
                activeHolds, holdScopeAvailable, null, Instant.now()));
    }

    /** One more candidate decided. Called from the sweep loop, so it must stay cheap. */
    void advanced(DispositionOutcome outcome) {
        RunProgress last = current.get();
        if (last == null) {
            return;
        }
        int deleted = last.deleted() + (isDeleted(outcome) ? 1 : 0);
        int skipped = last.skippedHold() + (outcome == DispositionOutcome.SKIPPED_HOLD ? 1 : 0);
        int failed = last.failed() + (outcome == DispositionOutcome.FAILED ? 1 : 0);
        publish(new RunProgress(last.runId(), DispositionStatus.RUNNING, last.dryRun(),
                last.processed() + 1, last.total(), deleted, skipped, failed,
                last.activeHolds(), last.holdScopeAvailable(), null, Instant.now()));
    }

    /** Terminal. Watchers get this snapshot and then the stream is closed. */
    void finished(DispositionRunEntity run) {
        publish(new RunProgress(run.getRunId(), run.getStatus(), run.isDryRun(),
                run.getCandidateCount(), run.getCandidateCount(), run.getDeletedCount(),
                run.getSkippedHoldCount(), run.getFailedCount(), run.getActiveHoldCount(),
                run.isHoldScopeAvailable(), run.getError(), Instant.now()));
        closeWatchers();
    }

    /**
     * Watch the sweep. The latest snapshot is sent immediately, so a client that connects
     * mid-sweep sees state rather than an empty stream until the next candidate is decided, and
     * one that connects between runs sees how the last one ended.
     *
     * <p>The stream then stays open until a run finishes, <b>including when the snapshot it was
     * handed is already terminal</b>. Closing on a terminal snapshot looks tidier and is wrong:
     * the normal sequence in the UI is open the stream, then click Run, and a stream that
     * disconnects the instant it is opened means the client watches nothing and the run it was
     * opened for is invisible. Observed exactly that way in an end-to-end run — the stream
     * delivered the *previous* sweep's totals and hung up before the new one started.
     *
     * <p>So the contract is "you will see the end of the next run", which covers both connecting
     * during a sweep and connecting just before one. A client that attaches and never triggers
     * anything holds an idle connection, which is what SSE is for.
     */
    public SseEmitter watch() {
        // No timeout: a sweep over a full batch can outlast any default, and the emitter is
        // completed explicitly when a run ends.
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> watchers.remove(emitter));
        emitter.onTimeout(() -> watchers.remove(emitter));
        emitter.onError(ex -> watchers.remove(emitter));

        RunProgress snapshot = current.get();
        if (snapshot != null && !send(emitter, snapshot)) {
            return emitter;
        }
        watchers.add(emitter);
        return emitter;
    }

    private void publish(RunProgress progress) {
        current.set(progress);
        for (SseEmitter emitter : watchers) {
            send(emitter, progress);
        }
    }

    /** @return false if the client has gone, in which case the emitter has been dropped */
    private boolean send(SseEmitter emitter, RunProgress progress) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(progress));
            return true;
        } catch (IOException | IllegalStateException ex) {
            // A browser tab closing mid-sweep is normal and must not disturb the sweep, which is
            // what is calling this. Drop the watcher and carry on.
            log.debug("dropping progress watcher: {}", ex.toString());
            watchers.remove(emitter);
            return false;
        }
    }

    private void closeWatchers() {
        for (SseEmitter emitter : watchers) {
            try {
                emitter.complete();
            } catch (RuntimeException ex) {
                log.debug("failed to close progress watcher: {}", ex.toString());
            }
        }
        watchers.clear();
    }

    private static boolean isDeleted(DispositionOutcome outcome) {
        return outcome == DispositionOutcome.DELETED || outcome == DispositionOutcome.DELETE_REQUESTED;
    }
}
