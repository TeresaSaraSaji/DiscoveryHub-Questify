import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { describe } from '../../core/api-config';
import { CasesApi } from '../../core/cases.api';
import { Failure, classify } from '../../core/failure';
import {
  CASE_STATUSES,
  CaseEntity,
  CaseStatus,
  EMPTY_PAGE,
  MATTER_TYPES,
  MatterType,
  Page,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Paginator } from '../../shared/paginator';
import { Panel } from '../../shared/panel';

/**
 * The list of matters, and the form that opens one.
 *
 * Finding a case and working on a case are different jobs, so a row navigates to
 * `/cases/:caseId` — see {@link CaseDetail} — rather than unfolding eight panels underneath the
 * table. That also gives a case a URL, which is the thing people want to send each other.
 *
 * The two figures at the top are service-wide on purpose: they are the reason to come here, and
 * they must not depend on a case being picked first.
 */
@Component({
  selector: 'app-cases-page',
  imports: [Alert, DecimalPipe, FormsModule, Paginator, Panel],
  templateUrl: './cases-page.html',
})
export class CasesPage {
  private readonly api = inject(CasesApi);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly statuses = CASE_STATUSES;
  protected readonly matterTypes = MATTER_TYPES;

  protected readonly statusFilter = signal<CaseStatus | ''>('');
  protected readonly casesPage = signal(0);

  // ------------------------------------------------------------ the list

  protected readonly cases = this.api.casesResource(this.statusFilter, this.casesPage);
  protected readonly caseRows = valueOr(this.cases, EMPTY_PAGE as Page<CaseEntity>);

  protected readonly caseStats = this.api.caseStatsResource();
  protected readonly holdStats = this.api.holdStatsResource();

  // ------------------------------------------------------------ open a case

  protected readonly newName = signal('');
  protected readonly newDescription = signal('');
  protected readonly newMatter = signal<MatterType>('INVESTIGATION');
  protected readonly newOwner = signal('investigator');
  protected readonly creating = signal(false);
  protected readonly createFailure = signal<Failure | null>(null);
  protected readonly createOk = signal<string | null>(null);

  // ------------------------------------------------------------ actions

  protected open(caseId: string): void {
    void this.router.navigate(['/cases', caseId]);
  }

  protected filterStatus(status: string): void {
    this.casesPage.set(0);
    this.statusFilter.set((status || '') as CaseStatus | '');
  }

  protected createCase(): void {
    const name = this.newName().trim();
    if (!name) {
      return;
    }
    this.createFailure.set(null);
    this.createOk.set(null);
    this.creating.set(true);

    this.api
      .createCase({
        name,
        description: this.newDescription().trim() || null,
        matterType: this.newMatter(),
        owner: this.newOwner().trim() || 'investigator',
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (created) => {
          this.creating.set(false);
          this.newName.set('');
          this.newDescription.set('');
          this.cases.reload();
          this.caseStats.reload();
          // Straight to the new case: opening one is only ever the first step of working on it.
          this.open(created.caseId);
        },
        error: (error: unknown) => {
          this.creating.set(false);
          this.createFailure.set(classify(error, describe('p4case')));
        },
      });
  }

  protected caseStatusClass(status: CaseStatus): string {
    if (status === 'ACTIVE') {
      return 'tag tag--ok';
    }
    return status === 'UNDER_REVIEW' ? 'tag tag--warn' : 'tag';
  }
}
