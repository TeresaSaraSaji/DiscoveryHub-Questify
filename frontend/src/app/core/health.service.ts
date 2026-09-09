import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, Signal, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval } from 'rxjs';
import { SERVICES, ServiceDescriptor, ServiceKey, baseUrl } from './api-config';

export type HealthState = 'unknown' | 'up' | 'down';

export interface ServiceHealth {
  readonly service: ServiceDescriptor;
  readonly state: HealthState;
  /** Round-trip of the last successful probe, for the tooltip. */
  readonly latencyMs: number | null;
  readonly checkedAt: Date | null;
}

const PROBE_INTERVAL_MS = 15_000;
const PROBE_TIMEOUT_MS = 3_000;

/**
 * Liveness of all five services, polled independently.
 *
 * Deliberately advisory: no page waits on it, and no request is skipped because of it. A panel
 * that only fetched when this said UP would go blank for fifteen seconds every time a probe was
 * unlucky, and would still have to handle the request failing anyway. It exists so the header can
 * answer "which of these is even running?" — a question this system's users ask constantly,
 * because both P2 and P2.2 fail *closed* and a sweep that deleted nothing looks identical to a
 * sweep that could not reach P4.
 */
@Injectable({ providedIn: 'root' })
export class HealthService {
  private readonly http = inject(HttpClient);
  private readonly states = signal(initialStates());

  readonly all: Signal<ServiceHealth[]> = computed(() => {
    const current = this.states();
    return SERVICES.map((service) => current[service.key]);
  });

  constructor() {
    this.probeAll();
    interval(PROBE_INTERVAL_MS)
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => this.probeAll());
  }

  health(key: ServiceKey): Signal<ServiceHealth> {
    return computed(() => this.states()[key]);
  }

  /** Fire all five probes at once; each settles on its own. */
  probeAll(): void {
    for (const service of SERVICES) {
      this.probe(service);
    }
  }

  private probe(service: ServiceDescriptor): void {
    const startedAt = performance.now();
    this.http
      .get(`${baseUrl(service.key)}${service.healthPath}`, { timeout: PROBE_TIMEOUT_MS })
      .subscribe({
        next: () => this.record(service.key, 'up', Math.round(performance.now() - startedAt)),
        // Any failure is DOWN, including a 503 from a service that is up but unhealthy: for the
        // purpose of "can I use this page?", an unhealthy service and an absent one are the same.
        error: () => this.record(service.key, 'down', null),
      });
  }

  private record(key: ServiceKey, state: HealthState, latencyMs: number | null): void {
    this.states.update((current) => ({
      ...current,
      [key]: { service: current[key].service, state, latencyMs, checkedAt: new Date() },
    }));
  }
}

function initialStates(): Record<ServiceKey, ServiceHealth> {
  const entries = SERVICES.map((service) => [
    service.key,
    { service, state: 'unknown' as HealthState, latencyMs: null, checkedAt: null },
  ]);
  return Object.fromEntries(entries) as Record<ServiceKey, ServiceHealth>;
}
