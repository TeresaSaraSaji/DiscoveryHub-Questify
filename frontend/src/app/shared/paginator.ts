import { Component, computed, input, output } from '@angular/core';
import { Page } from '../core/models';

/** Page controls for a Spring Data `Page`. Nothing clever; every list in this app is paginated. */
@Component({
  selector: 'app-paginator',
  template: `
    @if (page().totalElements > 0) {
      <div class="paginator">
        <button
          type="button"
          class="btn btn--ghost btn--small"
          [disabled]="page().first || busy()"
          (click)="go.emit(page().number - 1)"
        >
          Previous
        </button>
        <span class="paginator__label">
          {{ from() }}–{{ to() }} of {{ page().totalElements }}
        </span>
        <button
          type="button"
          class="btn btn--ghost btn--small"
          [disabled]="page().last || busy()"
          (click)="go.emit(page().number + 1)"
        >
          Next
        </button>
      </div>
    }
  `,
})
export class Paginator {
  readonly page = input.required<Page<unknown>>();
  readonly busy = input(false);
  readonly go = output<number>();

  protected readonly from = computed(() => this.page().number * this.page().size + 1);
  protected readonly to = computed(() =>
    Math.min(this.page().totalElements, (this.page().number + 1) * this.page().size),
  );
}
