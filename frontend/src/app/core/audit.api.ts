import { HttpClient, httpResource } from '@angular/common/http';
import { Injectable, Signal, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { baseUrl } from './api-config';
import { AuditEvent, AuditOutcome, EMPTY_PAGE, ExportJob, ExportRequest, Page } from './models';

/**
 * P5 Export & Audit on :8085 — defensible export packages, and the append-only chain of custody.
 *
 * **This service does not exist yet, and unlike P4 its HTTP surface is not specified anywhere in
 * the repository.** What is fixed is the event shape: `contracts/AuditEvent.java`, which every
 * service emits and P5 only appends. The endpoints below are therefore this page's *proposal*,
 * kept deliberately thin so that whoever writes P5 has little to disagree with:
 *
 * | | |
 * |---|---|
 * | `GET  /audit/events` | filter by `service`, `action`, `outcome`, `subjectId`, `correlationId`; paginated, newest first |
 * | `GET  /audit/events/{subjectId}` | every event about one subject, oldest first — the chain of custody for one message |
 * | `POST /exports` | request a package; **202** with a `QUEUED` job |
 * | `GET  /exports` | job history, paginated |
 * | `GET  /exports/{exportId}` | one job, for polling |
 * | `GET  /exports/{exportId}/package` | the bytes, once `COMPLETED` |
 *
 * Two things are asserted rather than assumed, because the alternative is not defensible:
 * an export job is **asynchronous** (a package over a 12,000-message corpus is not a request), and
 * a completed job carries a **`sha256`** of its manifest. If P5 lands with different paths, this
 * file is the only thing that changes.
 */
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly http = inject(HttpClient);

  private url(path: string): string {
    return `${baseUrl('p5')}${path}`;
  }

  // ------------------------------------------------------------ export (FR-6)

  exportsResource(page: Signal<number>, size = 10) {
    return httpResource<Page<ExportJob>>(
      () => ({ url: this.url('/exports'), params: { page: page(), size } }),
      { defaultValue: EMPTY_PAGE },
    );
  }

  requestExport(request: ExportRequest): Observable<ExportJob> {
    return this.http.post<ExportJob>(this.url('/exports'), request, { timeout: 10_000 });
  }

  export(exportId: string): Observable<ExportJob> {
    return this.http.get<ExportJob>(this.url(`/exports/${encodeURIComponent(exportId)}`));
  }

  /** The finished package. Kept as a plain URL so the browser downloads it rather than the app. */
  packageUrl(exportId: string): string {
    return this.url(`/exports/${encodeURIComponent(exportId)}/package`);
  }

  // ------------------------------------------------------------ audit (FR-7)

  auditResource(filter: Signal<AuditFilter>, page: Signal<number>, size = 25) {
    return httpResource<Page<AuditEvent>>(
      () => {
        const current = filter();
        const params: Record<string, string | number> = { page: page(), size };
        if (current.service) {
          params['service'] = current.service;
        }
        if (current.action.trim()) {
          params['action'] = current.action.trim();
        }
        if (current.outcome) {
          params['outcome'] = current.outcome;
        }
        if (current.subjectId.trim()) {
          params['subjectId'] = current.subjectId.trim();
        }
        return { url: this.url('/audit/events'), params };
      },
      { defaultValue: EMPTY_PAGE },
    );
  }

  /** Every event about one subject, oldest first — the chain of custody for a single message. */
  chainResource(subjectId: Signal<string>) {
    return httpResource<AuditEvent[]>(
      () => {
        const id = subjectId().trim();
        return id ? this.url(`/audit/events/${encodeURIComponent(id)}`) : undefined;
      },
      { defaultValue: [] },
    );
  }
}

export interface AuditFilter {
  service: string;
  action: string;
  outcome: AuditOutcome | '';
  subjectId: string;
}

export const EMPTY_AUDIT_FILTER: AuditFilter = {
  service: '',
  action: '',
  outcome: '',
  subjectId: '',
};
