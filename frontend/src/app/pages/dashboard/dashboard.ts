import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { from } from 'rxjs';
import { concatMap, last, switchMap, tap } from 'rxjs/operators';
import { ArchiveApi } from '../../core/archive.api';
import { AuditApi, EMPTY_AUDIT_FILTER } from '../../core/audit.api';
import { describe } from '../../core/api-config';
import { CasesApi } from '../../core/cases.api';
import { DispositionApi } from '../../core/disposition.api';
import { Failure, classify } from '../../core/failure';
import { IngestionApi } from '../../core/ingestion.api';
import {
  AuditEntry,
  CaseEntity,
  CaseStatus,
  EMPTY_PAGE,
  HoldEntity,
  ExportJob,
  HoldStatus,
  IngestResult,
  Page,
  RetentionMode,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/** What one run of the ingest panel added, summed across every file in it. */
interface IngestTotals {
  files: number;
  accepted: number;
  duplicates: number;
  rejected: number;
  failed: number;
}

/**
 * The first screen. Six panels against five services, and not one of them is required.
 *
 * What belongs on a landing page for this system is "what is here, and what is happening to it" —
 * an eDiscovery tool is used by someone who has to answer for what was kept and what was
 * destroyed, so the numbers worth showing on sight are the corpus size, the open matters, the
 * holds in force, and what the next retention sweep intends to delete. Everything else is a click
 * away.
 *
 * There is deliberately no search box here. Search has a page, and a dashboard that half-searches
 * is a worse version of both.
 */
@Component({
  selector: 'app-dashboard',
  imports: [Alert, DecimalPipe, Panel, RouterLink, Since],
  templateUrl: './dashboard.html',
})
export class Dashboard {
  private readonly archiveApi = inject(ArchiveApi);
  private readonly casesApi = inject(CasesApi);
  private readonly disposition = inject(DispositionApi);
  private readonly auditApi = inject(AuditApi);
  private readonly ingestion = inject(IngestionApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly links = [
    { path: '/search', label: 'Search messages', detail: 'Find email and chat across the corpus' },
    {
      path: '/cases',
      label: 'Cases & holds',
      detail: 'Open a matter, file evidence, place a hold',
    },
    {
      path: '/retention',
      label: 'Retention & disposition',
      detail: 'Policy, sweeps and the ledger',
    },
    { path: '/export-audit', label: 'Exports & audit', detail: 'Evidence packages and activity' },
  ] as const;

  // Fixed inputs, so these read once and stay put. The dashboard is a snapshot, not a monitor.
  private readonly allCases = signal<CaseStatus | ''>('');
  private readonly firstPage = signal(0);
  private readonly activeHolds = signal<HoldStatus | ''>('ACTIVE');
  private readonly noCase = signal('');
  private readonly auditFilter = signal(EMPTY_AUDIT_FILTER);

  protected readonly archiveStats = this.archiveApi.statsResource();
  protected readonly caseStats = this.casesApi.caseStatsResource();
  protected readonly holdStats = this.casesApi.holdStatsResource();
  protected readonly candidates = this.disposition.candidatesResource();

  protected readonly cases = this.casesApi.casesResource(this.allCases, this.firstPage, 5);
  protected readonly caseRows = valueOr(this.cases, EMPTY_PAGE as Page<CaseEntity>);

  protected readonly holds = this.casesApi.holdsResource(this.noCase, this.activeHolds);
  protected readonly holdRows = valueOr(this.holds, [] as HoldEntity[]);

  /**
   * FR-8.2 names four counts for this page, and this is the fourth. It comes from the job list
   * rather than a dedicated endpoint because P5 does not expose one, and the list is capped at 50
   * server-side — so this is "completed of the 50 most recent", which the label says.
   */
  protected readonly exports = this.auditApi.exportsResource();
  private readonly exportRows = valueOr(this.exports, [] as ExportJob[]);
  protected readonly exportCounts = computed(() => {
    const jobs = this.exportRows();
    return {
      completed: jobs.filter((job) => job.status === 'COMPLETED').length,
      running: jobs.filter((job) => job.status === 'QUEUED' || job.status === 'RUNNING').length,
      failed: jobs.filter((job) => job.status === 'FAILED').length,
    };
  });

  protected readonly audit = this.auditApi.auditResource(this.auditFilter, this.firstPage, 8);
  protected readonly auditRows = valueOr(this.audit, EMPTY_PAGE as Page<AuditEntry>);

  protected readonly statusClass = (status: CaseStatus): string => {
    if (status === 'ACTIVE') {
      return 'tag tag--ok';
    }
    if (status === 'UNDER_REVIEW') {
      return 'tag tag--warn';
    }
    // CLOSED is not a failure, and DRAFT is not a warning. Both are just neutral facts.
    return 'tag';
  };

  protected readonly outcomeClass = (outcome: string): string => {
    const value = outcome.toUpperCase();
    if (value === 'SUCCESS') {
      return 'tag tag--ok';
    }
    // A refusal is the most important kind of event in this system: a hold blocking a delete
    // lands here, and it is neither a success nor a fault.
    return value === 'REFUSED' ? 'tag tag--warn' : 'tag tag--danger';
  };

  // ------------------------------------------------------------ adding messages (P1)

  /**
   * Loading a folder or a file of messages, under one of two retention rules.
   *
   * The choice is the point of this panel. `NORMAL` keeps the messages for their type's period —
   * seven years for email — which is correct and useless to demonstrate disposition with.
   * `DEMO` labels this upload alone, and P2 stamps a deadline about a minute out on those rows, so
   * the next sweep destroys them and nothing else. Both rules are enforced entirely by the
   * services; this panel only chooses which one the upload is tagged with.
   */
  protected readonly retentionMode = signal<RetentionMode>('NORMAL');
  protected readonly picked = signal<File[]>([]);
  protected readonly skipped = signal(0);

  protected readonly ingesting = signal(false);
  /** Which file of how many, and how far into it — P1 reports a count, not a percentage. */
  protected readonly ingestProgress = signal<{
    name: string;
    index: number;
    of: number;
    processed: number;
  } | null>(null);
  protected readonly ingestTotals = signal<IngestTotals | null>(null);
  protected readonly ingestProblems = signal<IngestResult[]>([]);
  protected readonly ingestFailure = signal<Failure | null>(null);

  protected readonly canIngest = computed(() => this.picked().length > 0 && !this.ingesting());

  /**
   * Only the shapes P1 can read. A folder picker returns everything in the tree — for an export
   * package that means `manifest.json` and every attachment — and posting a PDF to the message
   * endpoint earns a rejection that looks like a bug in the upload rather than a file that was
   * never a message. The count of what was left out is shown, so the filtering is visible.
   */
  private static readonly INGESTIBLE = /\.(json|ndjson|jsonl)$/i;

  protected pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const all = Array.from(input.files ?? []);
    const usable = all.filter((file) => Dashboard.INGESTIBLE.test(file.name));
    this.picked.set(usable);
    this.skipped.set(all.length - usable.length);
    this.ingestTotals.set(null);
    this.ingestProblems.set([]);
    this.ingestFailure.set(null);
    // So re-picking the same folder fires a change event again.
    input.value = '';
  }

  protected clearPicked(): void {
    this.picked.set([]);
    this.skipped.set(0);
    this.ingestTotals.set(null);
    this.ingestProblems.set([]);
    this.ingestFailure.set(null);
  }

  /**
   * Upload the picked files one after another, and total up what P1 made of them.
   *
   * `concatMap` rather than `mergeMap`: P1 bounds its own upload executor and a folder fired at it
   * in parallel would earn a 503 for the surplus, reported as a failure of files that were
   * perfectly fine. Sequential is also the only order in which "file 3 of 20" means anything.
   *
   * A file that fails does not abort the rest — its `error` is counted and the run continues,
   * because one malformed file in a folder is not a reason to refuse the other nineteen.
   */
  protected ingest(): void {
    if (!this.canIngest()) {
      return;
    }
    const files = this.picked();
    const mode = this.retentionMode();
    this.ingesting.set(true);
    this.ingestTotals.set(null);
    this.ingestProblems.set([]);
    this.ingestFailure.set(null);

    const totals: IngestTotals = { files: 0, accepted: 0, duplicates: 0, rejected: 0, failed: 0 };

    from(files)
      .pipe(
        concatMap((file, index) => {
          this.ingestProgress.set({
            name: file.name,
            index: index + 1,
            of: files.length,
            processed: 0,
          });
          return this.ingestion.upload(file, mode).pipe(
            switchMap((job) => this.ingestion.watchJob(job.jobId)),
            tap((job) =>
              this.ingestProgress.update((current) =>
                current ? { ...current, processed: job.processed } : current,
              ),
            ),
            // Only the terminal snapshot carries the counts.
            last(),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (job) => {
          totals.files += 1;
          const result = job.result;
          if (!result) {
            // FAILED: the file could not be read or parsed, so nothing in it was ingested.
            totals.failed += 1;
            this.ingestProblems.update((problems) =>
              [
                ...problems,
                { externalId: job.filename, outcome: 'FAILED' as const, reason: job.error },
              ].slice(0, 10),
            );
            return;
          }
          totals.accepted += result.accepted;
          totals.duplicates += result.duplicates;
          totals.rejected += result.rejected;
          totals.failed += result.failed;
          if (result.problems?.length) {
            this.ingestProblems.update((problems) =>
              [...problems, ...(result.problems ?? [])].slice(0, 10),
            );
          }
        },
        error: (error: unknown) => {
          this.ingesting.set(false);
          this.ingestProgress.set(null);
          this.ingestFailure.set(classify(error, describe('p1')));
        },
        complete: () => {
          this.ingesting.set(false);
          this.ingestProgress.set(null);
          this.ingestTotals.set({ ...totals });
          this.picked.set([]);
          // The two counts on this page that the upload just changed. Everything else on the
          // dashboard is unaffected, so it is left alone rather than reloaded wholesale.
          this.archiveStats.reload();
          this.candidates.reload();
        },
      });
  }

  /** Only worth saying when the sweep would actually destroy something. */
  protected readonly sweepWarning = computed(() => {
    const preview = this.candidates.hasValue() ? this.candidates.value() : undefined;
    if (!preview || preview.wouldBeDeleted === 0) {
      return null;
    }
    return `The next sweep would delete ${preview.wouldBeDeleted} message${
      preview.wouldBeDeleted === 1 ? '' : 's'
    }.`;
  });
}
