import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AUDIT_ACTION_GROUPS,
  AUDIT_ACTIONS_BY_SERVICE,
  AUDIT_OUTCOMES,
  AUDIT_SERVICES,
  AuditApi,
  AuditFilter,
  EMPTY_AUDIT_FILTER,
} from '../../core/audit.api';
import { AuditEntry, EMPTY_PAGE, Page } from '../../core/models';
import { valueOr } from '../../core/resource-utils';
import { Paginator } from '../../shared/paginator';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * The chain of custody: every service's own record of what it did, newest first.
 *
 * Read-only, and not by omission — P5 exposes no write endpoint for the trail anywhere, which is
 * the whole enforcement mechanism for FR-7.3. An entry cannot be altered through an API that
 * offers no way to alter it.
 *
 * This is a page of its own rather than a panel under the exports it used to share a screen with.
 * The trail covers all seven services, not just P5's packages, and it is read to answer a question
 * ("why is this message still here?", "who exported that case?") rather than glanced at while
 * doing something else.
 */
@Component({
  selector: 'app-audit-page',
  imports: [FormsModule, Paginator, Panel, Since],
  templateUrl: './audit-page.html',
})
export class AuditPage {
  private readonly api = inject(AuditApi);

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
}
