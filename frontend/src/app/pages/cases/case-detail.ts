import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { describe } from '../../core/api-config';
import { CasesApi } from '../../core/cases.api';
import { DispositionApi } from '../../core/disposition.api';
import { Failure, classify } from '../../core/failure';
import {
  CaseCustodian,
  CaseStatus,
  DispositionItem,
  EMPTY_PAGE,
  Evidence,
  HoldEntity,
  NEXT_STATUS,
  Page,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Paginator } from '../../shared/paginator';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * One case, on its own page — P4's two halves for a single matter, plus the proof from P2.2.
 *
 * Split out of the list because the two are different jobs: the list is for finding a matter, this
 * is for working on one, and it is eight panels deep. Sharing a page meant scrolling past the list
 * to reach any of it, and meant a case could not be linked to, which is the thing you want to
 * send someone.
 *
 * The case id arrives as a route parameter — `/cases/:caseId` — bound straight to the `caseId`
 * input by `withComponentInputBinding()`. Every resource below keys off it, so routing from one
 * case to another re-issues all of them without this component being recreated.
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
  selector: 'app-case-detail',
  imports: [Alert, DecimalPipe, FormsModule, Paginator, Panel, RouterLink, Since],
  templateUrl: './case-detail.html',
})
export class CaseDetail {
  private readonly api = inject(CasesApi);
  private readonly disposition = inject(DispositionApi);
  private readonly destroyRef = inject(DestroyRef);

  /** Route parameter: `/cases/:caseId`. */
  readonly caseId = input('');

  /** Everything here is about this one case, so the id is the only selection there is. */
  private readonly selected = this.caseId;

  // ------------------------------------------------------------ the case

  protected readonly detail = this.api.caseResource(this.selected);
  protected readonly custodians = this.api.custodiansResource(this.selected);
  protected readonly evidence = this.api.evidenceResource(this.selected);

  // Templates read these, never `.value()`. A resource throws from `value()` in its error state.
  protected readonly custodianRows = valueOr(this.custodians, [] as CaseCustodian[]);
  protected readonly evidenceRows = valueOr(this.evidence, [] as Evidence[]);

  /**
   * How much of the evidence list is on screen. The whole list arrives in one response — P4 does
   * not page it — but a matter can carry hundreds of rows, and a wall of ids buries the controls
   * under it. Ten at a time, grown by "Show more", same as the search results.
   */
  protected readonly evidenceVisible = signal(10);
  protected readonly visibleEvidence = computed(() =>
    this.evidenceRows().slice(0, this.evidenceVisible()),
  );
  protected readonly evidenceRemaining = computed(
    () => this.evidenceRows().length - this.visibleEvidence().length,
  );

  protected showMoreEvidence(): void {
    this.evidenceVisible.update((count) => count + 10);
  }

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

  protected readonly custodianId = signal('');
  protected readonly addingCustodian = signal(false);
  protected readonly custodianFailure = signal<Failure | null>(null);
  protected readonly custodianOk = signal<string | null>(null);

  // Still needed by Remove, which is the only write this panel makes now.
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
            ids.length === 1
              ? `${ids[0]} is on this case.`
              : `${ids.length} custodians added to this case.`,
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
}

function toInstant(value: string): string | null {
  if (!value.trim()) {
    return null;
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}
