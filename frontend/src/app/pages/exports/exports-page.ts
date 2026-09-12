import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { describe } from '../../core/api-config';
import { AuditApi } from '../../core/audit.api';
import { CasesApi } from '../../core/cases.api';
import { Failure, classify } from '../../core/failure';
import {
  CaseEntity,
  CaseStatus,
  EMPTY_PAGE,
  ExportJob,
  Page,
  VerificationResult,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * Evidence packages: requesting one, watching it build, and getting it out.
 *
 * P5 owns this and the audit trail, and the two used to share a page on the grounds that a package
 * is only defensible if the trail says who asked for it. That is still true, and it is an argument
 * for them being one service rather than one screen — in practice the page was two unrelated jobs
 * of work stacked vertically, and doing either meant scrolling past the other. The trail has its
 * own page now and this links to it.
 *
 * Three properties of the service this is built around:
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
  selector: 'app-exports-page',
  imports: [Alert, DecimalPipe, FormsModule, Panel, RouterLink, Since],
  templateUrl: './exports-page.html',
})
export class ExportsPage {
  private readonly api = inject(AuditApi);
  private readonly casesApi = inject(CasesApi);
  private readonly destroyRef = inject(DestroyRef);

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
    // A case is a scope on its own now: P5 resolves it to the case's evidence items. A custodian
    // narrows that to one person's messages within the matter, and is optional.
    return Boolean(this.caseId());
  });

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
        // Blank rather than omitted would read as "a custodian called empty string" to a service
        // that now treats the field as optional, so it is normalised to null here.
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
        complete: () => this.exports.reload(),
      });
  }

  /**
   * Fetch the presigned link, then hand it to the browser.
   *
   * Requesting, completing and downloading are all audited by P5 as they happen. Nothing is
   * refreshed here for that: the trail is its own page and reads current data when opened.
   */
  protected download(job: ExportJob): void {
    this.downloadFailure.set(null);
    this.api
      .download(job.jobId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (link) => window.open(link.url, '_blank', 'noopener'),
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

  protected statusClass(status: ExportJob['status']): string {
    if (status === 'COMPLETED') {
      return 'tag tag--ok';
    }
    if (status === 'FAILED') {
      return 'tag tag--danger';
    }
    return 'tag tag--busy';
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
