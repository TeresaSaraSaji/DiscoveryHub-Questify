import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { baseUrl } from './api-config';
import { BulkAddToCaseResponse, SavedSearch, SearchRequest, SearchResponse } from './models';

/**
 * Nothing on the search page loads until the user asks for something.
 *
 * A budget rather than a limit: 3s is about as long as someone will look at a spinner before
 * assuming the thing is broken, and Elasticsearch over a 12,000-message index answers in
 * milliseconds. If a search does take longer, the honest outcome is an error the user can retry,
 * not a spinner that eventually resolves after they have given up and clicked again.
 */
export const SEARCH_TIMEOUT_MS = 3_000;

/**
 * P3 Search on :8083, over the Elasticsearch `communications` index.
 *
 * There is deliberately no `*Resource` method here, unlike every other client in this app. A
 * resource loads as soon as it is created, and this service must not: the index holds the entire
 * corpus, and a screen that opens with every message on it has answered a question nobody asked.
 * P3 takes the same view and rejects a criterion-less query with a 400. So search is imperative —
 * it happens when the form is submitted, and only then.
 */
@Injectable({ providedIn: 'root' })
export class SearchApi {
  private readonly http = inject(HttpClient);

  private url(path: string): string {
    return `${baseUrl('p3')}${path}`;
  }

  /**
   * Run a search. POST rather than GET: the criteria are a structured object with tri-state
   * booleans, and P3 accepts both forms but only the body one round-trips a saved search
   * unchanged.
   */
  search(request: SearchRequest): Observable<SearchResponse> {
    return this.http.post<SearchResponse>(this.url('/search'), request, {
      timeout: SEARCH_TIMEOUT_MS,
    });
  }

  savedSearches(caseId?: string): Observable<SavedSearch[]> {
    return this.http.get<SavedSearch[]>(this.url('/search/saved'), {
      params: caseId ? { caseId } : {},
    });
  }

  saveSearch(name: string, request: SearchRequest, caseId: string | null): Observable<SavedSearch> {
    return this.http.post<SavedSearch>(this.url('/search/saved'), {
      name,
      caseId,
      request,
      createdBy: 'investigator',
    });
  }

  runSaved(id: string): Observable<SearchResponse> {
    return this.http.post<SearchResponse>(
      this.url(`/search/saved/${encodeURIComponent(id)}/run`),
      null,
      { timeout: SEARCH_TIMEOUT_MS },
    );
  }

  deleteSaved(id: string): Observable<void> {
    return this.http.delete<void>(this.url(`/search/saved/${encodeURIComponent(id)}`));
  }

  /**
   * File search results as evidence on a case.
   *
   * **Synchronous and confirmed.** P3 collects the matching ids and calls case-service to write
   * the evidence rows before answering, so `added` is what P4 created, not what P3 matched, and
   * the case's evidence list contains them the moment this returns.
   *
   * This used to publish an event that nothing consumed while reporting success, so the count here
   * described a write that never happened. If case-service is down the call now fails with a 502
   * rather than claiming the messages were filed.
   *
   * @param allResults false adds the current page only; true scrolls every match, capped at 10,000
   */
  addToCase(
    caseId: string,
    request: SearchRequest,
    allResults: boolean,
  ): Observable<BulkAddToCaseResponse> {
    return this.http.post<BulkAddToCaseResponse>(this.url('/search/add-to-case'), {
      caseId,
      allResults,
      request,
    });
  }
}

/** True when a request carries at least one criterion — the same rule P3 enforces with a 400. */
export function hasCriterion(request: SearchRequest): boolean {
  return Boolean(
    request.query?.trim() ||
    request.custodianIds?.length ||
    request.type ||
    request.from?.trim() ||
    request.labels?.length ||
    request.sentAfter ||
    request.sentBefore ||
    request.hasAttachment !== null ||
    request.onHold !== null,
  );
}
