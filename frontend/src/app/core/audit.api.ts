import { HttpClient, httpResource } from '@angular/common/http';
import { Injectable, Signal, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { baseUrl } from './api-config';
import {
  AuditEntry,
  EMPTY_PAGE,
  ExportDownload,
  ExportJob,
  ExportRequest,
  Page,
  VerificationResult,
} from './models';

/**
 * P5 Export & Audit on :8085.
 *
 * One service, two responsibilities, and they belong together: an export is only defensible if the
 * trail says who asked for it and what was in it. `/exports` builds packages into MinIO;
 * `/audit` is the read side of the append-only chain of custody — there are no write endpoints for
 * it anywhere, by design (FR-7.3).
 *
 * Three things the UI has to respect:
 *
 * - **Export is asynchronous.** `POST /exports` returns 202 and a `QUEUED` job; poll `job(id)`.
 * - **Download is a link, not bytes.** `/download` returns a presigned MinIO URL that expires in
 *   15 minutes, so the browser fetches the package directly and this app never proxies it.
 * - **The digest is worth re-deriving.** `/verify` recomputes every checksum from the package's own
 *   bytes rather than trusting what the job row claims, which is the only version of this that is
 *   evidence rather than metadata.
 */
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly http = inject(HttpClient);

  private url(path: string): string {
    return `${baseUrl('p5')}${path}`;
  }

  // ------------------------------------------------------------ export (FR-6)

  /** The 50 most recent jobs. A plain array, not a page — the service caps it server-side. */
  exportsResource() {
    return httpResource<ExportJob[]>(() => this.url('/exports'), { defaultValue: [] });
  }

  /**
   * @param request exactly one scope: a non-empty `messageIds`, or a `custodianId` with an
   *                optional date range. Neither is a 400.
   */
  requestExport(request: ExportRequest): Observable<ExportJob> {
    return this.http.post<ExportJob>(this.url('/exports'), request, { timeout: 10_000 });
  }

  job(jobId: string): Observable<ExportJob> {
    return this.http.get<ExportJob>(this.url(`/exports/${encodeURIComponent(jobId)}`));
  }

  /** Only a FAILED job can be retried; anything else is a 409. */
  retry(jobId: string): Observable<ExportJob> {
    return this.http.post<ExportJob>(this.url(`/exports/${encodeURIComponent(jobId)}/retry`), null);
  }

  download(jobId: string): Observable<ExportDownload> {
    return this.http.get<ExportDownload>(
      this.url(`/exports/${encodeURIComponent(jobId)}/download`),
    );
  }

  verify(jobId: string): Observable<VerificationResult> {
    return this.http.get<VerificationResult>(
      this.url(`/exports/${encodeURIComponent(jobId)}/verify`),
      // Re-reads and re-hashes the whole package. Slower than anything else in this app.
      { timeout: 30_000 },
    );
  }

  // ------------------------------------------------------------ audit (FR-7)

  /** Always newest first — the sort is fixed server-side, there is no client sort parameter. */
  auditResource(filter: Signal<AuditFilter>, page: Signal<number>, size = 25) {
    return httpResource<Page<AuditEntry>>(
      () => {
        const current = filter();
        const params: Record<string, string | number> = { page: page(), size };
        for (const key of ['service', 'action', 'outcome', 'subjectId'] as const) {
          const value = current[key].trim();
          if (value) {
            params[key] = value;
          }
        }
        return { url: this.url('/audit'), params };
      },
      { defaultValue: EMPTY_PAGE },
    );
  }
}

export interface AuditFilter {
  service: string;
  action: string;
  outcome: string;
  subjectId: string;
}

export const EMPTY_AUDIT_FILTER: AuditFilter = {
  service: '',
  action: '',
  outcome: '',
  subjectId: '',
};

/**
 * The filterable values, enumerated because the service matches them exactly.
 *
 * `AuditLogRepository.search` compares with `=`, not `like`, so a typed "p5" or "export.complete"
 * returns an empty page rather than an error — indistinguishable, in the UI, from a trail that
 * genuinely holds no such event. These are closed sets in the emitters, so they are offered as
 * choices instead. `subjectId` stays free text: it is a message, case, hold or job id.
 *
 * Each value is the literal a service writes, so it must stay in step with the `SERVICE` constant
 * and action strings in the emitters — `CaseAuditEvents`, `HoldAuditEvents`, `ExportEvents`,
 * `AuditEvents` (P2 and P2.2), `IngestService` and `SearchKafkaPublisher`.
 */
export interface AuditOption {
  value: string;
  label: string;
}

/**
 * Seven emitters, and the values are not uniform: P1 writes `ingestion` and P4's two services
 * write `CASE` and `HOLD` to tell themselves apart, while the rest use their `Pn` label. The
 * labels here are what that means to a reader; the values are what is stored.
 */
export const AUDIT_SERVICES: AuditOption[] = [
  { value: 'ingestion', label: 'P1 · Ingestion' },
  { value: 'P2', label: 'P2 · Storage & archive' },
  { value: 'P2.2', label: 'P2.2 · Disposition' },
  { value: 'P3', label: 'P3 · Search' },
  { value: 'CASE', label: 'P4 · Cases' },
  { value: 'HOLD', label: 'P4 · Legal hold' },
  { value: 'P5', label: 'P5 · Export & audit' },
];

/** `AuditEvent.Outcome`. A refusal is not a failure: the system declined on purpose. */
export const AUDIT_OUTCOMES: AuditOption[] = [
  { value: 'SUCCESS', label: 'SUCCESS' },
  { value: 'REFUSED', label: 'REFUSED — declined on purpose' },
  { value: 'FAILURE', label: 'FAILURE' },
];

/**
 * Grouped so a single dropdown of twenty-eight verbs stays readable.
 *
 * This is the full set, offered when no service is chosen. Narrowing it to one service is
 * {@link AUDIT_ACTIONS_BY_SERVICE}.
 */
export interface AuditActionGroup {
  label: string;
  actions: string[];
}

export const AUDIT_ACTION_GROUPS: AuditActionGroup[] = [
  {
    label: 'Messages',
    actions: [
      'message.ingested',
      'message.deduped',
      'message.rejected',
      'message.archived',
      'message.hold-updated',
    ],
  },
  {
    label: 'Cases & evidence',
    actions: [
      'case.created',
      'case.updated',
      'case.transitioned',
      'case.closed',
      'case.mutation-refused',
      'case.messages-added',
      'custodian.added',
      'evidence.added',
      'evidence.removed',
    ],
  },
  {
    label: 'Legal hold',
    actions: ['hold.placed', 'hold.released', 'hold.check', 'hold.failed'],
  },
  {
    label: 'Retention & disposition',
    actions: [
      'disposition.run-started',
      'disposition.run-completed',
      'disposition.run-failed',
      'disposition.deleted',
      'disposition.refused',
      'retention.policy-updated',
    ],
  },
  {
    label: 'Export',
    actions: ['export.requested', 'export.completed', 'export.downloaded', 'export.failed'],
  },
];

/**
 * What each service can write, so choosing one narrows the action list to verbs that service
 * actually emits and a combination that cannot match is not offered in the first place.
 *
 * Taken from the emitters rather than from the rows on hand: P2.2 declares `disposition.deleted`
 * and `disposition.refused` even though, with deletes currently routed through Kafka, P2 is what
 * records them. They are real code paths under another `delete-mode`, so they stay listed — a
 * dropdown built from `select distinct` would drop them the moment the table happened not to hold
 * one.
 *
 * Several verbs belong to two services: `message.deduped` to P1 and P2 (a duplicate caught at the
 * door, and one caught at the archive), and the disposition pair above. Leaving the service blank
 * searches across all of them.
 */
export const AUDIT_ACTIONS_BY_SERVICE: Record<string, string[]> = {
  ingestion: ['message.ingested', 'message.deduped', 'message.rejected'],
  P2: [
    'message.archived',
    'message.deduped',
    'message.hold-updated',
    'disposition.deleted',
    'disposition.refused',
    'disposition.run-failed',
  ],
  'P2.2': [
    'disposition.run-started',
    'disposition.run-completed',
    'disposition.run-failed',
    'disposition.deleted',
    'disposition.refused',
    'retention.policy-updated',
  ],
  P3: ['case.messages-added'],
  CASE: [
    'case.created',
    'case.updated',
    'case.transitioned',
    'case.closed',
    'case.mutation-refused',
    'custodian.added',
    'evidence.added',
    'evidence.removed',
  ],
  HOLD: ['hold.placed', 'hold.released', 'hold.check', 'hold.failed'],
  P5: ['export.requested', 'export.completed', 'export.downloaded', 'export.failed'],
};
