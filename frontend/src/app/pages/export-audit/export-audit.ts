import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { describe } from '../../core/api-config';
import { AuditApi, AuditFilter, EMPTY_AUDIT_FILTER } from '../../core/audit.api';
import { DispositionApi } from '../../core/disposition.api';
import { Failure, classify } from '../../core/failure';
import {
  AUDIT_OUTCOMES,
  AuditEvent,
  AuditOutcome,
  DispositionItem,
  EMPTY_PAGE,
  EXPORT_FORMATS,
  ExportFormat,
  ExportJob,
  Page,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Paginator } from '../../shared/paginator';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * Export and audit on one page (FR-6, FR-7).
 *
 * They belong together because an export is only defensible if the audit trail says who asked for
 * it, when, and what was in it. Producing the package and recording that it was produced are two
 * halves of one obligation, and P5 owns both.
 *
 * P5 does not exist yet, so the first four panels are the contract and nothing else — they fail
 * honestly rather than showing invented data. The last panel is the part that works today: P2.2's
 * disposition ledger is a real chain of custody for one message, written at the time and outliving
 * the message itself. When P5 arrives it supersedes nothing here; it widens it to all five
 * services.
 */
@Component({
  selector: 'app-export-audit',
  imports: [Alert, DecimalPipe, FormsModule, Paginator, Panel, Since],
  templateUrl: './export-audit.html',
})
export class ExportAudit {
  private readonly api = inject(AuditApi);
  private readonly disposition = inject(DispositionApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly formats = EXPORT_FORMATS;
  protected readonly auditOutcomes = AUDIT_OUTCOMES;

  // ------------------------------------------------------------ export (FR-6)

  protected readonly exportCaseId = signal('');
  protected readonly exportFormat = signal<ExportFormat>('PST');
  protected readonly includeAttachments = signal(true);
  protected readonly requestedBy = signal('investigator');

  protected readonly requesting = signal(false);
  protected readonly requestFailure = signal<Failure | null>(null);
  protected readonly requestOk = signal<string | null>(null);
  /** The job we are currently following, polled until it reaches a terminal state. */
  protected readonly tracked = signal<ExportJob | null>(null);

  protected readonly exportsPage = signal(0);
  protected readonly exports = this.api.exportsResource(this.exportsPage);
  protected readonly exportRows = valueOr(this.exports, EMPTY_PAGE as Page<ExportJob>);

  // ------------------------------------------------------------ audit (FR-7)

  protected readonly filter = signal<AuditFilter>(EMPTY_AUDIT_FILTER);
  protected readonly auditPage = signal(0);
  protected readonly events = this.api.auditResource(this.filter, this.auditPage);
  protected readonly eventRows = valueOr(this.events, EMPTY_PAGE as Page<AuditEvent>);

  protected readonly subjectId = signal('');
  protected readonly chain = this.api.chainResource(this.subjectId);
  protected readonly chainRows = valueOr(this.chain, [] as AuditEvent[]);

  // ------------------------------------------------------------ the trail that exists today

  protected readonly ledgerId = signal('');
  protected readonly ledgerBusy = signal(false);
  protected readonly ledger = signal<DispositionItem[] | null>(null);
  protected readonly ledgerFailure = signal<Failure | null>(null);

  // ------------------------------------------------------------ actions

  protected requestExport(): void {
    const caseId = this.exportCaseId().trim();
    if (!caseId) {
      return;
    }
    this.requestFailure.set(null);
    this.requestOk.set(null);
    this.requesting.set(true);

    this.api
      .requestExport({
        caseId,
        format: this.exportFormat(),
        includeAttachments: this.includeAttachments(),
        requestedBy: this.requestedBy(),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (job) => {
          this.requesting.set(false);
          this.requestOk.set(`Export ${job.exportId} queued for ${job.caseId}.`);
          this.tracked.set(job);
          this.follow(job.exportId);
          this.exports.reload();
        },
        error: (error: unknown) => {
          this.requesting.set(false);
          this.requestFailure.set(classify(error, describe('p5')));
        },
      });
  }

  /**
   * Poll one job to completion.
   *
   * Packaging a case out of a 12,000-message corpus is not request-sized work, so the request
   * returns a job and this follows it. A failed poll ends the follow rather than retrying forever:
   * the job list is still there, and a spinner that never resolves is worse than a stale row.
   */
  protected follow(exportId: string): void {
    timer(0, 2_000)
      .pipe(
        switchMap(() => this.api.export(exportId)),
        takeWhile((job) => job.status === 'QUEUED' || job.status === 'RUNNING', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (job) => this.tracked.set(job),
        error: () => undefined,
        complete: () => this.exports.reload(),
      });
  }

  protected downloadUrl(job: ExportJob): string {
    return this.api.packageUrl(job.exportId);
  }

  protected patchFilter(patch: Partial<AuditFilter>): void {
    this.auditPage.set(0);
    this.filter.update((current) => ({ ...current, ...patch }));
  }

  protected clearFilter(): void {
    this.auditPage.set(0);
    this.filter.set(EMPTY_AUDIT_FILTER);
  }

  protected outcomeClass(outcome: AuditOutcome): string {
    if (outcome === 'SUCCESS') {
      return 'tag tag--ok';
    }
    // A refusal is not a failure, and in this system it is usually the most important event on the
    // page: a hold blocking a delete lands here.
    return outcome === 'REFUSED' ? 'tag tag--warn' : 'tag tag--danger';
  }

  protected loadLedger(): void {
    const id = this.ledgerId().trim();
    if (!id) {
      return;
    }
    this.ledgerFailure.set(null);
    this.ledger.set(null);
    this.ledgerBusy.set(true);
    this.disposition
      .messageHistory(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => {
          this.ledgerBusy.set(false);
          this.ledger.set(items);
        },
        error: (error: unknown) => {
          this.ledgerBusy.set(false);
          this.ledgerFailure.set(classify(error, describe('p22')));
        },
      });
  }

  /** Read one message id from the audit trail straight into the P2.2 ledger lookup. */
  protected inspect(subjectId: string): void {
    this.ledgerId.set(subjectId);
    this.loadLedger();
  }

  protected detailPairs(detail: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(detail ?? {}).map(([key, value]) => ({ key, value }));
  }
}
