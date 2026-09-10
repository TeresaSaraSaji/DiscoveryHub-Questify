import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { describe } from '../../core/api-config';
import {
  AUDIT_ACTION_GROUPS,
  AUDIT_ACTIONS_BY_SERVICE,
  AUDIT_OUTCOMES,
  AUDIT_SERVICES,
  AuditApi,
  AuditFilter,
  EMPTY_AUDIT_FILTER,
} from '../../core/audit.api';
import { CasesApi } from '../../core/cases.api';
import { Failure, classify } from '../../core/failure';
import {
  AuditEntry,
  CaseEntity,
  CaseStatus,
  EMPTY_PAGE,
  ExportJob,
  Page,
  VerificationResult,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Paginator } from '../../shared/paginator';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * Export and audit, which are one obligation seen twice.
 *
 * A package is only defensible if the trail says who asked for it, when, and what went into it, so
 * P5 owns both and they share a page. Three properties of that service the UI is built around:
 *
 * - **Export is asynchronous.** The request returns a QUEUED job; this polls it to a terminal
 *   state. Packaging a case out of a corpus is not request-sized work.
 * - **Download is a link, not bytes.** `/download` returns a presigned MinIO URL good for fifteen
 *   minutes, so the browser fetches the package directly and this app never proxies evidence.
 * - **The digest is worth re-deriving.** `/verify` recomputes every checksum from the package's own
 *   bytes rather than trusting the job row, which is the only version of this that is evidence
 *   rather than metadata. It is a separate button because it is a separate claim.
 */
@Component({
  selector: 'app-export-audit',
  imports: [Alert, DecimalPipe, FormsModule, Paginator, Panel, Since],
  templateUrl: './export-audit.html',
})
export class ExportAudit {
  private readonly api = inject(AuditApi);
  private readonly casesApi = inject(CasesApi);
  private readonly destroyRef = inject(DestroyRef);

  // ------------------------------------------------------------ requesting an export

  /** Exactly one scope. The service returns a 400 if neither is given. */
  protected readonly scope = signal<'case' | 'custodian' | 'messages'>('case');
  protected readonly caseId = signal('');
  protected readonly custodianId = signal('');
  protected readonly messageIds = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');

  protected readonly requesting = signal(false);
  protected readonly requestFailure = signal<Failure | null>(null);
  protected readonly requestOk = signal<string | null>(null);

  private readonly anyStatus = signal<CaseStatus | ''>('');
  private readonly firstPage = signal(0);
  private readonly cases = this.casesApi.casesResource(this.anyStatus, this.firstPage, 100);
  private readonly casePage = valueOr(this.cases, EMPTY_PAGE as Page<CaseEntity>);
  protected readonly caseOptions = computed(() => this.casePage().content);

  protected readonly exports = this.api.exportsResource();
  protected readonly exportRows = valueOr(this.exports, [] as ExportJob[]);
  protected readonly tracked = signal<ExportJob | null>(null);

  protected readonly verifying = signal<string | null>(null);
  protected readonly verification = signal<VerificationResult | null>(null);
  protected readonly verifyFailure = signal<Failure | null>(null);

  protected readonly downloadFailure = signal<Failure | null>(null);

  /** Parsed once here so the button state and the request cannot disagree. */
  private readonly parsedMessageIds = computed(() =>
    this.messageIds()
      .split(/[\s,]+/)
      .map((id) => id.trim())
      .filter(Boolean),
  );

  protected readonly canRequest = computed(() => {
    if (this.scope() === 'custodian') {
      return Boolean(this.custodianId().trim());
    }
    if (this.scope() === 'messages') {
      return this.parsedMessageIds().length > 0;
    }
    // A case export still needs a concrete scope: P5 takes messageIds or a custodianId, and a
    // caseId alone is a label on the job, not a selection.
    return Boolean(this.caseId() && this.custodianId().trim());
  });

  // ------------------------------------------------------------ audit

  protected readonly serviceOptions = AUDIT_SERVICES;
  protected readonly outcomeOptions = AUDIT_OUTCOMES;

  /**
   * The filter is staged, then applied.
   *
   * `draft` is what the controls are bound to; `applied` is what the request is built from. Only
   * {@link search} copies one to the other, so choosing a service, an action and an outcome is one
   * query rather than three, and the trail on screen keeps matching the last thing asked for while
   * the next question is still being composed. Paging reads `applied`, so it cannot pick up a
   * half-built filter either.
   */
  protected readonly draft = signal<AuditFilter>(EMPTY_AUDIT_FILTER);
  private readonly applied = signal<AuditFilter>(EMPTY_AUDIT_FILTER);
  protected readonly auditPage = signal(0);
  protected readonly audit = this.api.auditResource(this.applied, this.auditPage);
  protected readonly auditRows = valueOr(this.audit, EMPTY_PAGE as Page<AuditEntry>);

  /** The controls have moved on from the results below, so the table is answering an older question. */
  protected readonly filterDirty = computed(() => {
    const draft = this.draft();
    const applied = this.applied();
    return (['service', 'action', 'outcome', 'subjectId'] as const).some(
      (key) => draft[key].trim() !== applied[key].trim(),
    );
  });

  protected readonly filterActive = computed(() =>
    (['service', 'action', 'outcome', 'subjectId'] as const).some((key) =>
      Boolean(this.applied()[key].trim()),
    ),
  );

  /**
   * The actions on offer, narrowed to the chosen service.
   *
   * Empty groups are dropped rather than shown empty, so picking P3 leaves one entry rather than
   * five headings with nothing under them.
   */
  protected readonly actionGroups = computed(() => {
    const allowed = AUDIT_ACTIONS_BY_SERVICE[this.draft().service];
    if (!allowed) {
      return AUDIT_ACTION_GROUPS;
    }
    return AUDIT_ACTION_GROUPS.map((group) => ({
      label: group.label,
      actions: group.actions.filter((action) => allowed.includes(action)),
    })).filter((group) => group.actions.length > 0);
  });

  // ------------------------------------------------------------ actions

  protected request(): void {
    if (!this.canRequest()) {
      return;
    }
    this.requestFailure.set(null);
    this.requestOk.set(null);
    this.verification.set(null);
    this.requesting.set(true);

    const explicit = this.scope() === 'messages';
    this.api
      .requestExport({
        caseId: this.scope() === 'case' ? this.caseId() || null : null,
        messageIds: explicit ? this.parsedMessageIds() : [],
        custodianId: explicit ? null : this.custodianId().trim() || null,
        from: explicit ? null : toInstant(this.from()),
        to: explicit ? null : toInstant(this.to()),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (job) => {
          this.requesting.set(false);
          this.requestOk.set(`Export ${job.jobId.slice(0, 8)}… queued.`);
          this.tracked.set(job);
          this.follow(job.jobId);
          this.exports.reload();
        },
        error: (error: unknown) => {
          this.requesting.set(false);
          this.requestFailure.set(classify(error, describe('p5')));
        },
      });
  }

  /**
   * Poll one job to a terminal state.
   *
   * A failed poll ends the follow rather than retrying forever: the job list is still on screen,
   * and a spinner that never resolves is worse than a stale row.
   */
  protected follow(jobId: string): void {
    timer(0, 2_000)
      .pipe(
        switchMap(() => this.api.job(jobId)),
        takeWhile((job) => job.status === 'QUEUED' || job.status === 'RUNNING', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (job) => this.tracked.set(job),
        error: () => undefined,
        complete: () => {
          this.exports.reload();
          this.audit.reload();
        },
      });
  }

  /** Fetch the presigned link, then hand it to the browser. */
  protected download(job: ExportJob): void {
    this.downloadFailure.set(null);
    this.api
      .download(job.jobId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (link) => {
          window.open(link.url, '_blank', 'noopener');
          // Downloading is itself an audited act, so the trail below is now out of date.
          this.audit.reload();
        },
        error: (error: unknown) => this.downloadFailure.set(classify(error, describe('p5'))),
      });
  }

  protected verify(job: ExportJob): void {
    this.verifyFailure.set(null);
    this.verification.set(null);
    this.verifying.set(job.jobId);
    this.api
      .verify(job.jobId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.verifying.set(null);
          this.verification.set(result);
        },
        error: (error: unknown) => {
          this.verifying.set(null);
          this.verifyFailure.set(classify(error, describe('p5')));
        },
      });
  }

  protected retry(job: ExportJob): void {
    this.requestFailure.set(null);
    this.api
      .retry(job.jobId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (requeued) => {
          this.tracked.set(requeued);
          this.follow(requeued.jobId);
          this.exports.reload();
        },
        error: (error: unknown) => this.requestFailure.set(classify(error, describe('p5'))),
      });
  }

  /** Stages a change. Deliberately does not search: {@link search} is the only thing that does. */
  protected patchFilter(patch: Partial<AuditFilter>): void {
    this.draft.update((current) => ({ ...current, ...patch }));
  }

  /**
   * Stages a service, dropping an action that service cannot emit.
   *
   * Without this, narrowing the list would leave the old action staged but no longer visible in
   * it — a filter the user can neither see nor have meant, guaranteeing an empty page. Moving to
   * "All services" keeps whatever action is staged, since every action is on offer again.
   */
  protected selectService(service: string): void {
    this.draft.update((current) => {
      const allowed = AUDIT_ACTIONS_BY_SERVICE[service];
      const keepAction = !allowed || !current.action || allowed.includes(current.action);
      return { ...current, service, action: keepAction ? current.action : '' };
    });
  }

  /**
   * Applies every staged filter at once, back at page one.
   *
   * Whatever is blank stays out of the query — the resource only sends the keys that have a value
   * — so any subset of the four works, and none of them is required.
   */
  protected search(): void {
    this.auditPage.set(0);
    this.applied.set(this.draft());
  }

  protected clearFilter(): void {
    this.auditPage.set(0);
    this.draft.set(EMPTY_AUDIT_FILTER);
    this.applied.set(EMPTY_AUDIT_FILTER);
  }

  /** A shortcut, so it stages and applies in one go rather than waiting for Search. */
  protected showRefusalsOnly(): void {
    this.patchFilter({ outcome: 'REFUSED' });
    this.search();
  }

  protected statusClass(status: ExportJob['status']): string {
    if (status === 'COMPLETED') {
      return 'tag tag--ok';
    }
    if (status === 'FAILED') {
      return 'tag tag--danger';
    }
    return 'tag tag--busy';
  }

  protected outcomeClass(outcome: string): string {
    const value = outcome.toUpperCase();
    if (value === 'SUCCESS') {
      return 'tag tag--ok';
    }
    // A refusal is deliberate, and usually the most important row on the page.
    return value === 'REFUSED' ? 'tag tag--warn' : 'tag tag--danger';
  }

  protected detailPairs(detail: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(detail ?? {}).map(([key, value]) => ({ key, value }));
  }

  protected sizeLabel(bytes: number | null): string {
    if (bytes === null || bytes === 0) {
      return '—';
    }
    const units = ['B', 'kB', 'MB', 'GB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
  }
}

function toInstant(value: string): string | null {
  if (!value.trim()) {
    return null;
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}
