import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { describe } from '../../core/api-config';
import { CasesApi } from '../../core/cases.api';
import { Failure, classify } from '../../core/failure';
import { MATTER_TYPES, MatterType } from '../../core/models';
import { Alert } from '../../shared/alert';
import { Panel } from '../../shared/panel';

/**
 * Opening a matter, on its own route.
 *
 * This used to be a panel beside the case list, which put a form the user touches once per matter
 * permanently next to the list they read constantly, and made the two compete for the same glance.
 * A route instead of a dialog because the rest of this app is routed: `/cases/new` is linkable,
 * the browser's back button cancels it, and the form survives a reload.
 *
 * The page reads nothing — every field is a literal or a constant — so unlike the rest of this UI
 * it has no panel source and cannot be taken down by case-service being unreachable. It only finds
 * out on submit, and that failure belongs next to the button that caused it.
 */
@Component({
  selector: 'app-new-case-page',
  imports: [Alert, FormsModule, Panel, RouterLink],
  templateUrl: './new-case-page.html',
})
export class NewCasePage {
  private readonly api = inject(CasesApi);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly matterTypes = MATTER_TYPES;

  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly matterType = signal<MatterType>('INVESTIGATION');
  protected readonly owner = signal('investigator');

  protected readonly creating = signal(false);
  protected readonly failure = signal<Failure | null>(null);

  protected create(): void {
    const name = this.name().trim();
    if (!name) {
      return;
    }
    this.failure.set(null);
    this.creating.set(true);

    this.api
      .createCase({
        name,
        description: this.description().trim() || null,
        matterType: this.matterType(),
        owner: this.owner().trim() || 'investigator',
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        // Straight to the new case rather than back to a list with a green banner on it: the
        // next thing anyone does with a DRAFT matter is put custodians and evidence on it.
        next: (created) =>
          this.router.navigate(['/cases'], { queryParams: { caseId: created.caseId } }),
        error: (error: unknown) => {
          this.creating.set(false);
          this.failure.set(classify(error, describe('p4case')));
        },
      });
  }
}
