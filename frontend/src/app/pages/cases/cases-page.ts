import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { describe } from '../../core/api-config';
import { ArchiveApi } from '../../core/archive.api';
import { CasesApi, holdsForCase } from '../../core/cases.api';
import { DispositionApi } from '../../core/disposition.api';
import { Failure, classify } from '../../core/failure';
import {
  ActiveHold,
  ArchivedMessage,
  DispositionItem,
  EMPTY_PAGE,
  EvidenceHold,
  Page,
} from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Paginator } from '../../shared/paginator';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * One case, seen through every service that has an opinion about it.
 *
 * A legal hold is not one fact in one place, and this page is arranged to make that obvious. The
 * top three panels ask P4 what is in force. The bottom three ask P2 and P2.2 what has already
 * *happened* because of it — and those still answer when P4 is down, which is the point: P4 not
 * existing yet does not make "what did this case's holds save from destruction?" unanswerable,
 * because P2.2 wrote that down at the time (FR-4.6).
 *
 * The distinction the panels are careful about: an empty hold list means *no holds*, and a failed
 * request means *we could not ask*. On the backend those are opposite — an unreachable P4 makes
 * both P2 and P2.2 treat every message as held — so a UI that rendered them the same way would
 * tell the exact inverse of what the system is doing.
 */
@Component({
  selector: 'app-cases-page',
  imports: [Alert, FormsModule, Paginator, Panel, Since],
  templateUrl: './cases-page.html',
})
export class CasesPage {
  private readonly cases = inject(CasesApi);
  private readonly disposition = inject(DispositionApi);
  private readonly archiveApi = inject(ArchiveApi);
  private readonly destroyRef = inject(DestroyRef);

  /** The case in focus. Everything below narrows to it, and blank means "all". */
  protected readonly caseId = signal('');

  // ------------------------------------------------------------ P4: what is in force

  protected readonly holds = this.cases.activeHoldsResource();

  /** Never `holds.value()` — that throws while the panel is showing P4's absence. */
  private readonly holdRows = valueOr(this.holds, [] as ActiveHold[]);

  protected readonly visibleHolds = computed(() => holdsForCase(this.holdRows(), this.caseId()));

  protected readonly caseNames = computed(() => {
    const names = new Map<string, string>();
    for (const hold of this.holdRows()) {
      names.set(hold.caseId, hold.caseName);
    }
    return [...names].map(([id, name]) => ({ id, name }));
  });

  // Per-message hold check
  protected readonly checkId = signal('');
  protected readonly checking = signal(false);
  protected readonly checkResult = signal<boolean | null>(null);
  protected readonly checkFailure = signal<Failure | null>(null);

  // Held-case evidence check
  protected readonly evidenceInput = signal('');
  protected readonly evidenceBusy = signal(false);
  protected readonly evidenceResult = signal<EvidenceHold[] | null>(null);
  protected readonly evidenceAsked = signal(0);
  protected readonly evidenceFailure = signal<Failure | null>(null);

  // ------------------------------------------------------------ P2.2 / P2: what already happened

  protected readonly protectedPage = signal(0);
  protected readonly protectedItems = this.disposition.protectedByCaseResource(
    this.caseId,
    this.protectedPage,
  );
  protected readonly protectedRows = valueOr(
    this.protectedItems,
    EMPTY_PAGE as Page<DispositionItem>,
  );

  protected readonly archiveStats = this.archiveApi.statsResource();

  protected readonly custodianId = signal('');
  protected readonly messagesPage = signal(0);
  protected readonly messages = this.archiveApi.messagesResource(
    this.custodianId,
    this.messagesPage,
  );
  protected readonly messageRows = valueOr(this.messages, EMPTY_PAGE as Page<ArchivedMessage>);

  // ------------------------------------------------------------ actions

  protected focusCase(id: string): void {
    this.protectedPage.set(0);
    this.caseId.set(id);
  }

  protected holdScope(hold: ActiveHold): string {
    const custodians =
      hold.custodianIds.length === 0
        ? 'every custodian'
        : `${hold.custodianIds.length} custodian${hold.custodianIds.length === 1 ? '' : 's'}`;
    const from = hold.from ? new Date(hold.from).toLocaleDateString() : 'the beginning';
    const to = hold.to ? new Date(hold.to).toLocaleDateString() : 'now';
    return `${custodians}, ${from} → ${to}`;
  }

  protected checkHold(): void {
    const id = this.checkId().trim();
    if (!id) {
      return;
    }
    this.checkFailure.set(null);
    this.checkResult.set(null);
    this.checking.set(true);
    this.cases
      .checkHold(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.checking.set(false);
          this.checkResult.set(result.held);
        },
        error: (error: unknown) => {
          this.checking.set(false);
          this.checkFailure.set(classify(error, describe('p4')));
        },
      });
  }

  protected runEvidenceCheck(): void {
    const ids = this.evidenceInput()
      .split(/[\s,]+/)
      .map((id) => id.trim())
      .filter(Boolean);
    if (ids.length === 0) {
      return;
    }
    this.evidenceFailure.set(null);
    this.evidenceResult.set(null);
    this.evidenceAsked.set(ids.length);
    this.evidenceBusy.set(true);
    this.cases
      .evidenceCheck(ids)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.evidenceBusy.set(false);
          this.evidenceResult.set(result);
        },
        error: (error: unknown) => {
          this.evidenceBusy.set(false);
          this.evidenceFailure.set(classify(error, describe('p4')));
        },
      });
  }

  /** Fill the evidence box from whatever the archive panel is currently showing. */
  protected takeIdsFromArchive(): void {
    const ids = this.messageRows().content.map((message) => message.messageId);
    if (ids.length > 0) {
      this.evidenceInput.set(ids.join('\n'));
    }
  }

  protected searchCustodian(id: string): void {
    this.messagesPage.set(0);
    this.custodianId.set(id);
  }
}
