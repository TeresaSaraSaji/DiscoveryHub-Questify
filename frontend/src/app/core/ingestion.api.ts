import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap, timer } from 'rxjs';
import { takeWhile } from 'rxjs/operators';
import { baseUrl } from './api-config';
import { RetentionMode, UploadJob } from './models';

/**
 * P1 Ingestion on :8081 — the only writer into the corpus.
 *
 * One file per request: `POST /messages/upload` takes a single `file` part, so a folder is
 * uploaded as a sequence of calls rather than one batch. That is P1's shape and not worth
 * changing for this — dedupe, validation, attachment integrity and audit are applied per message
 * either way, and a per-file result is what a reader of the panel actually wants when one file in
 * a folder is malformed.
 *
 * Always asynchronous. The synchronous form is honest for a small file and useless for the
 * corpus — 12,000 messages take around 75 seconds, longer than a browser will wait — and one code
 * path that works for both beats two that each work for half the cases. A small file simply
 * completes before the first poll.
 */
@Injectable({ providedIn: 'root' })
export class IngestionApi {
  private readonly http = inject(HttpClient);

  private url(path: string): string {
    return `${baseUrl('p1')}${path}`;
  }

  /**
   * Hand one file to P1 and get back the job to poll.
   *
   * @param retentionMode `DEMO` labels every message in this file for a short, per-message
   *                      retention override; `NORMAL` leaves it to the type-based policy.
   */
  upload(file: File, retentionMode: RetentionMode): Observable<UploadJob> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<UploadJob>(this.url('/messages/upload'), body, {
      params: { async: true, retentionMode },
      // Spooling a 10 MB file to disk is the slow part of this call; the response itself is a job
      // id. Generous, because failing the request while P1 is still reading the body would report
      // an error for an upload that then succeeds.
      timeout: 120_000,
    });
  }

  job(jobId: string): Observable<UploadJob> {
    return this.http.get<UploadJob>(this.url(`/messages/uploads/${encodeURIComponent(jobId)}`));
  }

  /**
   * Poll one job to a terminal state, emitting each snapshot on the way.
   *
   * Every second, which is fast enough to look alive and slow enough not to matter next to the
   * work being reported on. The terminal snapshot is emitted before completing — that is the one
   * carrying the counts.
   */
  watchJob(jobId: string): Observable<UploadJob> {
    return timer(0, 1_000).pipe(
      switchMap(() => this.job(jobId)),
      takeWhile((job) => job.status === 'RUNNING', true),
    );
  }
}
