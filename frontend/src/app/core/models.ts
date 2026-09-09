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

// ---------------------------------------------------------------- P4 case & hold

/** `GET /holds/active` — a hold expressed as scope, never expanded to messages. */
export interface ActiveHold {
  holdId: string;
  caseId: string;
  caseName: string;
  /** `[]` means every custodian. */
  custodianIds: string[];
  from: string | null;
  to: string | null;
  terms?: string[];
}

/** `GET /holds/check?messageId=` */
export interface HoldCheck {
  held: boolean;
}

/** One entry from `POST /holds/evidence-check`. Only protected messages come back. */
export interface EvidenceHold {
  messageId: string;
  holdId: string;
  caseId: string;
  caseName: string;
}

// ---------------------------------------------------------------- P5 export & audit

export type ExportStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ExportJob {
  exportId: string;
  caseId: string;
  format: ExportFormat;
  status: ExportStatus;
  requestedAt: string;
  completedAt: string | null;
  messageCount: number;
  /** Manifest digest — what makes the package defensible. */
  sha256: string | null;
  sizeBytes: number | null;
  requestedBy: string;
  error: string | null;
}

export type ExportFormat = 'PST' | 'EML' | 'CSV' | 'JSON';

export const EXPORT_FORMATS: readonly ExportFormat[] = ['PST', 'EML', 'CSV', 'JSON'];

export interface ExportRequest {
  caseId: string;
  format: ExportFormat;
  includeAttachments: boolean;
  requestedBy: string;
}

/** `contracts/AuditEvent.java`. */
export interface AuditEvent {
  eventId: string;
  occurredAt: string;
  service: string;
  action: string;
  outcome: AuditOutcome;
  subjectType: string;
  subjectId: string;
  actor: string;
  correlationId: string;
  detail: Record<string, string>;
}

export type AuditOutcome = 'SUCCESS' | 'REFUSED' | 'FAILURE';

export const AUDIT_OUTCOMES: readonly AuditOutcome[] = ['SUCCESS', 'REFUSED', 'FAILURE'];
