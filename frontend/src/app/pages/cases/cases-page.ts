import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { CasesApi } from '../../core/cases.api';
import { CASE_STATUSES, CaseEntity, CaseStatus, EMPTY_PAGE, Page } from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Paginator } from '../../shared/paginator';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * The list of matters.
 *
 * Finding a case and working on a case are different jobs, so a row navigates to
 * `/cases/:caseId` — see {@link CaseDetail} — rather than unfolding eight panels underneath the
 * table. That also gives a case a URL, which is the thing people want to send each other.
 *
 * Opening a matter is not here either; it lives on `/cases/new`. It is the one thing on this
 * screen done once per matter rather than repeatedly, and it does not belong beside a list that
 * is read constantly.
 *
 * What is left is the list and the two service-wide figures, and those are deliberately
 * service-wide: they are the reason to come here, and they must not depend on a case being
 * picked first.
 */
@Component({
  selector: 'app-cases-page',
  imports: [DecimalPipe, Paginator, Panel, RouterLink, Since],
  templateUrl: './cases-page.html',
})
export class CasesPage {
  private readonly api = inject(CasesApi);
  private readonly router = inject(Router);

  protected readonly statuses = CASE_STATUSES;

  protected readonly statusFilter = signal<CaseStatus | ''>('');
  protected readonly casesPage = signal(0);

  protected readonly cases = this.api.casesResource(this.statusFilter, this.casesPage);
  protected readonly caseRows = valueOr(this.cases, EMPTY_PAGE as Page<CaseEntity>);

  protected readonly caseStats = this.api.caseStatsResource();
  protected readonly holdStats = this.api.holdStatsResource();

  protected open(caseId: string): void {
    void this.router.navigate(['/cases', caseId]);
  }

  protected filterStatus(status: string): void {
    this.casesPage.set(0);
    this.statusFilter.set((status || '') as CaseStatus | '');
  }

  protected caseStatusClass(status: CaseStatus): string {
    if (status === 'ACTIVE') {
      return 'tag tag--ok';
    }
    return status === 'UNDER_REVIEW' ? 'tag tag--warn' : 'tag';
  }
}
