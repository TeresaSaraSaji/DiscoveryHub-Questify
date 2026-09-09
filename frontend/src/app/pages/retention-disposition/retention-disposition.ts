import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ArchiveApi } from '../../core/archive.api';
import { DispositionApi } from '../../core/disposition.api';
import { humanize, parseToIso, toIso } from '../../core/duration';
import { Failure, classify } from '../../core/failure';
import { describe } from '../../core/api-config';
import {
  DispositionItem,
  DispositionOutcome,
  DispositionRun,
  EMPTY_PAGE,
  MessageType,
  OUTCOMES,
  Page,
  RetentionPolicy,
  RunProgress,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Paginator } from '../../shared/paginator';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * Retention and disposition on one page (FR-5).
 *
 * The two belong together because they are one decision seen twice: a retention period is an
 * instruction, and a sweep is that instruction being carried out. Editing `EMAIL` down to two
 * minutes and then reading "candidates in next sweep: 500" on the same screen is the only way to
 * see what you just authorised — the candidate preview reloads after every policy change for
 * exactly that reason.
 *
 * Seven panels, six of them against P2.2 and one against P2, each loading independently. The
 * archive panel is here to prove the point: stop P2.2 and it keeps working.
 */
@Component({
  selector: 'app-retention-disposition',
  imports: [Alert, DecimalPipe, FormsModule, Paginator, Panel, Since],
  templateUrl: './retention-disposition.html',
})
export class RetentionDisposition {
  private readonly api = inject(DispositionApi);
  private readonly archiveApi = inject(ArchiveApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly outcomes = OUTCOMES;

  /** Single-persona system for now, but every audited write carries it. */
  protected readonly actor = signal('investigator');

  // ------------------------------------------------------------ panels, each independent

  protected readonly policies = this.api.policiesResource();
  protected readonly stats = this.api.statsResource();
  protected readonly candidates = this.api.candidatesResource();
  protected readonly archiveStats = this.archiveApi.statsResource();

  protected readonly runsPage = signal(0);
  protected readonly runs = this.api.runsResource(this.runsPage);

  protected readonly selectedRunId = signal<string | null>(null);
  protected readonly itemOutcome = signal<DispositionOutcome | null>(null);
  protected readonly itemsPage = signal(0);
  protected readonly items = this.api.runItemsResource(
    this.selectedRunId,
    this.itemOutcome,
    this.itemsPage,
  );

  // Templates read these, never `.value()` directly. See core/resource-utils.
  protected readonly policyRows = valueOr(this.policies, [] as RetentionPolicy[]);
  protected readonly runRows = valueOr(this.runs, EMPTY_PAGE as Page<DispositionRun>);
  protected readonly itemRows = valueOr(this.items, EMPTY_PAGE as Page<DispositionItem>);

  // ------------------------------------------------------------ policy editing

  /** Raw text per type, so a half-typed period does not fight the input on every keystroke. */
  protected readonly drafts = signal<Partial<Record<MessageType, string>>>({});
  protected readonly savingType = signal<MessageType | null>(null);
  protected readonly policyFailure = signal<Failure | null>(null);
  protected readonly policyOk = signal<string | null>(null);

  // ------------------------------------------------------------ the sweep

  protected readonly dryRun = signal(true);
  protected readonly triggering = signal(false);
  protected readonly triggerFailure = signal<Failure | null>(null);
  protected readonly triggerOk = signal<string | null>(null);
  protected readonly progress = signal<RunProgress | null>(null);
  protected readonly watching = signal(false);

  protected readonly progressPercent = computed(() => {
    const current = this.progress();
    return current ? Math.max(0, Math.min(100, current.percent)) : 0;
  });

  /**
   * The warning that belongs next to the button. `hold-check.required` is true by default and
   * means an unreachable P4 makes every sweep a no-op, which looks like nothing being wrong.
   */
  protected readonly holdCheckWarning = computed(() => {
    const current = this.stats.hasValue() ? this.stats.value() : undefined;
    if (!current) {
      return null;
    }
    if (!current.holdCheckEnabled) {
      return 'The hold check is DISABLED. Nothing is asking P4 whether these messages are under legal hold.';
    }
    const preview = this.candidates.hasValue() ? this.candidates.value() : undefined;
    if (preview && !preview.holdScopeAvailable) {
      return current.holdCheckRequired
        ? 'P4 is unreachable and the hold check is required, so this sweep will skip every candidate. That is not proof that holds work.'
        : 'P4 is unreachable and the hold check is NOT required. A sweep will delete without verifying holds.';
    }
    return null;
  });

  constructor() {
    // The last run of this boot, if there was one. A 404 here is the normal answer on a fresh
    // service ("no sweep has run since startup") and is not worth showing anyone.
    this.api
      .progress()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (value) => this.progress.set(value), error: () => undefined });
  }

  // ------------------------------------------------------------ retention policy (FR-5.1)

  protected draftFor(policy: RetentionPolicy): string {
    return this.drafts()[policy.messageType] ?? toIso(policy.periodSeconds);
  }

  protected onDraft(type: MessageType, value: string): void {
    this.drafts.update((current) => ({ ...current, [type]: value }));
  }

  protected periodLabel(seconds: number): string {
    return `${humanize(seconds)} · ${toIso(seconds)}`;
  }

  /**
   * Shortening a period is an instruction to destroy data on the next sweep, so the write is
   * audited server-side with the actor, and the candidate preview is reloaded immediately after
   * to show what the change just made eligible.
   */
  protected savePolicy(policy: RetentionPolicy): void {
    const typed = this.draftFor(policy);
    const period = parseToIso(typed);
    this.policyOk.set(null);
    if (!period) {
      this.policyFailure.set({
        kind: 'rejected',
        status: 0,
        message: `"${typed}" is not a duration. Use ISO-8601 (P2555D, PT2M) or shorthand (90d, 2m).`,
      });
      return;
    }

    this.policyFailure.set(null);
    this.savingType.set(policy.messageType);
    this.api
      .updatePolicy(policy.messageType, period, this.actor())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (saved) => {
          this.savingType.set(null);
          this.drafts.update((current) => ({ ...current, [saved.messageType]: undefined }));
          this.policyOk.set(
            `${saved.messageType} retention is now ${humanize(saved.periodSeconds)} (${toIso(saved.periodSeconds)}).`,
          );
          this.policies.reload();
          this.candidates.reload();
          this.stats.reload();
        },
        error: (error: unknown) => {
          this.savingType.set(null);
          this.policyFailure.set(classify(error, describe('p22')));
        },
      });
  }

  protected resetDraft(policy: RetentionPolicy): void {
    this.drafts.update((current) => ({ ...current, [policy.messageType]: undefined }));
    this.policyFailure.set(null);
  }

  // ------------------------------------------------------------ running a sweep (FR-5.2, FR-8.1)

  protected trigger(): void {
    this.triggerFailure.set(null);
    this.triggerOk.set(null);
    this.triggering.set(true);

    this.api
      .triggerRun({ dryRun: this.dryRun(), actor: this.actor() })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (run) => {
          this.triggering.set(false);
          this.triggerOk.set(
            run.dryRun
              ? `Dry run ${run.runId} queued. Every decision is recorded; nothing is deleted.`
              : `Sweep ${run.runId} queued.`,
          );
          this.watch();
        },
        error: (error: unknown) => {
          this.triggering.set(false);
          const failure = classify(error, describe('p22'));
          this.triggerFailure.set(failure);
          // 409 means a sweep is already running. The instruction did not take effect, but there
          // is something worth watching, so attach to it.
          if (failure.kind === 'conflict') {
            this.watch();
          }
        },
      });
  }

  /** Attach to the live stream (or its polling fallback) until the run reaches a terminal state. */
  protected watch(): void {
    if (this.watching()) {
      return;
    }
    this.watching.set(true);
    this.api
      .watchProgress()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (value) => this.progress.set(value),
        error: () => this.watching.set(false),
        complete: () => {
          this.watching.set(false);
          // The run is over: refresh what it changed. Each of these fails on its own if P2.2 has
          // gone away in the meantime.
          this.runs.reload();
          this.stats.reload();
          this.candidates.reload();
          this.archiveStats.reload();
          const finished = this.progress()?.runId;
          if (finished) {
            this.selectRun(finished);
          }
        },
      });
  }

  // ------------------------------------------------------------ the ledger (FR-5.3, FR-4.6)

  protected selectRun(runId: string): void {
    this.itemsPage.set(0);
    this.selectedRunId.set(runId);
  }

  protected filterOutcome(outcome: string): void {
    this.itemsPage.set(0);
    this.itemOutcome.set((outcome || null) as DispositionOutcome | null);
  }

  protected runLabel(run: DispositionRun): string {
    return run.dryRun ? 'dry run' : run.triggerSource.toLowerCase();
  }

  protected cutoffRows = computed(() => {
    const preview = this.candidates.hasValue() ? this.candidates.value() : undefined;
    return Object.entries(preview?.cutoffs ?? {}).map(([type, at]) => ({ type, at }));
  });
}
