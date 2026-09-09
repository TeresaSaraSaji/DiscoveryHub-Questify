import { HttpClient, httpResource } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { baseUrl } from './api-config';
import { ActiveHold, EvidenceHold, HoldCheck } from './models';

/**
 * P4 Case & Hold on :8084.
 *
 * **This service does not exist yet.** The three endpoints below are the contract specified in
 * `services/disposition-service/DISPOSITION.md` ("What P4 has to provide"), which P2 and P2.2
 * already call. Nothing here is stubbed or faked: every request goes to the real port and fails
 * honestly until someone ships the service, at which point this page starts working with no
 * change. `npm run mock` serves the same contract locally if you want to see the page populated.
 *
 * Note that on the backend, an unreachable P4 means *held* — both P2 and P2.2 fail closed and
 * refuse to delete. A UI that quietly showed "no holds" when P4 was down would tell the exact
 * opposite story to the one the system is acting on, so the panels distinguish "no holds" from
 * "could not ask".
 */
@Injectable({ providedIn: 'root' })
export class CasesApi {
  private readonly http = inject(HttpClient);

  private url(path: string): string {
    return `${baseUrl('p4')}${path}`;
  }

  /** Every hold in force, as scope — custodians and a date range, never expanded to messages. */
  activeHoldsResource() {
    return httpResource<ActiveHold[]>(() => this.url('/holds/active'), { defaultValue: [] });
  }

  /** The per-message guard. Anything other than `{"held": false}` is treated as held upstream. */
  checkHold(messageId: string): Observable<HoldCheck> {
    return this.http.get<HoldCheck>(this.url('/holds/check'), { params: { messageId } });
  }

  /**
   * "Of these messages, which are evidence in a case that is under hold?"
   *
   * A bulk POST rather than a query string because a sweep carries up to `batch-size` ids. Only
   * protected messages come back, so an empty array is the normal answer and does not mean the
   * call failed.
   */
  evidenceCheck(messageIds: string[]): Observable<EvidenceHold[]> {
    return this.http.post<EvidenceHold[]>(this.url('/holds/evidence-check'), { messageIds });
  }
}

/**
 * Holds on one case, filtered from `/holds/active` rather than by calling a per-case endpoint the
 * contract does not promise. A blank id means "all cases".
 */
export function holdsForCase(holds: readonly ActiveHold[], caseId: string): ActiveHold[] {
  const id = caseId.trim();
  return id ? holds.filter((hold) => hold.caseId === id) : [...holds];
}
