package com.discoveryhub.disposition.run;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.archive.ArchiveGateway;
import com.discoveryhub.disposition.archive.MessageDeleter;
import com.discoveryhub.disposition.config.DispositionProperties;
import com.discoveryhub.disposition.domain.ArchiveCandidate;
import com.discoveryhub.disposition.domain.DispositionItemEntity;
import com.discoveryhub.disposition.domain.DispositionOutcome;
import com.discoveryhub.disposition.domain.DispositionRunEntity;
import com.discoveryhub.disposition.domain.DispositionStatus;
import com.discoveryhub.disposition.domain.TriggerSource;
import com.discoveryhub.disposition.hold.ActiveHold;
import com.discoveryhub.disposition.hold.CaseHoldClient;
import com.discoveryhub.disposition.hold.HoldCheckClient;
import com.discoveryhub.disposition.hold.HoldScopeSnapshot;
import com.discoveryhub.disposition.messaging.AuditEvents;
import com.discoveryhub.disposition.messaging.DispositionKafkaPublisher;
import com.discoveryhub.disposition.repository.DispositionItemRepository;
import com.discoveryhub.disposition.repository.DispositionRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The disposition sweep (FR-5.2, FR-5.3): find messages past their type's retention period, delete
 * the ones no hold covers, and record what happened to every one of them.
 *
 * <p><b>Holds win, always.</b> Each candidate passes four independent guards before anything is
 * deleted, and they are redundant on purpose:
 *
 * <ol>
 *   <li>the {@code on_hold} flag P2 mirrors from {@code holds.events} — free, and right most of
 *       the time;</li>
 *   <li>the <b>scope of every hold in force on a case</b>, taken once per run — this one does not
 *       depend on P4 having expanded the hold down to individual messages;</li>
 *   <li>a synchronous per-message call to P4;</li>
 *   <li>in {@code ARCHIVE_DB} mode, an {@code AND on_hold = false} predicate inside the DELETE.</li>
 * </ol>
 *
 * <p>Guard 2 exists because of FR-4.3. Hold propagation is required to be asynchronous, so between
 * an investigator placing a hold on a case and every message in scope being flagged there is a
 * window as long as P4's fan-out. Guards 1, 3 and 4 all read per-message state, so for the whole
 * of that window they say "not held" — and a sweep landing in it would destroy the evidence the
 * hold was placed to preserve. Evaluating the case's hold scope directly closes the window: the
 * hold on the case is enough, expanded or not.
 *
 * <p><b>Fail closed.</b> An unreachable P4 makes both the per-message verdict and the hold scope
 * unavailable, and while {@code hold-check.required} is true an unavailable answer is treated as
 * held. One unreachable check skips one message rather than aborting the sweep; a P4 that is down
 * for the whole run produces a completed run that deleted nothing, which is the correct and safe
 * outcome, not a failure. The run records that the scope was unavailable, so a run that deleted
 * nothing can be told apart from a run that had nothing to do.
 *
 * <p><b>Idempotent.</b> A message either is past retention or it is not, so re-running is a no-op
 * once the eligible set is gone. That is what makes a cron schedule and a manual trigger safe to
 * coexist.
 *
 * <p><b>Bounded.</b> A run processes at most {@code batch-size} candidates, oldest first. The full
 * corpus is 10,000+ messages (NFR-3) and an unbounded sweep would hold all of them in memory and
 * write the ledger in one transaction at the end. Bounded runs make progress monotonic and the
 * memory profile flat; the cron picks up the remainder.
 */
@Service
public class DispositionService {

    private static final Logger log = LoggerFactory.getLogger(DispositionService.class);

    /** Guards against a scheduled tick starting while a manual run is still going. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ArchiveGateway archive;
    private final MessageDeleter deleter;
    private final HoldCheckClient holdCheck;
    private final CaseHoldClient caseHolds;
    private final RetentionPolicyService retention;
    private final DispositionRunRepository runs;
    private final DispositionItemRepository items;
    private final DispositionProperties props;
    private final DispositionKafkaPublisher publisher;
    private final AuditEvents audit;

    public DispositionService(ArchiveGateway archive, MessageDeleter deleter, HoldCheckClient holdCheck,
                              CaseHoldClient caseHolds, RetentionPolicyService retention,
                              DispositionRunRepository runs, DispositionItemRepository items,
                              DispositionProperties props, DispositionKafkaPublisher publisher,
                              AuditEvents audit) {
        this.archive = archive;
        this.deleter = deleter;
        this.holdCheck = holdCheck;
        this.caseHolds = caseHolds;
        this.retention = retention;
        this.runs = runs;
        this.items = items;
        this.props = props;
        this.publisher = publisher;
        this.audit = audit;
    }

    /**
     * Run a sweep.
     *
     * <p>Not {@code @Transactional}. The run and its items are committed in stages so that a
     * failure halfway through leaves a ledger of what had already been deleted — with one
     * transaction around the whole sweep, a rollback would erase the record of deletions that
     * actually happened in P2's database, which no transaction of ours can undo. The ledger must
     * survive the failure that makes it interesting.
     *
     * @param dryRun evaluate and record every decision, delete nothing
     * @return the completed or failed run record
     */
    public DispositionRunEntity run(TriggerSource source, boolean dryRun, String actor) {
        if (!running.compareAndSet(false, true)) {
            throw new DispositionRunInProgressException();
        }
        try {
            return sweep(source, dryRun, actor);
        } finally {
            running.set(false);
        }
    }

    private DispositionRunEntity sweep(TriggerSource source, boolean dryRun, String actor) {
        String runId = UUID.randomUUID().toString();
        DispositionRunEntity run = runs.save(
                new DispositionRunEntity(runId, Instant.now(), source, dryRun));

        int deleted = 0;
        int skippedHold = 0;
        int failed = 0;

        try {
            Instant now = Instant.now();
            Map<MessageType, Instant> cutoffs = retention.cutoffs(now);
            List<ArchiveCandidate> candidates = archive.findCandidates(cutoffs, props.batchSize());

            // Once per run, before any decision. Every candidate is evaluated against the same
            // snapshot, so a sweep cannot delete one message and then protect an identical one
            // because a hold landed halfway through.
            HoldScopeSnapshot scope = caseHolds.activeHolds();

            run.setCandidateCount(candidates.size());
            run.setActiveHoldCount(scope.size());
            run.setHoldScopeAvailable(scope.available());
            runs.save(run);
            log.info("disposition run {} started: {} candidates, {} active hold(s){} (dryRun={}, cutoffs={})",
                    runId, candidates.size(), scope.size(),
                    scope.available() ? "" : " [SCOPE UNAVAILABLE — failing closed]", dryRun, cutoffs);
            publisher.publishAudit(audit.runStarted(runId, candidates.size(), dryRun, actor));

            List<DispositionItemEntity> ledger = new ArrayList<>(candidates.size());
            for (ArchiveCandidate candidate : candidates) {
                Decision decision = decide(runId, candidate, scope, dryRun);
                ledger.add(new DispositionItemEntity(runId, candidate, decision.outcome(),
                        decision.reason(), decision.blockingHoldId(), decision.blockingCaseId()));
                switch (decision.outcome()) {
                    case DELETED, DELETE_REQUESTED -> deleted++;
                    case SKIPPED_HOLD -> skippedHold++;
                    case FAILED -> failed++;
                    case WOULD_DELETE -> { /* dry run: counted as neither deleted nor failed */ }
                }
            }
            items.saveAll(ledger);

            run.setStatus(DispositionStatus.COMPLETED);
            run.setFinishedAt(Instant.now());
            run.setDeletedCount(deleted);
            run.setSkippedHoldCount(skippedHold);
            run.setFailedCount(failed);
            runs.save(run);

            log.info("disposition run {} completed: deleted={}, skippedHold={}, failed={}",
                    runId, deleted, skippedHold, failed);
            publisher.publishAudit(audit.runCompleted(runId, deleted, skippedHold, failed, dryRun));
            return run;
        } catch (Exception ex) {
            log.error("disposition run {} failed: {}", runId, ex.toString(), ex);
            run.setStatus(DispositionStatus.FAILED);
            run.setFinishedAt(Instant.now());
            run.setDeletedCount(deleted);
            run.setSkippedHoldCount(skippedHold);
            run.setFailedCount(failed);
            run.setError(truncate(ex.toString()));
            runs.save(run);
            publisher.publishAudit(audit.runFailed(runId, truncate(ex.toString())));
            return run;
        }
    }

    /** The guards, in cheapest-first order, then the delete. */
    private Decision decide(String runId, ArchiveCandidate candidate, HoldScopeSnapshot scope, boolean dryRun) {
        // Guard 1: P2's mirrored flag. Free, and right most of the time.
        if (candidate.onHold()) {
            return refuse(runId, candidate, "hold flag set in archive");
        }

        // Guard 2: is this message inside the scope of a hold on a case? Answered from the
        // run-level snapshot, so it holds even while P4 is still expanding that hold (FR-4.3).
        if (!scope.available() && props.holdCheck().required()) {
            return refuse(runId, candidate,
                    "active hold scope could not be retrieved from P4 — failing closed");
        }
        Optional<ActiveHold> covering = scope.coveringHold(candidate);
        if (covering.isPresent()) {
            return refuseByCaseHold(runId, candidate, covering.get());
        }

        // Guard 3: ask P4 about this message specifically. Catches anything the scope logic cannot
        // express — an evidence item added to a held case from outside the hold's custodian or
        // date range, for instance.
        HoldCheckClient.Verdict verdict = holdCheck.check(candidate.messageId());
        if (verdict == HoldCheckClient.Verdict.HELD) {
            return refuse(runId, candidate, "P4 reports an active hold");
        }
        if (verdict == HoldCheckClient.Verdict.UNKNOWN && props.holdCheck().required()) {
            return refuse(runId, candidate, "hold status could not be verified — failing closed");
        }

        if (dryRun) {
            return new Decision(DispositionOutcome.WOULD_DELETE, "past retention; dry run, not deleted");
        }

        // Guard 4 lives inside the deleter, evaluated atomically with the write.
        MessageDeleter.DeleteResult result = deleter.delete(runId, candidate);
        return switch (result) {
            case DELETED -> {
                publisher.publishAudit(audit.deleted(runId, candidate, true));
                yield new Decision(DispositionOutcome.DELETED, "past retention");
            }
            case REQUESTED -> {
                publisher.publishAudit(audit.deleted(runId, candidate, false));
                yield new Decision(DispositionOutcome.DELETE_REQUESTED, "past retention; delete command published");
            }
            case REFUSED_HOLD -> refuse(runId, candidate, "hold placed between the check and the delete");
            // Already gone. Recorded as deleted rather than failed: the desired state holds, and a
            // FAILED row would make the next run look like it is retrying something broken.
            case NOT_FOUND -> new Decision(DispositionOutcome.DELETED, "already absent from the archive");
            case FAILED -> new Decision(DispositionOutcome.FAILED, "delete failed; will retry on the next run");
        };
    }

    private Decision refuse(String runId, ArchiveCandidate candidate, String reason) {
        publisher.publishAudit(audit.refused(runId, candidate, reason));
        return new Decision(DispositionOutcome.SKIPPED_HOLD, reason, null, null);
    }

    /** A refusal that can name the case responsible — the one a regulator asks to see. */
    private Decision refuseByCaseHold(String runId, ArchiveCandidate candidate, ActiveHold hold) {
        log.info("refused to delete {}: covered by {}", candidate.messageId(), hold.describe());
        publisher.publishAudit(audit.refusedByCaseHold(runId, candidate, hold));
        return new Decision(DispositionOutcome.SKIPPED_HOLD, "covered by " + hold.describe(),
                hold.holdId(), hold.caseId());
    }

    private record Decision(DispositionOutcome outcome, String reason,
                            String blockingHoldId, String blockingCaseId) {

        Decision(DispositionOutcome outcome, String reason) {
            this(outcome, reason, null, null);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() <= 1000 ? s : s.substring(0, 1000) + "…");
    }

    /** Thrown when a sweep is already in flight. Surfaced as 409, not queued. */
    public static class DispositionRunInProgressException extends RuntimeException {
        public DispositionRunInProgressException() {
            super("a disposition run is already in progress");
        }
    }
}
