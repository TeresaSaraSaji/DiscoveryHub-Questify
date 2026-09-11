import { Location } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { describe } from '../../core/api-config';
import { CaseBroadcast } from '../../core/case-broadcast';
import { CasesApi } from '../../core/cases.api';
import { Failure, classify } from '../../core/failure';
import { MATTER_TYPES, MatterType } from '../../core/models';
import { Alert } from '../../shared/alert';
import { Panel } from '../../shared/panel';

/**
 * Opening a matter, on its own route, and the new case opens in a new tab.
 *
 * This used to be a panel beside the case list, which put a form touched once per matter
 * permanently next to the list read constantly, and made the two compete for the same glance.
 * A route instead of a dialog because the rest of this app is routed: `/cases/new` is linkable,
 * the browser's back button cancels it, and the form survives a reload.
 *
 * The page reads nothing — every field is a literal or a constant — so unlike the rest of this UI
 * it has no panel source and cannot be taken down by case-service being unreachable. It only finds
 * out on submit, and that failure belongs next to the button that caused it.
 *
 * **The tab is opened on the click, not on the response.** A `window.open` called from an HTTP
 * callback is not in a user gesture any more, and every browser's popup blocker stops it — the
 * case would be created and then nothing would appear, which is the worst of both outcomes. So
 * the click opens a blank tab immediately, while the gesture is still live, and the response only
 * points it at a URL. A create that fails closes the tab again rather than stranding an empty one.
 */
@Component({
  selector: 'app-new-case-page',
  imports: [Alert, FormsModule, Panel, RouterLink],
  templateUrl: './new-case-page.html',
})
export class NewCasePage {
  private readonly api = inject(CasesApi);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly broadcast = inject(CaseBroadcast);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly matterTypes = MATTER_TYPES;

  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly matterType = signal<MatterType>('INVESTIGATION');
  protected readonly owner = signal('investigator');

  protected readonly creating = signal(false);
  protected readonly failure = signal<Failure | null>(null);

  /** The case just opened, so the page can link to it when the popup blocker ate the tab. */
  protected readonly opened = signal<{ name: string; url: string } | null>(null);
  protected readonly blocked = signal(false);

  protected create(): void {
    const name = this.name().trim();
    if (!name || this.creating()) {
      return;
    }
    this.failure.set(null);
    this.opened.set(null);
    this.blocked.set(false);
    this.creating.set(true);

    // Claimed here, inside the click, for the popup blocker's benefit. `noopener` would return
    // null and leave nothing to redirect, so the reference is kept and `opener` severed below.
    const tab = window.open('', '_blank');

    this.api
      .createCase({
        name,
        description: this.description().trim() || null,
        matterType: this.matterType(),
        owner: this.owner().trim() || 'investigator',
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (created) => {
          this.creating.set(false);
          const url = this.location.prepareExternalUrl(
            this.router.serializeUrl(
              this.router.createUrlTree(['/cases'], { queryParams: { caseId: created.caseId } }),
            ),
          );
          this.opened.set({ name: created.name, url });

          if (tab) {
            // The new tab must not be able to script this one.
            tab.opener = null;
            tab.location.replace(url);
          } else {
            // Blocked. The case exists either way, so say so and offer the link — a click on it
            // is a fresh gesture and always allowed.
            this.blocked.set(true);
          }

          // Any other tab showing the case list is now one row behind.
          this.broadcast.announce();
          this.reset();
        },
        error: (error: unknown) => {
          this.creating.set(false);
          // No case, so no tab. Leaving a blank one open would be debris from a failed action.
          tab?.close();
          this.failure.set(classify(error, describe('p4case')));
        },
      });
  }

  /** Cleared so the next matter starts from a blank form rather than the last one's text. */
  private reset(): void {
    this.name.set('');
    this.description.set('');
    this.owner.set('investigator');
    this.matterType.set('INVESTIGATION');
  }
}
