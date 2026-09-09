/**
 * Where each backend lives.
 *
 * The UI talks to five independent services on five ports, and it must keep working when any
 * subset of them is down. Nothing here is bundled at build time: `public/api-config.js` sets
 * `window.discoveryhubApi` before the app boots, so a demo can be repointed at another host by
 * editing one file in `dist/` rather than rebuilding.
 */
export type ServiceKey = 'p1' | 'p2' | 'p3' | 'p4case' | 'p4hold' | 'p5' | 'p22';

export interface ServiceDescriptor {
  readonly key: ServiceKey;
  /** Short label used in the status bar, e.g. "P2.2". */
  readonly code: string;
  readonly name: string;
  readonly defaultBaseUrl: string;
  /** Actuator path polled for liveness. */
  readonly healthPath: string;
  /** False for services nobody has written yet, so the UI can say so instead of blaming itself. */
  readonly implemented: boolean;
}

/**
 * Seven ports, six deployables plus P4's two halves.
 *
 * P4 is deliberately two entries. case-service and hold-service are separate deployables with
 * separate databases, and the UI has to be able to say which of the two is down: a case list that
 * loads while every hold check fails is a real and confusing state, and one "P4" badge could not
 * express it.
 */

export const SERVICES: readonly ServiceDescriptor[] = [
  {
    key: 'p1',
    code: 'P1',
    name: 'Ingestion',
    defaultBaseUrl: 'http://localhost:8081',
    healthPath: '/actuator/health',
    implemented: true,
  },
  {
    key: 'p2',
    code: 'P2',
    name: 'Archive',
    defaultBaseUrl: 'http://localhost:8082',
    healthPath: '/actuator/health',
    implemented: true,
  },
  {
    key: 'p3',
    code: 'P3',
    name: 'Search',
    defaultBaseUrl: 'http://localhost:8083',
    healthPath: '/actuator/health',
    implemented: true,
  },
  {
    key: 'p4case',
    code: 'P4c',
    name: 'Case Management',
    defaultBaseUrl: 'http://localhost:8084',
    healthPath: '/actuator/health',
    implemented: true,
  },
  {
    key: 'p4hold',
    code: 'P4h',
    name: 'Legal Hold',
    defaultBaseUrl: 'http://localhost:8086',
    healthPath: '/actuator/health',
    implemented: true,
  },
  {
    key: 'p5',
    code: 'P5',
    name: 'Export & Audit',
    defaultBaseUrl: 'http://localhost:8085',
    healthPath: '/actuator/health',
    implemented: true,
  },
  {
    // 8087, not 8086: hold-service claimed 8086 while this service was on an unmerged branch.
    key: 'p22',
    code: 'P2.2',
    name: 'Disposition',
    defaultBaseUrl: 'http://localhost:8087',
    healthPath: '/actuator/health',
    implemented: true,
  },
];

declare global {
  interface Window {
    discoveryhubApi?: Partial<Record<ServiceKey, string>>;
  }
}

export function describe(key: ServiceKey): ServiceDescriptor {
  const found = SERVICES.find((s) => s.key === key);
  if (!found) {
    throw new Error(`unknown service: ${key}`);
  }
  return found;
}

/** Base URL for a service, with the runtime override applied and any trailing slash removed. */
export function baseUrl(key: ServiceKey): string {
  const override = globalThis.window?.discoveryhubApi?.[key];
  const url = override?.trim() || describe(key).defaultBaseUrl;
  return url.replace(/\/+$/, '');
}
