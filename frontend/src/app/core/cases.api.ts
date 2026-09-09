import { HttpClient, httpResource } from '@angular/common/http';
import { Injectable, Signal, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { baseUrl } from './api-config';
import {
  BulkEvidenceResult,
  CaseCustodian,
  CaseEntity,
  CaseHoldCount,
  CaseRequest,
  CaseStats,
  CaseStatus,
  EMPTY_PAGE,
  Evidence,
  EvidenceSource,
  HoldCheck,
  HoldEntity,
  HoldStats,
  HoldStatus,
  Page,
  PlaceHoldRequest,
} from './models';

/**
 * P4, both halves of it.
 *
 * case-service on :8084 owns the case lifecycle, custodians and evidence. hold-service on :8086
 * owns holds, scope resolution and the `/holds/check` guard that P2 and P2.2 call before deleting
 * anything. They are separate deployables with separate databases, so this client keeps them
 * separate too — every method says which one it is talking to, and the UI can report one down
 * while the other works.
 *
 * Two facts about hold-service that the UI has to be honest about:
 *
 * - **Placing a hold is asynchronous.** `POST /holds` returns 202 and a hold in `RESOLVING`; a
 *   worker resolves the scope and flips it to `ACTIVE` with a `messageCount`. Until then the hold
 *   protects nothing, and `messageCount` is 0 because it is unknown, not because it is zero.
 * - **A hold's custodian scope is not on the wire.** See `HoldEntity` in `models.ts`.
 */
@Injectable({ providedIn: 'root' })
export class CasesApi {
  private readonly http = inject(HttpClient);

  private caseUrl(path: string): string {
    return `${baseUrl('p4case')}${path}`;
  }

  private holdUrl(path: string): string {
    return `${baseUrl('p4hold')}${path}`;
  }

  // ------------------------------------------------------------ cases (:8084)

  casesResource(status: Signal<CaseStatus | ''>, page: Signal<number>, size = 20) {
    return httpResource<Page<CaseEntity>>(
      () => {
        const filter = status();
        return {
          url: this.caseUrl('/cases'),
          params: { page: page(), size, ...(filter ? { status: filter } : {}) },
        };
      },
      { defaultValue: EMPTY_PAGE },
    );
  }

  caseStatsResource() {
    return httpResource<CaseStats>(() => this.caseUrl('/cases/stats'));
  }

  caseResource(caseId: Signal<string>) {
    return httpResource<CaseEntity>(() => {
      const id = caseId().trim();
      return id ? this.caseUrl(`/cases/${encodeURIComponent(id)}`) : undefined;
    });
  }

  custodiansResource(caseId: Signal<string>) {
    return httpResource<CaseCustodian[]>(
      () => {
        const id = caseId().trim();
        return id ? this.caseUrl(`/cases/${encodeURIComponent(id)}/custodians`) : undefined;
      },
      { defaultValue: [] },
    );
  }

  evidenceResource(caseId: Signal<string>) {
    return httpResource<Evidence[]>(
      () => {
        const id = caseId().trim();
        return id ? this.caseUrl(`/cases/${encodeURIComponent(id)}/evidence`) : undefined;
      },
      { defaultValue: [] },
    );
  }

  createCase(request: CaseRequest): Observable<CaseEntity> {
    return this.http.post<CaseEntity>(this.caseUrl('/cases'), request);
  }

  /** Forward one step only. Anything else is a 409 with `from` and `to` in the problem detail. */
  transition(caseId: string, targetStatus: CaseStatus): Observable<CaseEntity> {
    return this.http.post<CaseEntity>(
      this.caseUrl(`/cases/${encodeURIComponent(caseId)}/transitions`),
      { targetStatus },
    );
  }

  /** Idempotent: re-adding an existing custodian returns the existing row. */
  addCustodian(caseId: string, custodianId: string): Observable<CaseCustodian> {
    return this.http.post<CaseCustodian>(
      this.caseUrl(`/cases/${encodeURIComponent(caseId)}/custodians`),
      { custodianId },
    );
  }

  addEvidence(caseId: string, messageId: string, source: EvidenceSource): Observable<Evidence> {
    return this.http.post<Evidence>(this.caseUrl(`/cases/${encodeURIComponent(caseId)}/evidence`), {
      messageId,
      source,
      searchRef: null,
    });
  }

  addEvidenceBatch(caseId: string, messageIds: string[]): Observable<BulkEvidenceResult> {
    return this.http.post<BulkEvidenceResult>(
      this.caseUrl(`/cases/${encodeURIComponent(caseId)}/evidence/batch`),
      { messageIds, source: 'SEARCH', searchRef: null },
    );
  }

  removeEvidence(caseId: string, messageId: string): Observable<void> {
    return this.http.delete<void>(
      this.caseUrl(
        `/cases/${encodeURIComponent(caseId)}/evidence/${encodeURIComponent(messageId)}`,
      ),
    );
  }

  // ------------------------------------------------------------ holds (:8086)

  /**
   * Holds for one case, or every hold in a status.
   *
   * With neither filter the service defaults to `status=ACTIVE`, which is the useful default: a
   * released hold protects nothing and a resolving one does not protect anything yet.
   */
  holdsResource(caseId: Signal<string>, status: Signal<HoldStatus | ''>) {
    return httpResource<HoldEntity[]>(
      () => {
        const id = caseId().trim();
        const filter = status();
        const params: Record<string, string> = {};
        if (id) {
          params['caseId'] = id;
        } else if (filter) {
          params['status'] = filter;
        }
        return { url: this.holdUrl('/holds'), params };
      },
      { defaultValue: [] },
    );
  }

  holdStatsResource() {
    return httpResource<HoldStats>(() => this.holdUrl('/holds/stats'));
  }

  caseHoldCountResource(caseId: Signal<string>) {
    return httpResource<CaseHoldCount>(() => {
      const id = caseId().trim();
      return id ? this.holdUrl(`/holds/case/${encodeURIComponent(id)}/count`) : undefined;
    });
  }

  /** 202 and a `RESOLVING` hold. Poll `hold(id)` until it reaches ACTIVE. */
  placeHold(request: PlaceHoldRequest): Observable<HoldEntity> {
    return this.http.post<HoldEntity>(this.holdUrl('/holds'), request);
  }

  hold(holdId: string): Observable<HoldEntity> {
    return this.http.get<HoldEntity>(this.holdUrl(`/holds/${encodeURIComponent(holdId)}`));
  }

  /** Idempotent on an already-released hold; 409 while it is still RESOLVING. */
  release(holdId: string, reason: string | null): Observable<HoldEntity> {
    return this.http.post<HoldEntity>(
      this.holdUrl(`/holds/${encodeURIComponent(holdId)}/release`),
      { reason },
    );
  }

  /** The guard the rest of the system trusts. Fails closed upstream: unreachable means held. */
  checkHold(messageId: string): Observable<HoldCheck> {
    return this.http.get<HoldCheck>(this.holdUrl('/holds/check'), { params: { messageId } });
  }
}
