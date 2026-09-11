import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ArchiveApi } from '../../core/archive.api';
import { AuditApi, EMPTY_AUDIT_FILTER } from '../../core/audit.api';
import { CasesApi } from '../../core/cases.api';
import { DispositionApi } from '../../core/disposition.api';
import {
  AuditEntry,
  CaseEntity,
  CaseStatus,
  EMPTY_PAGE,
  HoldEntity,
  ExportJob,
  HoldStatus,
  Page,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

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
  imports: [DecimalPipe, Panel, RouterLink, Since],
  templateUrl: './dashboard.html',
})
export class Dashboard {
  private readonly archiveApi = inject(ArchiveApi);
  private readonly casesApi = inject(CasesApi);
  private readonly disposition = inject(DispositionApi);
  private readonly auditApi = inject(AuditApi);

  protected readonly links = [
    { path: '/search', label: 'Search messages', detail: 'Find email and chat across the corpus' },
    {
      path: '/cases',
      label: 'Cases & holds',
      detail: 'Pick a matter, file evidence, place a hold',
    },
    { path: '/cases/new', label: 'Open a case', detail: 'Start a new matter in DRAFT' },
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
