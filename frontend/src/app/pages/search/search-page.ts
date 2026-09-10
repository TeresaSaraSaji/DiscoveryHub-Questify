import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { describe } from '../../core/api-config';
import { CasesApi } from '../../core/cases.api';
import { Failure, classify } from '../../core/failure';
import {
  CaseEntity,
  CaseStatus,
  EMPTY_PAGE,
  MessageType,
  Page,
  SearchRequest,
  SearchResult,
} from '../../core/models';
import { SEARCH_TIMEOUT_MS, SearchApi, hasCriterion } from '../../core/search.api';
import { valueOr } from '../../core/resource-utils';
import { Alert } from '../../shared/alert';
import { Panel } from '../../shared/panel';
import { Since } from '../../shared/since.pipe';

/**
 * Search, and nothing but search.
 *
 * **The page opens empty and stays empty until asked.** That is the whole design. The index holds
 * the entire corpus, and a screen that greets you with 12,000 messages has answered a question
 * nobody asked, buried the one you came to ask, and spent a page of Elasticsearch throughput
 * doing it. P3 takes the same position and rejects a criterion-less query with a 400, so the
 * button is disabled until at least one filter is set rather than firing a request that is
 * guaranteed to fail.
 *
 * Every request carries a 3s deadline (`SEARCH_TIMEOUT_MS`). Not a performance target — a promise
 * to the user: within three seconds they get results or they get an error they can retry. What
 * they never get is a spinner that resolves after they have given up and clicked again.
 */
@Component({
  selector: 'app-search-page',
  imports: [Alert, FormsModule, Panel, Since],
  templateUrl: './search-page.html',
})
export class SearchPage {
  private readonly api = inject(SearchApi);
  private readonly casesApi = inject(CasesApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly timeoutSeconds = SEARCH_TIMEOUT_MS / 1000;

  // ------------------------------------------------------------ the form

  protected readonly query = signal('');
  protected readonly custodian = signal('');
  protected readonly sender = signal('');
  protected readonly type = signal<MessageType | ''>('');
  protected readonly sentAfter = signal('');
  protected readonly sentBefore = signal('');
  /** Tri-state, as three strings, because a checkbox cannot express "no filter". */
  protected readonly attachments = signal<'' | 'yes' | 'no'>('');
  protected readonly heldOnly = signal<'' | 'yes' | 'no'>('');
  protected readonly size = signal(20);

  /**
   * FR-3.4 asks for sortable results. Empty means "let the query decide": relevance when there is
   * a keyword to be relevant to, newest-first when there is not — sorting a pure filter by
   * relevance orders it by nothing at all, since every hit scores the same.
   */
  protected readonly sort = signal<'' | 'DATE' | 'RELEVANCE'>('');

  protected readonly page = signal(0);

  /** What would be sent right now. Also what gets filed to a case, so the two cannot diverge. */
  protected readonly request = computed<SearchRequest>(() => ({
    query: this.query().trim() || null,
    custodianIds: this.custodian().trim() ? [this.custodian().trim()] : [],
    type: this.type() || null,
    from: this.sender().trim() || null,
    sentAfter: toInstant(this.sentAfter()),
    sentBefore: toInstant(this.sentBefore()),
    labels: [],
    hasAttachment: triState(this.attachments()),
    onHold: triState(this.heldOnly()),
    page: this.page(),
    size: this.size(),
    sortBy: this.sort() || (this.query().trim() ? 'RELEVANCE' : 'DATE'),
    sortDirection: 'DESC',
  }));

  protected readonly ready = computed(() => hasCriterion(this.request()));

  // ------------------------------------------------------------ results

  protected readonly searching = signal(false);
  protected readonly failure = signal<Failure | null>(null);
  /** Null until the first search. Distinct from an empty result set, which is an answer. */
  protected readonly results = signal<SearchResult[] | null>(null);
  protected readonly total = signal(0);
  protected readonly tookMs = signal(0);
  protected readonly shownPage = signal(0);
  protected readonly shownSize = signal(20);

  protected readonly lastPage = computed(() => {
    const size = this.shownSize();
    return size > 0 ? Math.max(0, Math.ceil(this.total() / size) - 1) : 0;
  });

  protected readonly from = computed(() => this.shownPage() * this.shownSize() + 1);
  protected readonly to = computed(() =>
    Math.min(this.total(), (this.shownPage() + 1) * this.shownSize()),
  );

  // ------------------------------------------------------------ filing results to a case

  private readonly anyStatus = signal<CaseStatus | ''>('');
  private readonly casePage = signal(0);
  /**
   * The case list loads on sight, unlike the search itself. It is a short, bounded list that the
   * "file to case" control cannot be used without, and a dropdown that only populates after you
   * have clicked it is a worse trade than one request.
   */
  private readonly cases = this.casesApi.casesResource(this.anyStatus, this.casePage, 100);
  private readonly casePageValue = valueOr(this.cases, EMPTY_PAGE as Page<CaseEntity>);

  /** Closed cases are read-only: case-service refuses evidence on them with a 409. */
  protected readonly caseOptions = computed(() =>
    this.casePageValue().content.filter((item) => item.status !== 'CLOSED'),
  );

  protected readonly fileCaseId = signal('');
  protected readonly fileAll = signal(false);
  protected readonly filing = signal(false);
  protected readonly fileFailure = signal<Failure | null>(null);
  protected readonly fileOk = signal<string | null>(null);

  // ------------------------------------------------------------ actions

  protected submit(): void {
    if (!this.ready()) {
      return;
    }
    this.page.set(0);
    this.run();
  }

  protected changeSort(value: string): void {
    this.sort.set(value as '' | 'DATE' | 'RELEVANCE');
    // Only re-runs if something has already been searched. Changing the sort of an empty page
    // would fire a query the user never asked for, which is the thing this page does not do.
    if (this.results() !== null) {
      this.page.set(0);
      this.run();
    }
  }

  protected goToPage(next: number): void {
    this.page.set(Math.max(0, next));
    this.run();
  }

  private run(): void {
    const request = this.request();
    this.failure.set(null);
    this.fileOk.set(null);
    this.searching.set(true);

    this.api
      .search(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.searching.set(false);
          this.results.set(response.results);
          this.total.set(response.total);
          this.tookMs.set(response.tookMs);
          this.shownPage.set(response.page);
          // Echoed back rather than assumed: asking for more than 100 is clamped server-side, and
          // paging arithmetic against the number we asked for would then be wrong.
          this.shownSize.set(response.size);
        },
        error: (error: unknown) => {
          this.searching.set(false);
          this.results.set(null);
          this.failure.set(classify(error, describe('p3')));
        },
      });
  }

  protected reset(): void {
    this.query.set('');
    this.custodian.set('');
    this.sender.set('');
    this.type.set('');
    this.sentAfter.set('');
    this.sentBefore.set('');
    this.attachments.set('');
    this.heldOnly.set('');
    this.sort.set('');
    this.page.set(0);
    this.results.set(null);
    this.failure.set(null);
    this.fileOk.set(null);
    this.fileFailure.set(null);
  }

  /**
   * File the results as evidence on a case.
   *
   * Eventually consistent: P3 publishes an event and case-service writes the rows, so the message
   * says "queued" rather than "added". Claiming otherwise would contradict the case page a second
   * later.
   */
  protected fileToCase(): void {
    const caseId = this.fileCaseId().trim();
    if (!caseId || !this.ready()) {
      return;
    }
    this.fileFailure.set(null);
    this.fileOk.set(null);
    this.filing.set(true);

    this.api
      .addToCase(caseId, this.request(), this.fileAll())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.filing.set(false);
          this.fileOk.set(
            `${result.added} message${result.added === 1 ? '' : 's'} queued for ${result.caseId}` +
              (result.truncated ? ' — capped at 10,000 matches.' : '.') +
              ' Case-service applies these asynchronously, so the case may take a moment to catch up.',
          );
        },
        error: (error: unknown) => {
          this.filing.set(false);
          this.fileFailure.set(classify(error, describe('p3')));
        },
      });
  }

  /**
   * Split a snippet into plain and matched runs, so the template can mark up the matches itself.
   *
   * FR-3.3 asks for highlighted matching terms, and Elasticsearch already tells us where they are
   * by wrapping them in `<em>`. But what it returns is a fragment of a message body — user-supplied
   * text that arrived by email — so it must never be bound through `innerHTML`. That would be a
   * stored-XSS route from an ingested message straight into every investigator's browser. Angular's
   * sanitizer would probably strip a payload, but "probably" is not a security control, and the
   * escape hatch that makes such a binding render at all (`bypassSecurityTrustHtml`) is precisely
   * the wrong tool here.
   *
   * So the markers are parsed out here and each run is rendered as a text node, with the emphasis
   * applied by an element the template owns. Same result on screen; no markup from the corpus ever
   * reaches the DOM as markup.
   */
  protected segments(snippet: string): { text: string; match: boolean }[] {
    const out: { text: string; match: boolean }[] = [];
    let rest = snippet;
    let match = false;

    while (rest.length > 0) {
      // A snippet is a window cut out of a longer body, so Elasticsearch can hand back an opening
      // <em> whose closing tag fell outside the fragment. Looking only for the tag that would end
      // the current run keeps that case sane instead of losing the tail.
      const marker = match ? '</em>' : '<em>';
      const at = rest.indexOf(marker);
      if (at < 0) {
        out.push({ text: rest, match });
        break;
      }
      out.push({ text: rest.slice(0, at), match });
      rest = rest.slice(at + marker.length);
      match = !match;
    }

    return out.filter((segment) => segment.text.length > 0);
  }
}

/** A date input gives `YYYY-MM-DD`; the API wants an instant. */
function toInstant(value: string): string | null {
  if (!value.trim()) {
    return null;
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

function triState(value: '' | 'yes' | 'no'): boolean | null {
  return value === '' ? null : value === 'yes';
}
