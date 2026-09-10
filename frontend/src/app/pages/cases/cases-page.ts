import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { forkJoin, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { describe } from '../../core/api-config';
import { CasesApi } from '../../core/cases.api';
import { DispositionApi } from '../../core/disposition.api';
import { Failure, classify } from '../../core/failure';
import {
  CASE_STATUSES,
  CaseCustodian,
  CaseEntity,
  CaseStatus,
  DispositionItem,
  EMPTY_PAGE,
  Evidence,
  HoldEntity,
  MATTER_TYPES,
  MatterType,
  NEXT_STATUS,
  Page,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Paginator } from '../../shared/paginator';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * Cases and the holds on them — P4's two halves on one page, plus the proof from P2.2.
 *
 * The arrangement follows what someone actually does here: pick or open a matter, put custodians
 * and evidence on it, place a hold so retention stops touching any of it, and later show that the
 * hold did its job. That last panel comes from P2.2 rather than P4, which matters: it was written
 * at sweep time and outlives both the release of the hold and the closing of the case, so it is
 * evidence rather than a status display.
 *
 * Two behaviours the UI must not smooth over:
 *
 * - **A new hold protects nothing yet.** `POST /holds` returns `RESOLVING`; a worker resolves the
 *   scope and flips it to `ACTIVE`. So a placed hold is polled to completion, and while it is
 *   resolving the page says so rather than showing a reassuring `messageCount` of 0.
 * - **A closed case is read-only.** case-service refuses evidence, custodians and further
 *   transitions on it with a 409, so the controls are disabled rather than left to fail.
 */
@Component({
  selector: 'app-cases-page',
  imports: [Alert, DecimalPipe, FormsModule, Paginator, Panel, Since],
  templateUrl: './cases-page.html',
})
export class CasesPage {
  private readonly api = inject(CasesApi);
  private readonly disposition = inject(DispositionApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly statuses = CASE_STATUSES;
  protected readonly matterTypes = MATTER_TYPES;

  /** Deep link from the dashboard: /cases?caseId=… */
  readonly caseId = input('');

  protected readonly selected = signal('');
  protected readonly statusFilter = signal<CaseStatus | ''>('');
  protected readonly casesPage = signal(0);

  constructor() {
    effect(() => {
      const incoming = this.caseId();
      if (incoming) {
        this.selected.set(incoming);
      }
    });
  }

  // ------------------------------------------------------------ case list and detail

  protected readonly cases = this.api.casesResource(this.statusFilter, this.casesPage);
  protected readonly caseRows = valueOr(this.cases, EMPTY_PAGE as Page<CaseEntity>);

  protected readonly caseStats = this.api.caseStatsResource();
  protected readonly detail = this.api.caseResource(this.selected);
  protected readonly custodians = this.api.custodiansResource(this.selected);
  protected readonly evidence = this.api.evidenceResource(this.selected);

  // Templates read these, never `.value()`. A resource throws from `value()` in its error state.
  protected readonly custodianRows = valueOr(this.custodians, [] as CaseCustodian[]);
  protected readonly evidenceRows = valueOr(this.evidence, [] as Evidence[]);

  protected readonly current = computed(() =>
    this.detail.hasValue() ? this.detail.value() : undefined,
  );

  protected readonly readOnly = computed(() => this.current()?.status === 'CLOSED');
  protected readonly nextStatus = computed(() => {
    const status = this.current()?.status;
    return status ? NEXT_STATUS[status] : null;
  });

  // ------------------------------------------------------------ holds

  private readonly noStatus = signal<'' | 'ACTIVE'>('');
  protected readonly holds = this.api.holdsResource(this.selected, this.noStatus);
  protected readonly holdRows = valueOr(this.holds, [] as HoldEntity[]);
  protected readonly holdStats = this.api.holdStatsResource();
  protected readonly holdCount = this.api.caseHoldCountResource(this.selected);

  protected readonly resolving = computed(() =>
    this.holdRows().some((hold) => hold.status === 'RESOLVING'),
  );

  // ------------------------------------------------------------ what holds have saved (P2.2)

  protected readonly protectedPage = signal(0);
  protected readonly protectedItems = this.disposition.protectedByCaseResource(
    this.selected,
    this.protectedPage,
  );
  protected readonly protectedRows = valueOr(
    this.protectedItems,
    EMPTY_PAGE as Page<DispositionItem>,
  );

  // ------------------------------------------------------------ forms

  protected readonly newName = signal('');
  protected readonly newDescription = signal('');
  protected readonly newMatter = signal<MatterType>('INVESTIGATION');
  protected readonly newOwner = signal('investigator');
  protected readonly creating = signal(false);
  protected readonly createFailure = signal<Failure | null>(null);
  protected readonly createOk = signal<string | null>(null);

  protected readonly custodianId = signal('');
  protected readonly addingCustodian = signal(false);
  protected readonly custodianFailure = signal<Failure | null>(null);
  protected readonly custodianOk = signal<string | null>(null);

  protected readonly evidenceId = signal('');
  protected readonly addingEvidence = signal(false);
  protected readonly evidenceFailure = signal<Failure | null>(null);
  protected readonly evidenceOk = signal<string | null>(null);

  protected readonly holdCustodians = signal('');
  protected readonly holdFrom = signal('');
  protected readonly holdTo = signal('');
  protected readonly holdTerms = signal('');
  protected readonly placing = signal(false);
  protected readonly holdFailure = signal<Failure | null>(null);
  protected readonly holdOk = signal<string | null>(null);

  protected readonly transitioning = signal(false);
  protected readonly transitionFailure = signal<Failure | null>(null);

  protected readonly checkId = signal('');
  protected readonly checking = signal(false);
  protected readonly checkResult = signal<boolean | null>(null);
  protected readonly checkFailure = signal<Failure | null>(null);

  // ------------------------------------------------------------ actions

  protected select(id: string): void {
    this.protectedPage.set(0);
    this.selected.set(id);
    this.clearMessages();
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
          this.createOk.set(`Case "${created.name}" opened as ${created.status}.`);
          this.newName.set('');
          this.newDescription.set('');
          this.cases.reload();
          this.caseStats.reload();
          this.select(created.caseId);
        },
        error: (error: unknown) => {
          this.creating.set(false);
          this.createFailure.set(classify(error, describe('p4case')));
        },
      });
  }

  /** One step forward only. The service refuses anything else with a 409 naming from and to. */
  protected advance(): void {
    const target = this.nextStatus();
    const id = this.selected();
    if (!target || !id) {
      return;
    }
    this.transitionFailure.set(null);
    this.transitioning.set(true);

    this.api
      .transition(id, target)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.transitioning.set(false);
          this.detail.reload();
          this.cases.reload();
          this.caseStats.reload();
        },
        error: (error: unknown) => {
          this.transitioning.set(false);
          this.transitionFailure.set(classify(error, describe('p4case')));
        },
      });
  }

  /**
   * Accepts one custodian or several, space/comma-separated — the same splitting
   * {@link placeHold} already does for its own custodian field. Without this, typing
   * "cust-001,cust-002" here (a reasonable thing to try, since the hold form right below it
   * accepts exactly that) silently created one custodian row whose id was the literal string
   * "cust-001,cust-002" — never matches a real custodian, so any hold scoped to it resolves to
   * zero messages and fails.
   */
  protected addCustodian(): void {
    const ids = this.custodianId()
      .split(/[\s,]+/)
      .map((value) => value.trim())
      .filter(Boolean);
    const caseId = this.selected();
    if (ids.length === 0 || !caseId) {
      return;
    }
    this.custodianFailure.set(null);
    this.custodianOk.set(null);
    this.addingCustodian.set(true);

    forkJoin(ids.map((id) => this.api.addCustodian(caseId, id)))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.addingCustodian.set(false);
          this.custodianOk.set(
            ids.length === 1 ? `${ids[0]} is on this case.` : `${ids.length} custodians added to this case.`,
          );
          this.custodianId.set('');
          this.custodians.reload();
        },
        error: (error: unknown) => {
          this.addingCustodian.set(false);
          this.custodianFailure.set(classify(error, describe('p4case')));
          // Any custodian before the failing one in the list is already saved server-side;
          // reload so the form does not look like nothing happened.
          this.custodians.reload();
        },
      });
  }

  protected addEvidence(): void {
    const messageId = this.evidenceId().trim();
    if (!messageId || !this.selected()) {
      return;
    }
    this.evidenceFailure.set(null);
    this.evidenceOk.set(null);
    this.addingEvidence.set(true);

    this.api
      .addEvidence(this.selected(), messageId, 'MANUAL')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.addingEvidence.set(false);
          this.evidenceOk.set(`${messageId} filed as evidence.`);
          this.evidenceId.set('');
          this.evidence.reload();
        },
        error: (error: unknown) => {
          this.addingEvidence.set(false);
          this.evidenceFailure.set(classify(error, describe('p4case')));
        },
      });
  }

  protected removeEvidence(messageId: string): void {
    this.evidenceFailure.set(null);
    this.api
      .removeEvidence(this.selected(), messageId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.evidenceOk.set(`${messageId} removed from this case.`);
          this.evidence.reload();
        },
        error: (error: unknown) => this.evidenceFailure.set(classify(error, describe('p4case'))),
      });
  }

  /**
   * Place a hold, then watch it resolve.
   *
   * The 202 is an acceptance, not a protection: until the resolver has walked the scope and the
   * status reaches ACTIVE, this hold stops nothing. Polling is the honest way to show that.
   */
  protected placeHold(): void {
    const caseId = this.selected();
    const custodians = this.holdCustodians()
      .split(/[\s,]+/)
      .map((value) => value.trim())
      .filter(Boolean);
    if (!caseId || custodians.length === 0) {
      return;
    }
    this.holdFailure.set(null);
    this.holdOk.set(null);
    this.placing.set(true);

    this.api
      .placeHold({
        caseId,
        custodians,
        dateFrom: toInstant(this.holdFrom()),
        dateTo: toInstant(this.holdTo()),
        searchTerms: this.holdTerms().trim() || null,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (hold) => {
          this.placing.set(false);
          this.holdOk.set(
            `Hold ${hold.holdId.slice(0, 8)}… accepted and resolving. It protects nothing until it reaches ACTIVE.`,
          );
          this.holdCustodians.set('');
          this.holdTerms.set('');
          this.holds.reload();
          this.follow(hold.holdId);
        },
        error: (error: unknown) => {
          this.placing.set(false);
          this.holdFailure.set(classify(error, describe('p4hold')));
        },
      });
  }

  private follow(holdId: string): void {
    timer(1_000, 2_000)
      .pipe(
        switchMap(() => this.api.hold(holdId)),
        takeWhile((hold) => hold.status === 'RESOLVING', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => undefined,
        error: () => undefined,
        complete: () => {
          this.holds.reload();
          this.holdStats.reload();
          this.holdCount.reload();
        },
      });
  }

  protected release(hold: HoldEntity): void {
    this.holdFailure.set(null);
    this.api
      .release(hold.holdId, 'released from the case page')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.holdOk.set(`Hold ${hold.holdId.slice(0, 8)}… released.`);
          this.holds.reload();
          this.holdStats.reload();
          this.holdCount.reload();
        },
        error: (error: unknown) => this.holdFailure.set(classify(error, describe('p4hold'))),
      });
  }

  protected checkHold(): void {
    const id = this.checkId().trim();
    if (!id) {
      return;
    }
    this.checkFailure.set(null);
    this.checkResult.set(null);
    this.checking.set(true);
    this.api
      .checkHold(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.checking.set(false);
          this.checkResult.set(result.held);
        },
        error: (error: unknown) => {
          this.checking.set(false);
          this.checkFailure.set(classify(error, describe('p4hold')));
        },
      });
  }

  protected holdStatusClass(status: HoldEntity['status']): string {
    if (status === 'ACTIVE') {
      return 'tag tag--ok';
    }
    if (status === 'RESOLVING') {
      return 'tag tag--busy';
    }
    return status === 'FAILED' ? 'tag tag--danger' : 'tag';
  }

  protected caseStatusClass(status: CaseStatus): string {
    if (status === 'ACTIVE') {
      return 'tag tag--ok';
    }
    return status === 'UNDER_REVIEW' ? 'tag tag--warn' : 'tag';
  }

  private clearMessages(): void {
    this.custodianOk.set(null);
    this.custodianFailure.set(null);
    this.evidenceOk.set(null);
    this.evidenceFailure.set(null);
    this.holdOk.set(null);
    this.holdFailure.set(null);
    this.transitionFailure.set(null);
  }
}

function toInstant(value: string): string | null {
  if (!value.trim()) {
    return null;
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}
