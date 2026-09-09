import { Component, inject } from '@angular/core';
import { HealthService, ServiceHealth } from '../core/health.service';

/**
 * Which of the five services are actually up.
 *
 * Worth the header space in this system specifically. Both P2 and P2.2 fail *closed* on an
 * unreachable P4: a sweep that could not verify holds skips every candidate and reports success,
 * which is indistinguishable from holds working correctly. The README says as much — "a sweep that
 * skipped everything is not proof that holds work". This strip is the first thing to check.
 */
@Component({
  selector: 'app-service-status',
  template: `
    <div class="status">
      @for (health of health.all(); track health.service.key) {
        <span
          class="status__item"
          [class.status__item--up]="health.state === 'up'"
          [class.status__item--down]="health.state === 'down'"
          [title]="tooltip(health)"
        >
          <span class="status__dot"></span>{{ health.service.code }}
        </span>
      }
      <button type="button" class="link status__recheck" (click)="health.probeAll()">
        Re-check
      </button>
    </div>
  `,
})
export class ServiceStatus {
  protected readonly health = inject(HealthService);

  protected tooltip(health: ServiceHealth): string {
    const where = `${health.service.name} — ${health.service.defaultBaseUrl}`;
    if (health.state === 'up') {
      return `${where}\nUP${health.latencyMs === null ? '' : ` (${health.latencyMs} ms)`}`;
    }
    if (health.state === 'down') {
      return health.service.implemented
        ? `${where}\nDOWN — not running, or not reachable from the browser`
        : `${where}\nDOWN — this service has not been written yet`;
    }
    return `${where}\nnot checked yet`;
  }
}
