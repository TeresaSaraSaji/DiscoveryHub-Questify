import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { SEARCH_TIMEOUT_MS } from '../core/search.api';
import { routes } from '../app.routes';
import { CasesPage } from './cases/cases-page';
import { Dashboard } from './dashboard/dashboard';
import { ExportAudit } from './export-audit/export-audit';
import { RetentionDisposition } from './retention-disposition/retention-disposition';
import { SearchPage } from './search/search-page';

function mount<T>(component: Type<T>): ComponentFixture<T> {
  TestBed.configureTestingModule({
    providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
  });
  const fixture = TestBed.createComponent(component);
  // Render, and let the resources' effects issue their requests. Deliberately not
  // `whenStable()`: that waits for pending HTTP, and with the testing backend nothing completes
  // until this test flushes it, so awaiting it here deadlocks.
  settle(fixture);
  return fixture;
}

/**
 * Run change detection and effects. Three passes because a resource takes one to start, one to
 * deliver and one to be rendered.
 */
function settle(fixture: ComponentFixture<unknown>): void {
  for (let pass = 0; pass < 3; pass++) {
    TestBed.tick();
    fixture.detectChanges();
  }
}

async function settleAsync(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let pass = 0; pass < 3; pass++) {
    settle(fixture);
    await Promise.resolve();
  }
  settle(fixture);
}

/** Fail every request in flight the way a service that is not running does. */
async function failEverything(fixture: ComponentFixture<unknown>): Promise<void> {
  const http = TestBed.inject(HttpTestingController);
  for (const request of http.match(() => true)) {
    request.error(new ProgressEvent('error'), { status: 0 });
  }
  await settleAsync(fixture);
}

const text = (fixture: ComponentFixture<unknown>) =>
  (fixture.nativeElement as HTMLElement).textContent ?? '';

/**
 * Every page mounted with every service down.
 *
 * This is the test that catches the real bug in this UI: `Resource.value()` throws in the error
 * state, Angular instantiates projected content eagerly, and a panel's table is therefore
 * evaluated while the panel is displaying a failure instead of it. Nothing about that shows up
 * when the services answer.
 */
describe('every page survives every service being down', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('renders the dashboard and names what is unreachable', async () => {
    const fixture = mount(Dashboard);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Welcome to DiscoveryHub');
    // The quick links are static, so navigation survives everything being down.
    expect(rendered).toContain('Search messages');
    expect(rendered).toContain('is not reachable');
    expect(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.failure').length,
    ).toBeGreaterThan(1);
  });

  it('renders Cases & Legal Hold', async () => {
    const fixture = mount(CasesPage);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Cases & Legal Hold');
    expect(rendered).toContain('Open a case');
    expect(rendered).toContain('is not reachable');
  });

  it('renders Exports & Audit', async () => {
    const fixture = mount(ExportAudit);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Exports & Audit Trail');
    expect(rendered).toContain('Audit trail');
    expect(rendered).toContain('is not reachable');
  });

  it('renders Retention & Disposition', async () => {
    const fixture = mount(RetentionDisposition);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Retention & Disposition');
    expect(rendered).toContain('Disposition run');
    expect(rendered).toContain('is not reachable');
  });
});

/**
 * The search page's whole design, asserted.
 *
 * The requirement is that it shows nothing until asked. That is not a styling choice — the index
 * holds the entire corpus, and P3 rejects a criterion-less query with a 400, so a page that
 * searched on load would be both noisy and broken.
 */
describe('search asks for nothing until it is asked', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('issues no search on load, and says so on screen', async () => {
    const fixture = mount(SearchPage);
    const http = TestBed.inject(HttpTestingController);

    // No query against the corpus. The history read is not one: it lists what has already been
    // asked, which is the page's own metadata, same as the case list for the "file to" control.
    http.expectNone((request) => request.url === 'http://localhost:8083/search');
    // The panel shows the last five searches, so that is all it asks for.
    http
      .expectOne(
        (request) =>
          request.url === 'http://localhost:8083/search/history' &&
          request.params.get('limit') === '5',
      )
      .flush([]);
    http
      .expectOne((request) => request.url === 'http://localhost:8084/cases')
      .flush({
        content: [],
        number: 0,
        size: 100,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true,
      });
    await settleAsync(fixture);

    expect(text(fixture)).toContain('Results appear here once you search');
  });

  it('keeps the button disabled until there is a criterion to send', async () => {
    const fixture = mount(SearchPage);
    await failEverything(fixture);

    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '.btn--primary',
    );
    expect(button?.disabled).toBe(true);
    expect(text(fixture)).toContain('Enter a keyword or set a filter');
  });

  it('searches once submitted, with a three second deadline', async () => {
    const fixture = mount(SearchPage);
    const page = fixture.componentInstance as unknown as { query: { set(v: string): void } };
    const http = TestBed.inject(HttpTestingController);
    http
      .match(() => true)
      .forEach((request) =>
        request.flush({
          content: [],
          number: 0,
          size: 100,
          totalElements: 0,
          totalPages: 0,
          first: true,
          last: true,
        }),
      );

    page.query.set('project atlas');
    settle(fixture);
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.btn--primary')
      ?.click();
    settle(fixture);

    const search = http.expectOne('http://localhost:8083/search');
    expect(search.request.method).toBe('POST');
    expect(search.request.body.query).toBe('project atlas');
    // The promise this page makes to the user: an answer or an error, inside three seconds.
    expect(search.request.timeout).toBe(SEARCH_TIMEOUT_MS);
    expect(SEARCH_TIMEOUT_MS).toBe(3_000);

    search.flush({
      results: [
        {
          messageId: 'msg-1',
          externalId: 'EXCH-1',
          custodianId: 'cust-004',
          from: 'a@example.com',
          to: ['b@example.com'],
          subject: 'Project Atlas update',
          sentAt: '2020-05-01T10:00:00Z',
          snippet: 'the <em>project atlas</em> rollout',
          highlights: null,
          score: 4.2,
          onHold: true,
          attachmentCount: 2,
          attachmentFilenames: ['a.pdf', 'b.pdf'],
        },
      ],
      total: 1,
      page: 0,
      size: 20,
      tookMs: 12,
    });
    await settleAsync(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Search results (1)');
    expect(rendered).toContain('Project Atlas update');
    expect(rendered).toContain('On hold');
    expect(rendered).toContain('Answered in 12 ms');

    // FR-3.3: the matched terms are highlighted, using our own <mark>, and the snippet's own text
    // survives intact around them.
    const host = fixture.nativeElement as HTMLElement;
    expect([...host.querySelectorAll('mark')].map((m) => m.textContent?.trim())).toEqual([
      'project atlas',
    ]);
    expect(rendered).toContain('the');
    expect(rendered).toContain('rollout');
    // Elasticsearch's <em> markers are consumed, not printed.
    expect(rendered).not.toContain('<em>');
  });

  /**
   * A snippet is a fragment of an ingested email. If it were bound through innerHTML, anyone who
   * could get a message into the corpus could run script in an investigator's browser.
   */
  it('renders a snippet containing markup as text, never as HTML', async () => {
    const fixture = mount(SearchPage);
    const page = fixture.componentInstance as unknown as { query: { set(v: string): void } };
    const http = TestBed.inject(HttpTestingController);
    http
      .match(() => true)
      .forEach((request) => request.error(new ProgressEvent('error'), { status: 0 }));

    page.query.set('payload');
    settle(fixture);
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.btn--primary')
      ?.click();
    settle(fixture);

    http.expectOne('http://localhost:8083/search').flush({
      results: [
        {
          messageId: 'msg-x',
          externalId: null,
          custodianId: null,
          from: null,
          to: null,
          subject: 'harmless',
          sentAt: null,
          snippet: 'before <em>payload</em> <img src=x onerror=alert(1)> after',
          highlights: null,
          score: 1,
          onHold: false,
          attachmentCount: 0,
          attachmentFilenames: null,
        },
      ],
      total: 1,
      page: 0,
      size: 20,
      tookMs: 3,
    });
    await settleAsync(fixture);

    const host = fixture.nativeElement as HTMLElement;
    // The tag from the message body is text on the page and not an element in the DOM.
    expect(host.querySelector('img')).toBeNull();
    expect(host.textContent).toContain('<img src=x onerror=alert(1)>');
    // The genuine highlight still works.
    expect(host.querySelector('mark')?.textContent?.trim()).toBe('payload');
  });

  /** FR-3.4: sortable results, and changing the sort must not search a page nobody has searched. */
  it('does not search when the sort changes before anything has been searched', async () => {
    const fixture = mount(SearchPage);
    await failEverything(fixture);
    const http = TestBed.inject(HttpTestingController);

    const page = fixture.componentInstance as unknown as { changeSort(v: string): void };
    page.changeSort('DATE');
    settle(fixture);

    http.expectNone((request) => request.url.includes('/search'));
  });

  it('re-runs with the chosen sort once there are results to re-order', async () => {
    const fixture = mount(SearchPage);
    const page = fixture.componentInstance as unknown as {
      query: { set(v: string): void };
      changeSort(v: string): void;
    };
    const http = TestBed.inject(HttpTestingController);
    http
      .match(() => true)
      .forEach((request) => request.error(new ProgressEvent('error'), { status: 0 }));

    page.query.set('atlas');
    settle(fixture);
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.btn--primary')
      ?.click();
    settle(fixture);
    // A keyword search defaults to relevance.
    const first = http.expectOne('http://localhost:8083/search');
    expect(first.request.body.sortBy).toBe('RELEVANCE');
    // Has to resolve: re-sorting is deliberately a no-op until there are results to re-order.
    first.flush({ results: [], total: 0, page: 0, size: 20, tookMs: 2 });
    await settleAsync(fixture);

    page.changeSort('DATE');
    settle(fixture);
    expect(http.expectOne('http://localhost:8083/search').request.body.sortBy).toBe('DATE');
  });

  it('reports an empty result as an answer, not a failure', async () => {
    const fixture = mount(SearchPage);
    const page = fixture.componentInstance as unknown as { query: { set(v: string): void } };
    const http = TestBed.inject(HttpTestingController);
    http
      .match(() => true)
      .forEach((request) => request.error(new ProgressEvent('error'), { status: 0 }));

    page.query.set('nothing matches this');
    settle(fixture);
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.btn--primary')
      ?.click();
    settle(fixture);

    http
      .expectOne('http://localhost:8083/search')
      .flush({ results: [], total: 0, page: 0, size: 20, tookMs: 4 });
    await settleAsync(fixture);

    expect(text(fixture)).toContain('That is an answer, not a failure');
  });
});

/**
 * The history panel reads what has already been asked and can put it back into the form. It must
 * never take the page down: a history failure stays in its own panel, and a re-run goes through
 * the same form and the same POST /search as a hand-typed query.
 */
describe('search history', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists recent searches with their answers', async () => {
    const fixture = mount(SearchPage);
    const http = TestBed.inject(HttpTestingController);

    http
      .expectOne((request) => request.url === 'http://localhost:8083/search/history')
      .flush([
        {
          id: 'h-1',
          query: 'project atlas',
          requestJson: '{"query":"project atlas","page":0,"size":20}',
          totalHits: 42,
          tookMs: 7,
          executedAt: '2026-09-10T12:00:00Z',
        },
        {
          id: 'h-2',
          query: null,
          requestJson: '{"custodianIds":["cust-004"],"page":0,"size":20}',
          totalHits: 3,
          tookMs: 2,
          executedAt: '2026-09-10T11:00:00Z',
        },
      ]);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Recent searches');
    expect(rendered).toContain('project atlas');
    // A pure filter search has no keyword to show, and must not render as blank.
    expect(rendered).toContain('(filters only)');
    expect(rendered).toContain('42');
  });

  it('re-runs an entry through the form, filters included', async () => {
    const fixture = mount(SearchPage);
    const http = TestBed.inject(HttpTestingController);

    http
      .expectOne((request) => request.url === 'http://localhost:8083/search/history')
      .flush([
        {
          id: 'h-1',
          query: 'fraud',
          requestJson:
            '{"query":"fraud","custodianIds":["cust-004"],"hasAttachment":true,"page":0,"size":20}',
          totalHits: 42,
          tookMs: 7,
          executedAt: '2026-09-10T12:00:00Z',
        },
      ]);
    await failEverything(fixture);

    const runButton = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ].find((button) => button.textContent?.trim() === 'Run again');
    expect(runButton).toBeTruthy();
    runButton!.click();
    settle(fixture);

    const search = http.expectOne('http://localhost:8083/search');
    expect(search.request.body.query).toBe('fraud');
    expect(search.request.body.custodianIds).toEqual(['cust-004']);
    expect(search.request.body.hasAttachment).toBe(true);
    // Recorded searches restart at the first page: the question is re-asked, not resumed.
    expect(search.request.body.page).toBe(0);
  });

  it('keeps a history failure inside its own panel', async () => {
    const fixture = mount(SearchPage);
    await failEverything(fixture);

    const rendered = text(fixture);
    // The search form is intact and usable...
    expect(rendered).toContain('Enter a keyword or set a filter');
    // ...and the history panel says what happened without emptying the page.
    expect(rendered).toContain('Recent searches');
  });

  it('drops recent searches under the keyword input on focus, and a pick re-runs', async () => {
    const fixture = mount(SearchPage);
    const http = TestBed.inject(HttpTestingController);

    http
      .expectOne((request) => request.url === 'http://localhost:8083/search/history')
      .flush([
        {
          id: 'h-1',
          query: 'project atlas',
          requestJson: '{"query":"project atlas","page":0,"size":20}',
          totalHits: 42,
          tookMs: 7,
          executedAt: '2026-09-10T12:00:00Z',
        },
      ]);
    await failEverything(fixture);

    const host = fixture.nativeElement as HTMLElement;
    const input = host.querySelector<HTMLInputElement>('input[name="query"]');
    expect(host.querySelector('.suggest__list')).toBeNull();

    input!.dispatchEvent(new Event('focus'));
    settle(fixture);

    const item = host.querySelector<HTMLButtonElement>('.suggest__item');
    expect(item?.textContent).toContain('project atlas');
    expect(item?.textContent).toContain('42 hits');

    item!.click();
    settle(fixture);

    const search = http.expectOne('http://localhost:8083/search');
    expect(search.request.body.query).toBe('project atlas');
    // Picking closes the list.
    expect(host.querySelector('.suggest__list')).toBeNull();
  });

  it('narrows the dropdown to what has been typed', async () => {
    const fixture = mount(SearchPage);
    const http = TestBed.inject(HttpTestingController);

    http
      .expectOne((request) => request.url === 'http://localhost:8083/search/history')
      .flush([
        {
          id: 'h-1',
          query: 'project atlas',
          requestJson: '{"query":"project atlas","page":0,"size":20}',
          totalHits: 42,
          tookMs: 7,
          executedAt: '2026-09-10T12:00:00Z',
        },
        {
          id: 'h-2',
          query: 'invoice fraud',
          requestJson: '{"query":"invoice fraud","page":0,"size":20}',
          totalHits: 9,
          tookMs: 3,
          executedAt: '2026-09-10T11:00:00Z',
        },
      ]);
    await failEverything(fixture);

    const page = fixture.componentInstance as unknown as {
      query: { set(v: string): void };
      historyOpen: { set(v: boolean): void };
    };
    page.historyOpen.set(true);
    page.query.set('invoice');
    settle(fixture);

    const items = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.suggest__item'),
    ].map((item) => item.textContent ?? '');
    expect(items).toHaveLength(1);
    expect(items[0]).toContain('invoice fraud');
  });

  it('clears the history on request', async () => {
    const fixture = mount(SearchPage);
    const http = TestBed.inject(HttpTestingController);

    http
      .expectOne((request) => request.url === 'http://localhost:8083/search/history')
      .flush([
        {
          id: 'h-1',
          query: 'fraud',
          requestJson: '{"query":"fraud","page":0,"size":20}',
          totalHits: 1,
          tookMs: 1,
          executedAt: '2026-09-10T12:00:00Z',
        },
      ]);
    await failEverything(fixture);

    const clearButton = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ].find((button) => button.textContent?.trim() === 'Clear history');
    expect(clearButton).toBeTruthy();
    clearButton!.click();
    settle(fixture);

    const clear = http.expectOne('http://localhost:8083/search/history');
    expect(clear.request.method).toBe('DELETE');
    clear.flush(null, { status: 204, statusText: 'No Content' });
    await settleAsync(fixture);

    expect(text(fixture)).toContain('Nothing has been searched yet');
  });
});

describe('a page whose services answer', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows the retention period in the form the API accepts back', async () => {
    const fixture = mount(RetentionDisposition);
    const http = TestBed.inject(HttpTestingController);

    http.expectOne('http://localhost:8087/retention/policies').flush([
      // 2555 days: the seven-year default, delivered as seconds.
      {
        messageType: 'EMAIL',
        periodSeconds: 220_752_000,
        updatedAt: '2026-01-01T00:00:00Z',
        updatedBy: 'seed',
      },
    ]);
    // Let the rest fail; this test is about one panel, which is the whole point of the design.
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('7 years');
    expect(rendered).toContain('P2555D');
  });

  it('warns that an unreachable hold service makes a sweep prove nothing', async () => {
    const fixture = mount(RetentionDisposition);
    const http = TestBed.inject(HttpTestingController);

    http.expectOne('http://localhost:8087/disposition/stats').flush({
      archivedMessages: 12_000,
      deleteMode: 'KAFKA',
      holdCheckEnabled: true,
      holdCheckRequired: true,
      retentionPeriods: {},
      totalRuns: 3,
      totalDeleted: 0,
      totalSkippedByHold: 500,
      lastRun: null,
    });
    http.expectOne('http://localhost:8087/disposition/stats/candidates').flush({
      batchSize: 500,
      candidatesInNextSweep: 500,
      cutoffs: {},
      activeHolds: 0,
      // P4 could not be reached during the preview.
      holdScopeAvailable: false,
      protectedByHoldFlag: 0,
      protectedByHoldScope: 0,
      protectedByCaseEvidence: 0,
      protectedByHold: 0,
      wouldBeDeleted: 0,
    });
    await failEverything(fixture);

    expect(text(fixture)).toContain('not proof that holds work');
  });

  it('distinguishes "no holds" from "could not ask"', async () => {
    // Asserted on the dashboard, where the holds panel is always on screen; the one on the cases
    // page only renders once a case is selected.
    const fixture = mount(Dashboard);
    const http = TestBed.inject(HttpTestingController);

    http.expectOne((request) => request.url === 'http://localhost:8086/holds').flush([]);
    await failEverything(fixture);

    // Scoped to the one panel: the hold *stats* panel on this page legitimately failed, and
    // asserting over the whole page would conflate the two.
    const holdsPanel = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.panel'),
    ].find((panel) => panel.querySelector('h2')?.textContent?.trim() === 'Active holds');

    expect(holdsPanel).toBeTruthy();
    expect(holdsPanel!.textContent).toContain('No hold is in force');
    // An empty list must never be reported as a failure: upstream, unreachable means *held*, so
    // rendering the two the same way would state the exact inverse of what the system is doing.
    expect(holdsPanel!.querySelector('.failure')).toBeNull();
  });

  it('counts the corpus on the dashboard', async () => {
    const fixture = mount(Dashboard);
    const http = TestBed.inject(HttpTestingController);

    http.expectOne('http://localhost:8082/stats').flush({ totalMessages: 12_000, onHold: 42 });
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('12,000');
    expect(rendered).toContain('42 flagged on hold');
  });
});
