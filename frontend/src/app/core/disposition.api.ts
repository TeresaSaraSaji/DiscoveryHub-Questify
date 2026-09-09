import { HttpClient, httpResource } from '@angular/common/http';
import { Injectable, Signal, inject } from '@angular/core';
import { Observable, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { baseUrl } from './api-config';
import {
  CandidatePreview,
  DispositionItem,
  DispositionOutcome,
  DispositionRun,
  DispositionStats,
  EMPTY_PAGE,
  MessageType,
  Page,
  RetentionPolicy,
  RunProgress,
} from './models';

/**
 * P2.2 Disposition on :8086 — retention policy, sweeps, and the ledger.
 *
 * The `*Resource` methods build an `httpResource`, so they must be called from an injection
 * context: a component field initialiser. Each returns its own independent loading/error state,
 * which is what lets one panel on a page fail while the rest of the page works.
 */
@Injectable({ providedIn: 'root' })
export class DispositionApi {
  private readonly http = inject(HttpClient);

  private url(path: string): string {
    return `${baseUrl('p22')}${path}`;
  }

  // ------------------------------------------------------------ retention (FR-5.1)

  policiesResource() {
    return httpResource<RetentionPolicy[]>(() => this.url('/retention/policies'), {
      defaultValue: [],
    });
  }

  /**
   * @param period ISO-8601, e.g. `P2555D`. The read side returns `periodSeconds`; the write side
   *               takes a duration string. Not a mistake on either end — see `duration.ts`.
   */
  updatePolicy(type: MessageType, period: string, actor: string): Observable<RetentionPolicy> {
    return this.http.put<RetentionPolicy>(
      this.url(`/retention/policies/${type}`),
      { period },
      { params: { actor } },
    );
  }

  // ------------------------------------------------------------ what the next sweep would do

  statsResource() {
    return httpResource<DispositionStats>(() => this.url('/disposition/stats'));
  }

  /**
   * The preview that matters before letting a sweep run: how many candidates, how many a hold
   * would save, and how many would actually be destroyed. Cheap enough to reload after every
   * policy change.
   */
  candidatesResource() {
    return httpResource<CandidatePreview>(() => this.url('/disposition/stats/candidates'));
  }

  // ------------------------------------------------------------ runs

  runsResource(page: Signal<number>, size = 10) {
    return httpResource<Page<DispositionRun>>(
      () => ({ url: this.url('/disposition/runs'), params: { page: page(), size } }),
      { defaultValue: EMPTY_PAGE },
    );
  }

  runItemsResource(
    runId: Signal<string | null>,
    outcome: Signal<DispositionOutcome | null>,
    page: Signal<number>,
    size = 25,
  ) {
    return httpResource<Page<DispositionItem>>(
      () => {
        const id = runId();
        // No run selected: returning undefined leaves the resource idle rather than firing a
        // request at `/runs/null` and rendering a 404 as if something had gone wrong.
        if (!id) {
          return undefined;
        }
        const filter = outcome();
        return {
          url: this.url(`/disposition/runs/${encodeURIComponent(id)}/items`),
          params: { page: page(), size, ...(filter ? { outcome: filter } : {}) },
        };
      },
      { defaultValue: EMPTY_PAGE },
    );
  }

  /**
   * Trigger a sweep.
   *
   * Always asynchronous from this UI. A run makes one call to P4 per candidate and is bounded by
   * `batch-size` (500), so the synchronous form holds the connection open long enough for the
   * browser to give up first — NFR-3 asks specifically that this not time out the UI. The 202
   * carries a QUEUED run id; progress arrives on the stream below.
   */
  triggerRun(options: { dryRun: boolean; actor: string }): Observable<DispositionRun> {
    return this.http.post<DispositionRun>(this.url('/disposition/runs'), null, {
      params: { dryRun: options.dryRun, async: true, actor: options.actor },
      // A queued run answers immediately; this only needs to outlast the accept.
      timeout: 10_000,
    });
  }

  progress(): Observable<RunProgress> {
    return this.http.get<RunProgress>(this.url('/disposition/runs/progress'));
  }

  /**
   * Live progress, with polling as the fallback.
   *
   * `EventSource` is the right transport and the wrong single point of failure: it is refused by
   * proxies that buffer, by a missing CORS header on the stream endpoint specifically, and by
   * anything that does not speak HTTP/1.1 chunked. Rather than show a dead progress bar in those
   * cases, a stream that fails before delivering anything falls back to polling
   * `/runs/progress` — the endpoint that exists for exactly this. The subscriber cannot tell the
   * difference, which is the point.
   */
  watchProgress(): Observable<RunProgress> {
    return new Observable<RunProgress>((observer) => {
      let source: EventSource | null = new EventSource(this.url('/disposition/runs/stream'));
      let delivered = false;
      let fallback: { unsubscribe(): void } | null = null;

      source.addEventListener('progress', (event) => {
        delivered = true;
        try {
          observer.next(JSON.parse((event as MessageEvent<string>).data) as RunProgress);
        } catch {
          // A malformed frame is not worth tearing the stream down for; the next one will be fine.
        }
      });

      source.addEventListener('error', () => {
        // The server closes the stream when a run reaches a terminal state, and the browser
        // surfaces that clean close as an error too. If anything was delivered, this is the
        // normal end of a run; if nothing was, the transport itself never worked.
        source?.close();
        source = null;
        if (delivered) {
          observer.complete();
          return;
        }
        fallback = pollProgress(() => this.progress()).subscribe(observer);
      });

      return () => {
        source?.close();
        fallback?.unsubscribe();
      };
    });
  }

  // ------------------------------------------------------------ the "what did holds save?" views

  messageHistory(messageId: string): Observable<DispositionItem[]> {
    return this.http.get<DispositionItem[]>(
      this.url(`/disposition/messages/${encodeURIComponent(messageId)}`),
    );
  }

  /**
   * Everything one case's holds have protected, across every run. Lives on P2.2, not P4 — so it
   * still answers when P4 is down, which is half the point of putting it on the case page.
   */
  protectedByCaseResource(caseId: Signal<string>, page: Signal<number>, size = 25) {
    return httpResource<Page<DispositionItem>>(
      () => {
        const id = caseId().trim();
        if (!id) {
          return undefined;
        }
        return {
          url: this.url(`/disposition/cases/${encodeURIComponent(id)}/protected`),
          params: { page: page(), size },
        };
      },
      { defaultValue: EMPTY_PAGE },
    );
  }
}

/** Poll every second until the run is terminal, then complete. */
function pollProgress(read: () => Observable<RunProgress>): Observable<RunProgress> {
  return timer(0, 1_000).pipe(
    switchMap(() => read()),
    // Emit the terminal snapshot, then stop: `inclusive` is what makes the final counts visible
    // instead of the last mid-run ones.
    takeWhile((progress) => !progress.terminal, true),
  );
}
