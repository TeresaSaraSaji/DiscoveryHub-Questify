import { Component, Signal, computed, input } from '@angular/core';
import { ServiceKey, describe } from '../core/api-config';
import { Failure, classify } from '../core/failure';

/**
 * The subset of a `Resource` a panel needs. Structural rather than importing `ResourceRef`, so an
 * `httpResource` of any element type is accepted without the variance fight that `set(value: T)`
 * on `WritableResource` picks with `unknown`.
 */
export interface PanelSource {
  readonly status: Signal<'idle' | 'error' | 'loading' | 'reloading' | 'resolved' | 'local'>;
  readonly error: Signal<Error | undefined>;
  readonly isLoading: Signal<boolean>;
  reload(): boolean;
}

/**
 * One independently-loading region of a page.
 *
 * This component is the whole resilience story made visible. Every panel owns one request and
 * renders its own loading, error and retry state, so a page composed of six panels against three
 * services shows five of them working and one saying why it cannot. The alternative — a page-level
 * load that resolves everything before rendering — makes the weakest service the availability of
 * every page, which for this system means P4 not existing takes down the retention screen.
 *
 * There is deliberately no global error toast and no route resolver anywhere in this app.
 */
@Component({
  selector: 'app-panel',
  template: `
    <section class="panel" [class.panel--failed]="failure()">
      <header class="panel__head">
        <div class="panel__title">
          <h2>{{ heading() }}</h2>
          @if (hint()) {
            <p class="panel__hint">{{ hint() }}</p>
          }
        </div>
        <div class="panel__tools">
          @if (serviceCode()) {
            <span class="tag tag--service" [title]="serviceName()">{{ serviceCode() }}</span>
          }
          @if (source()) {
            @if (busy()) {
              <span class="tag tag--busy">loading</span>
            }
            <button
              type="button"
              class="btn btn--ghost btn--small"
              (click)="reload()"
              [disabled]="busy()"
            >
              Reload
            </button>
          }
        </div>
      </header>

      <div class="panel__body">
        @if (failure(); as problem) {
          <div class="failure" role="alert">
            <p class="failure__message">{{ problem.message }}</p>
            @if (problem.detail && problem.detail !== problem.message) {
              <p class="failure__detail">{{ problem.detail }}</p>
            }
            <p class="failure__meta">
              {{ problem.kind }}
              @if (problem.status) {
                · HTTP {{ problem.status }}
              }
            </p>
            <button type="button" class="btn btn--small" (click)="reload()" [disabled]="busy()">
              Try again
            </button>
          </div>
        } @else if (firstLoad()) {
          <div class="skeleton" aria-busy="true"><span></span><span></span><span></span></div>
        } @else {
          <ng-content />
        }
      </div>
    </section>
  `,
})
export class Panel {
  readonly heading = input.required<string>();
  /** The requirement or endpoint this panel answers for, shown small under the title. */
  readonly hint = input('');
  readonly source = input<PanelSource | undefined>(undefined);
  /** Which backend this panel talks to — drives the badge and the wording of a failure. */
  readonly service = input<ServiceKey | undefined>(undefined);

  readonly busy = computed(() => this.source()?.isLoading() ?? false);

  /** Loading with nothing to show yet. `reloading` keeps the old value on screen instead. */
  readonly firstLoad = computed(() => this.source()?.status() === 'loading');

  readonly failure = computed<Failure | null>(() => {
    const error = this.source()?.error();
    if (!error) {
      return null;
    }
    const key = this.service();
    return classify(error, key ? describe(key) : undefined);
  });

  readonly serviceCode = computed(() => {
    const key = this.service();
    return key ? describe(key).code : '';
  });

  readonly serviceName = computed(() => {
    const key = this.service();
    if (!key) {
      return '';
    }
    const service = describe(key);
    return `${service.name} · ${service.defaultBaseUrl}`;
  });

  reload(): void {
    this.source()?.reload();
  }
}
