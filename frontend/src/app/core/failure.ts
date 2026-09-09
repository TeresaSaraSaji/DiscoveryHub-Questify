/**
 * Turning a failed request into something the panel can say out loud.
 *
 * The distinction that matters to a user of this system is between *this service is not running*
 * and *this service answered and said no*. A single "something went wrong" collapses those two,
 * and in an eDiscovery UI they lead to opposite actions: start the service, or read the message.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { ServiceDescriptor } from './api-config';

export type FailureKind =
  'unreachable' | 'not-found' | 'conflict' | 'rejected' | 'server' | 'unknown';

export interface Failure {
  kind: FailureKind;
  status: number;
  /** One line, safe to render. */
  message: string;
  /** The server's own explanation, when it gave one. */
  detail?: string;
}

export function classify(error: unknown, service?: ServiceDescriptor): Failure {
  if (!(error instanceof HttpErrorResponse)) {
    return {
      kind: 'unknown',
      status: 0,
      message: error instanceof Error ? error.message : 'Unexpected error.',
    };
  }

  const detail = serverMessage(error);
  const who = service ? `${service.code} ${service.name}` : 'The service';

  // Status 0 is the browser refusing to tell us more: connection refused, DNS failure, a CORS
  // preflight that never got an answer, or a timeout. All of them mean "nothing answered".
  if (error.status === 0) {
    return {
      kind: 'unreachable',
      status: 0,
      message:
        service?.implemented === false
          ? `${who} is not implemented yet — no service is listening on ${service.defaultBaseUrl}.`
          : `${who} is not reachable. Is it running, and does it allow requests from this origin?`,
      detail,
    };
  }
  if (error.status === 404) {
    return { kind: 'not-found', status: 404, message: detail ?? 'Not found.', detail };
  }
  if (error.status === 409) {
    return {
      kind: 'conflict',
      status: 409,
      message: detail ?? 'Conflicts with the current state.',
      detail,
    };
  }
  if (error.status >= 400 && error.status < 500) {
    return {
      kind: 'rejected',
      status: error.status,
      message: detail ?? `Rejected (${error.status}).`,
      detail,
    };
  }
  return {
    kind: 'server',
    status: error.status,
    message: detail ?? `${who} failed with ${error.status}.`,
    detail,
  };
}

/** Spring's error body is `{ status, error, message, path }`; a plain string is also possible. */
function serverMessage(error: HttpErrorResponse): string | undefined {
  const body: unknown = error.error;
  if (typeof body === 'string' && body.trim() && !body.trim().startsWith('<')) {
    return body.trim();
  }
  if (body && typeof body === 'object') {
    const record = body as Record<string, unknown>;
    for (const field of ['message', 'detail', 'error']) {
      const value = record[field];
      if (typeof value === 'string' && value.trim()) {
        return value.trim();
      }
    }
  }
  return undefined;
}
