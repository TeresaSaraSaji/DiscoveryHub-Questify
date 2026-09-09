import { Component, input } from '@angular/core';
import { Failure } from '../core/failure';

/**
 * The result of something the user did — a policy change, a triggered sweep, an export request.
 *
 * Separate from `Panel`'s failure box because the two are different claims. A panel failure says
 * "I could not read this"; this says "your instruction did not happen", and it has to stay on
 * screen next to the control that issued it rather than replacing the region.
 */
@Component({
  selector: 'app-alert',
  template: `
    @if (failure(); as problem) {
      <div class="alert alert--error" role="alert">
        <strong>{{ problem.kind === 'unreachable' ? 'Not sent.' : 'Rejected.' }}</strong>
        {{ problem.message }}
      </div>
    } @else if (success()) {
      <div class="alert alert--ok" role="status">{{ success() }}</div>
    }
  `,
})
export class Alert {
  readonly failure = input<Failure | null>(null);
  readonly success = input<string | null>(null);
}
