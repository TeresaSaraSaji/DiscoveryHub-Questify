/**
 * Wire types.
 *
 * P1, P2 and P2.2 shapes are transcribed from the Java they are serialised from. P4 and P5 shapes
 * come from the specifications in `services/disposition-service/DISPOSITION.md` and
 * `contracts/AuditEvent.java` — those services do not exist yet, so these are the contract the UI
 * holds them to rather than an observed response.
 */

export type MessageType = 'EMAIL' | 'CHAT';

export type DispositionStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export type DispositionOutcome =
  'DELETED' | 'DELETE_REQUESTED' | 'SKIPPED_HOLD' | 'WOULD_DELETE' | 'FAILED';

export const OUTCOMES: readonly DispositionOutcome[] = [
  'DELETED',
  'DELETE_REQUESTED',
  'SKIPPED_HOLD',
  'WOULD_DELETE',
  'FAILED',
];

/** Spring Data's `Page`, as it lands on the wire. */
export interface Page<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export const EMPTY_PAGE: Page<never> = {
  content: [],
  number: 0,
  size: 0,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

// ---------------------------------------------------------------- P1 ingestion

/**
 * Which retention rule the messages in one upload are kept under.
 *
 * `NORMAL` is the type-based period P2.2 holds (seven years for email, three for chat). `DEMO`
 * tags every message in the upload with `RetentionLabels.DEMO_RETENTION`, and P2 stamps a short
 * absolute deadline on those rows alone — so one batch can be shown going through disposition
 * without shortening retention for anything else of its type.
 *
 * Not a cosmetic choice: picking `DEMO` for real data is an instruction to destroy it within
 * minutes.
 */
export type RetentionMode = 'NORMAL' | 'DEMO';

export type IngestOutcome = 'ACCEPTED' | 'DUPLICATE' | 'REJECTED' | 'FAILED';

/** `IngestResult`. Serialised without nulls, hence the optional fields. */
export interface IngestResult {
  externalId?: string;
  messageId?: string;
  outcome: IngestOutcome;
  /** For a duplicate, which dedupe key matched; for a rejection, what was wrong with it. */
  reason?: string;
}

/**
 * `UploadResponse` — the outcome of one file. Counts are exact; `problems` carries only failures
 * and only the first few, with `problemsTruncated` saying so plainly.
 */
export interface UploadResponse {
  filename: string;
  totalMessages: number;
  accepted: number;
  duplicates: number;
  rejected: number;
  failed: number;
  problems?: IngestResult[];
  problemsTruncated: boolean;
}

/**
 * `UploadJob` — the state of one asynchronous upload.
 *
 * `processed` is a count, not a percentage: the total is unknown until the file has been read to
 * the end. Job state is held in memory by the instance that issued it and does not survive a
 * restart; re-running a lost upload is safe, because the second run reports duplicates and
 * ingests nothing.
 */
export interface UploadJob {
  jobId: string;
  filename: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  processed: number;
  startedAt: string;
  finishedAt?: string;
  result?: UploadResponse;
  error?: string;
}

// ---------------------------------------------------------------- P2.2 disposition

/**
 * `RetentionPolicyEntity`. Note the asymmetry: the response carries `periodSeconds`, while
 * `PUT /retention/policies/{type}` expects an ISO-8601 `period`. See `duration.ts`.
 */
export interface RetentionPolicy {
  messageType: MessageType;
  periodSeconds: number;
  updatedAt: string;
  updatedBy: string;
}

export interface DispositionRun {
  runId: string;
  startedAt: string;
  finishedAt: string | null;
  status: DispositionStatus;
  triggerSource: 'SCHEDULED' | 'MANUAL';
  dryRun: boolean;
  candidateCount: number;
  deletedCount: number;
  skippedHoldCount: number;
  failedCount: number;
  activeHoldCount: number;
  holdScopeAvailable: boolean;
  error: string | null;
}

export interface DispositionItem {
  id: number;
  runId: string;
  messageId: string;
  externalId: string;
  custodianId: string;
  messageType: MessageType;
  sentAt: string;
  outcome: DispositionOutcome;
  reason: string | null;
  blockingHoldId: string | null;
  blockingCaseId: string | null;
  occurredAt: string;
  settledAt: string | null;
}

/** `GET /disposition/runs/progress`, and each `progress` event on the SSE stream. */
export interface RunProgress {
  runId: string;
  status: DispositionStatus;
  dryRun: boolean;
  processed: number;
  total: number;
  deleted: number;
  skippedHold: number;
  failed: number;
  activeHolds: number | null;
  holdScopeAvailable: boolean;
  error: string | null;
  at: string;
  terminal: boolean;
  percent: number;
}

export interface DispositionStats {
  archivedMessages: number;
  deleteMode: string;
  holdCheckEnabled: boolean;
  holdCheckRequired: boolean;
  retentionPeriods: Record<string, string>;
  totalRuns: number;
  totalDeleted: number;
  totalSkippedByHold: number;
  lastRun: DispositionRun | null;
}

export interface CandidatePreview {
  batchSize: number;
  candidatesInNextSweep: number;
  cutoffs: Record<string, string>;
  activeHolds: number;
  holdScopeAvailable: boolean;
  protectedByHoldFlag: number;
  protectedByHoldScope: number;
  protectedByCaseEvidence: number;
  protectedByHold: number;
  wouldBeDeleted: number;
}

// ---------------------------------------------------------------- P2 archive

export interface ArchiveStats {
  totalMessages: number;
  onHold: number;
}

export interface ArchivedMessage {
  messageId: string;
  externalId: string;
  source: string;
  type: MessageType;
  custodianId: string;
  from: string;
  to: string[];
  cc: string[];
  subject: string;
  body: string;
  sentAt: string;
  threadId: string | null;
  labels: string[];
}

// ---------------------------------------------------------------- P3 search

export type SortBy = 'RELEVANCE' | 'DATE';
export type SortDirection = 'ASC' | 'DESC';

/**
 * `POST /search` body.
 *
 * P3 refuses a request with no criterion at all with a 400, which is the same rule this UI wants:
 * the index holds the whole corpus, and a screen that opens with 12,000 messages on it has
 * answered a question nobody asked.
 */
export interface SearchRequest {
  query?: string | null;
  custodianIds?: string[];
  type?: MessageType | null;
  /** Sender address. Distinct from `custodianIds`, which is the mailbox owner. */
  from?: string | null;
  sentAfter?: string | null;
  sentBefore?: string | null;
  labels?: string[];
  /** Tri-state: null is no filter at all, not false. */
  hasAttachment?: boolean | null;
  onHold?: boolean | null;
  page?: number;
  /** Asking for more than 100 is silently clamped to 100 by the service. */
  size?: number;
  sortBy?: SortBy;
  sortDirection?: SortDirection;
}

/** Not a Spring `Page` — P3 returns its own record, with the elapsed time it took. */
export interface SearchResponse {
  results: SearchResult[];
  /** Total hits for the whole query, not this page. */
  total: number;
  page: number;
  size: number;
  tookMs: number;
}

export interface SearchResult {
  messageId: string;
  externalId: string | null;
  custodianId: string | null;
  from: string | null;
  to: string[] | null;
  subject: string | null;
  sentAt: string | null;
  /** Window around the first body match. Contains `<em>` markup from Elasticsearch. */
  snippet: string;
  highlights: string[] | null;
  score: number;
  onHold: boolean;
  attachmentCount: number;
  attachmentFilenames: string[] | null;
}

export interface SavedSearch {
  id: string;
  name: string;
  caseId: string | null;
  /** The serialised `SearchRequest`, replayed by `POST /search/saved/{id}/run`. */
  requestJson: string;
  createdBy: string | null;
  createdAt: string;
}

/** One executed search, recorded by P3 as a side effect of answering it. */
export interface SearchHistoryEntry {
  id: string;
  /** The free-text part of the request, null for a pure filter search. */
  query: string | null;
  /** The serialised `SearchRequest`, replayable by parsing it back into the form. */
  requestJson: string;
  /** Hits at the time it ran; a re-run may answer differently. */
  totalHits: number;
  tookMs: number;
  executedAt: string;
}

export interface BulkAddToCaseResponse {
  caseId: string;
  /** Messages the search matched. */
  matched: number;
  /** Evidence rows case-service actually created. Differs from `matched` when some were already filed. */
  added: number;
  alreadyPresent: number;
  messageIds: string[];
  truncated: boolean;
}

// ---------------------------------------------------------------- P4 case management

export type CaseStatus = 'DRAFT' | 'ACTIVE' | 'UNDER_REVIEW' | 'CLOSED';
export type MatterType = 'INVESTIGATION' | 'LITIGATION' | 'REGULATORY_INQUIRY';
export type EvidenceSource = 'MANUAL' | 'SEARCH';

export const CASE_STATUSES: readonly CaseStatus[] = ['DRAFT', 'ACTIVE', 'UNDER_REVIEW', 'CLOSED'];
export const MATTER_TYPES: readonly MatterType[] = [
  'INVESTIGATION',
  'LITIGATION',
  'REGULATORY_INQUIRY',
];

/** Forward only, one step at a time. The service refuses anything else with a 409. */
export const NEXT_STATUS: Readonly<Record<CaseStatus, CaseStatus | null>> = {
  DRAFT: 'ACTIVE',
  ACTIVE: 'UNDER_REVIEW',
  UNDER_REVIEW: 'CLOSED',
  CLOSED: null,
};

export interface CaseEntity {
  caseId: string;
  name: string;
  description: string | null;
  matterType: MatterType;
  owner: string;
  status: CaseStatus;
  createdAt: string;
  updatedAt: string | null;
  closedAt: string | null;
}

export interface CaseRequest {
  name: string;
  description: string | null;
  matterType: MatterType;
  owner: string;
}

export interface CaseStats {
  totalCases: number;
  /** ACTIVE + UNDER_REVIEW. */
  activeCases: number;
  closedCases: number;
}

export interface CaseCustodian {
  id: number;
  caseId: string;
  custodianId: string;
  addedAt: string;
}

export interface Evidence {
  id: number;
  caseId: string;
  messageId: string;
  source: EvidenceSource;
  searchRef: string | null;
  addedBy: string | null;
  addedAt: string;
}

export interface BulkEvidenceResult {
  requested: number;
  added: number;
  alreadyPresent: number;
}

// ---------------------------------------------------------------- P4 legal hold

export type HoldStatus = 'RESOLVING' | 'ACTIVE' | 'RELEASED' | 'FAILED';

export const HOLD_STATUSES: readonly HoldStatus[] = ['RESOLVING', 'ACTIVE', 'RELEASED', 'FAILED'];

/**
 * `HoldEntity` as it actually arrives.
 *
 * Note the absence of `custodians`. The entity stores them as a comma-separated column and exposes
 * them through `custodianList()`, which is a method and not a getter, so Jackson omits it — there
 * is no field on the wire at all. A hold's custodian scope is therefore not readable from the API
 * today, and the UI says so rather than rendering an empty list as "no custodians".
 */
export interface HoldEntity {
  holdId: string;
  caseId: string;
  dateFrom: string | null;
  dateTo: string | null;
  searchTerms: string | null;
  status: HoldStatus;
  placedAt: string;
  resolvedAt: string | null;
  releasedAt: string | null;
  releasedReason: string | null;
  /** Messages the resolver matched. 0 until the hold leaves RESOLVING. */
  messageCount: number;
  error: string | null;
}

export interface PlaceHoldRequest {
  caseId: string;
  custodians: string[];
  dateFrom: string | null;
  dateTo: string | null;
  searchTerms: string | null;
}

export interface HoldCheck {
  held: boolean;
}

export interface HoldStats {
  activeHolds: number;
  resolvingHolds: number;
  releasedHolds: number;
  failedHolds: number;
}

export interface CaseHoldCount {
  caseId: string;
  heldMessages: number;
}

// ---------------------------------------------------------------- P5 export & audit

export type ExportStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ExportJob {
  jobId: string;
  caseId: string | null;
  /** Verbatim JSON of the submitted request, as the service received it. */
  requestedScope: string;
  status: ExportStatus;
  itemCount: number;
  objectKey: string | null;
  /** SHA-256 of the .zip. What makes the package defensible. */
  packageSha256: string | null;
  packageSizeBytes: number | null;
  attempts: number;
  error: string | null;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

/** Exactly one scope: a non-empty `messageIds`, or a `custodianId` with an optional date range. */
export interface ExportRequest {
  caseId: string | null;
  messageIds: string[];
  custodianId: string | null;
  from: string | null;
  to: string | null;
}

/** `GET /exports/{jobId}/download` hands back an expiring link, not the bytes. */
export interface ExportDownload {
  jobId: string;
  url: string;
  packageSha256: string | null;
  sizeBytes: number;
}

/** `GET /exports/{jobId}/verify` — checksums re-derived from the package's own bytes (FR-6.5). */
export interface VerificationResult {
  valid: boolean;
  packageChecksumMatches: boolean;
  itemMismatches: string[];
  itemsChecked: number;
}

export interface AuditEntry {
  eventId: string;
  occurredAt: string;
  service: string;
  action: string;
  /** Free text, not an enum, on this service. SUCCESS / REFUSED / FAILURE by convention. */
  outcome: string;
  subjectType: string;
  subjectId: string | null;
  actor: string;
  correlationId: string | null;
  detail: Record<string, string>;
}
