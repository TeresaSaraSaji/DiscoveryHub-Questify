import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CaseBroadcast } from '../core/case-broadcast';
import { SEARCH_TIMEOUT_MS } from '../core/search.api';
import { routes } from '../app.routes';
import { AuditPage } from './audit/audit-page';
import { CaseDetail } from './cases/case-detail';
import { CasesPage } from './cases/cases-page';
import { NewCasePage } from './cases/new-case-page';
import { Dashboard } from './dashboard/dashboard';
import { ExportsPage } from './exports/exports-page';
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
    // A resource whose key changed aborts the request it had in flight, and the testing backend
    // throws rather than ignore an attempt to fail one of those.
    if (!request.cancelled) {
      request.error(new ProgressEvent('error'), { status: 0 });
    }
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

  it('renders a case detail page', async () => {
    // Its own entry because it is its own route. It is also the page with the most lists on it,
    // and every one of them reads a resource that is in its error state here.
    const fixture = mount(CaseDetail);
    // Every resource is keyed on the id and issues nothing while it is blank, so the input has to
    // be set and settled before there is anything to fail.
    fixture.componentRef.setInput('caseId', 'c0ffee00-0000-4000-8000-000000000000');
    await settleAsync(fixture);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('All cases');
    expect(rendered).toContain('is not reachable');
  });

  it('renders Open a case, which needs no service to read from', async () => {
    const fixture = mount(NewCasePage);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Open a case');
    expect(rendered).toContain('Matter details');
    // It reads nothing, so there is nothing to be unreachable. A form for creating a case must
    // not refuse to draw because a stats endpoint is down.
    expect((fixture.nativeElement as HTMLElement).querySelector('.failure')).toBeNull();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.btn--primary')
        ?.disabled,
    ).toBe(true);
  });

  it('renders Evidence Exports', async () => {
    const fixture = mount(ExportsPage);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Evidence Exports');
    expect(rendered).toContain('Request an export');
    expect(rendered).toContain('is not reachable');
  });

  it('renders the Audit Trail', async () => {
    const fixture = mount(AuditPage);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Audit Trail');
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
 * Selecting results and revealing more of them.
 *
 * The result list starts at one batch and grows only when asked ("Show more" appends the next
 * batch); a tick on a row survives that growth, and "Add selected" files exactly the ticked ids
 * through P3's add-selected endpoint — never a re-run of the search, which could match a
 * different set than the reviewer saw.
 */
describe('search result selection and show more', () => {
  beforeEach(() => TestBed.resetTestingModule());

  function aResult(id: string) {
    return {
      messageId: id,
      externalId: null,
      custodianId: 'cust-1',
      from: 'a@example.com',
      to: [],
      subject: `Subject ${id}`,
      sentAt: '2020-05-01T10:00:00Z',
      snippet: '',
      highlights: null,
      score: 1,
      onHold: false,
      attachmentCount: 0,
      attachmentFilenames: null,
    };
  }

  async function searchedFixture(total: number) {
    const fixture = mount(SearchPage);
    const page = fixture.componentInstance as unknown as { query: { set(v: string): void } };
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

    http.expectOne('http://localhost:8083/search').flush({
      results: [aResult('msg-1'), aResult('msg-2')],
      total,
      page: 0,
      size: 10,
      tookMs: 1,
    });
    await settleAsync(fixture);
    // The history refresh the successful first page triggered.
    http
      .match((request) => request.url === 'http://localhost:8083/search/history')
      .forEach((request) => request.flush([]));
    await settleAsync(fixture);
    return { fixture, http };
  }

  it('shows one batch and appends the next on Show more', async () => {
    const { fixture, http } = await searchedFixture(4);

    expect(text(fixture)).toContain('Showing 2 of 4');
    const showMore = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ].find((button) => button.textContent?.trim() === 'Show more');
    expect(showMore).toBeTruthy();

    showMore!.click();
    settle(fixture);

    const next = http.expectOne('http://localhost:8083/search');
    expect(next.request.body.page).toBe(1);
    next.flush({
      results: [aResult('msg-3'), aResult('msg-4')],
      total: 4,
      page: 1,
      size: 10,
      tookMs: 1,
    });
    await settleAsync(fixture);

    const rendered = text(fixture);
    // Appended, not replaced: the first batch is still on screen.
    expect(rendered).toContain('Subject msg-1');
    expect(rendered).toContain('Subject msg-4');
    expect(rendered).toContain('Showing 4 of 4');
    // Everything shown, nothing more to ask for.
    expect(rendered).not.toContain('Show more');
  });

  it('files exactly the ticked ids through the add-selected endpoint', async () => {
    const { fixture, http } = await searchedFixture(2);
    const host = fixture.nativeElement as HTMLElement;

    const checkboxes = [...host.querySelectorAll<HTMLInputElement>('tbody input[type=checkbox]')];
    expect(checkboxes).toHaveLength(2);
    checkboxes[1].click();
    settle(fixture);

    const page = fixture.componentInstance as unknown as {
      fileCaseId: { set(v: string): void };
    };
    page.fileCaseId.set('case-9');
    settle(fixture);

    const addSelected = [...host.querySelectorAll<HTMLButtonElement>('button')].find((button) =>
      button.textContent?.includes('Add selected (1)'),
    );
    expect(addSelected).toBeTruthy();
    addSelected!.click();
    settle(fixture);

    const filed = http.expectOne('http://localhost:8083/search/add-selected-to-case');
    // The ticked id, verbatim — not the search, not the page.
    expect(filed.request.body).toEqual({ caseId: 'case-9', messageIds: ['msg-2'] });
    filed.flush({
      caseId: 'case-9',
      matched: 1,
      added: 1,
      alreadyPresent: 0,
      messageIds: ['msg-2'],
      truncated: false,
    });
    await settleAsync(fixture);

    expect(text(fixture)).toContain('1 selected message filed on case-9');
    // Filing clears the picks, so the same tick cannot be double-filed by accident.
    expect(text(fixture)).toContain('Add selected (0)');
  });

  it('a new search clears the previous selection', async () => {
    const { fixture, http } = await searchedFixture(2);
    const host = fixture.nativeElement as HTMLElement;

    host.querySelector<HTMLInputElement>('tbody input[type=checkbox]')!.click();
    settle(fixture);
    expect(text(fixture)).toContain('Add selected (1)');

    host.querySelector<HTMLButtonElement>('.btn--primary')!.click();
    settle(fixture);
    http.expectOne('http://localhost:8083/search').flush({
      results: [aResult('msg-9')],
      total: 1,
      page: 0,
      size: 10,
      tookMs: 1,
    });
    await settleAsync(fixture);

    expect(text(fixture)).toContain('Add selected (0)');
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

/**
 * Opening a case is a route of its own, and the two things that has to keep true are that
 * `/cases/new` actually reaches this component and that a rejected create does not lose what was
 * typed. The second is the reason this is not a navigation-on-submit-and-hope: case-service
 * answers 409 on a duplicate name, and a form that had already navigated away would have thrown
 * the user's input on the floor.
 */
describe('opening a case on /cases/new', () => {
  beforeEach(() => TestBed.resetTestingModule());
  // window.open is spied on per test; leaving it stubbed would follow into the next file.
  afterEach(() => vi.restoreAllMocks());

  it('is reachable at its own URL', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    const harness = await RouterTestingHarness.create();
    const component = await harness.navigateByUrl('/cases/new');

    expect(component).toBeInstanceOf(NewCasePage);
    expect(TestBed.inject(Router).url).toBe('/cases/new');
  });

  /** A stand-in for the tab `window.open` hands back, recording what was done to it. */
  function fakeTab() {
    return {
      opener: {} as unknown,
      location: { replace: vi.fn() },
      close: vi.fn(),
    };
  }

  function submit(fixture: ComponentFixture<unknown>): void {
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.btn--primary')
      ?.click();
    settle(fixture);
  }

  const CREATED = {
    caseId: 'e3a1c0de-0000-4000-8000-000000000001',
    name: 'Q4 Broker Investigation',
    description: null,
    matterType: 'INVESTIGATION',
    owner: 'teresa',
    status: 'DRAFT',
    createdAt: '2026-09-11T09:00:00Z',
    updatedAt: null,
    closedAt: null,
  };

  it('posts the form and shows the new case in a new tab', async () => {
    const fixture = mount(NewCasePage);
    const http = TestBed.inject(HttpTestingController);
    const tab = fakeTab();
    const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);

    const page = fixture.componentInstance as unknown as {
      name: { set(v: string): void };
      owner: { set(v: string): void };
    };
    page.name.set('  Q4 Broker Investigation  ');
    page.owner.set('teresa');
    settle(fixture);
    submit(fixture);

    // The tab is claimed during the click, while the user gesture is still live — opening it
    // from the response instead is what every popup blocker exists to stop.
    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(tab.location.replace).not.toHaveBeenCalled();

    const created = http.expectOne('http://localhost:8084/cases');
    expect(created.request.method).toBe('POST');
    // Trimmed, and an empty description is null rather than "".
    expect(created.request.body).toEqual({
      name: 'Q4 Broker Investigation',
      description: null,
      matterType: 'INVESTIGATION',
      owner: 'teresa',
    });

    created.flush(CREATED);
    await settleAsync(fixture);

    expect(tab.location.replace).toHaveBeenCalledWith(
      '/cases/e3a1c0de-0000-4000-8000-000000000001',
    );
    // The new tab must not be able to script the one that opened it.
    expect(tab.opener).toBeNull();
    // This tab stays on the form, cleared and ready for the next matter.
    expect(TestBed.inject(Router).url).not.toContain(CREATED.caseId);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('is open in a new tab');
    expect(host.querySelector<HTMLInputElement>('input[name="name"]')?.value).toBe('');
  });

  it('tells the other tabs so their case list is not a row behind', async () => {
    const fixture = mount(NewCasePage);
    const http = TestBed.inject(HttpTestingController);
    vi.spyOn(window, 'open').mockReturnValue(fakeTab() as unknown as Window);
    const announce = vi.spyOn(TestBed.inject(CaseBroadcast), 'announce');

    (fixture.componentInstance as unknown as { name: { set(v: string): void } }).name.set('Q4');
    settle(fixture);
    submit(fixture);
    http.expectOne('http://localhost:8084/cases').flush(CREATED);
    await settleAsync(fixture);

    expect(announce).toHaveBeenCalled();
  });

  it('reloads the case list in a tab that hears the announcement', async () => {
    const fixture = mount(CasesPage);
    const http = TestBed.inject(HttpTestingController);
    await failEverything(fixture);

    window.dispatchEvent(
      new StorageEvent('storage', { key: 'discoveryhub:cases-changed', newValue: '1' }),
    );
    await settleAsync(fixture);

    // The list asks P4 again rather than waiting for someone to hit reload.
    expect(http.match((request) => request.url === 'http://localhost:8084/cases')).toHaveLength(1);
  });

  it('offers a link instead when the popup blocker eats the tab', async () => {
    const fixture = mount(NewCasePage);
    const http = TestBed.inject(HttpTestingController);
    // What a blocker returns.
    vi.spyOn(window, 'open').mockReturnValue(null);

    (fixture.componentInstance as unknown as { name: { set(v: string): void } }).name.set('Q4');
    settle(fixture);
    submit(fixture);
    http.expectOne('http://localhost:8084/cases').flush(CREATED);
    await settleAsync(fixture);

    const host = fixture.nativeElement as HTMLElement;
    // The case was created regardless, so saying nothing would lose it entirely.
    expect(host.textContent).toContain('the tab was blocked');
    const link = [...host.querySelectorAll<HTMLAnchorElement>('a[target="_blank"]')].find(
      (anchor) => anchor.getAttribute('href')?.includes(CREATED.caseId),
    );
    expect(link).toBeTruthy();
  });

  it('keeps the form and says why when case-service refuses', async () => {
    const fixture = mount(NewCasePage);
    const http = TestBed.inject(HttpTestingController);
    const tab = fakeTab();
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);

    const page = fixture.componentInstance as unknown as { name: { set(v: string): void } };
    page.name.set('Q3 Broker Investigation');
    settle(fixture);
    submit(fixture);

    http
      .expectOne('http://localhost:8084/cases')
      .flush(
        { detail: 'A case with that name is already open.' },
        { status: 409, statusText: 'Conflict' },
      );
    await settleAsync(fixture);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Rejected.');
    expect(host.textContent).toContain('already open');
    // No case, so no tab: a blank one left open is debris from an action that did not happen.
    expect(tab.close).toHaveBeenCalled();
    expect(tab.location.replace).not.toHaveBeenCalled();
    // Still here, still filled in, and ready to be corrected rather than retyped.
    expect(host.querySelector<HTMLInputElement>('input[name="name"]')?.value).toBe(
      'Q3 Broker Investigation',
    );
    expect(host.querySelector<HTMLButtonElement>('.btn--primary')?.disabled).toBe(false);
  });

  it('is linked from the case list, and from the list when it is empty', async () => {
    const fixture = mount(CasesPage);
    const http = TestBed.inject(HttpTestingController);

    http
      .expectOne((request) => request.url === 'http://localhost:8084/cases')
      .flush({
        content: [],
        number: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true,
      });
    await failEverything(fixture);

    const host = fixture.nativeElement as HTMLElement;
    // No cases at all is exactly when the way to open one has to be on screen, and the panel
    // holding that message is the one most likely to be showing a failure instead.
    expect(host.textContent).toContain('No cases match');
    const targets = [...host.querySelectorAll<HTMLAnchorElement>('a[href]')].map((anchor) =>
      anchor.getAttribute('href'),
    );
    expect(targets.filter((href) => href === '/cases/new')).toHaveLength(2);
  });
});

/** The evidence list can run to hundreds of rows; it grows ten at a time, like search results. */
describe('case evidence show more', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows ten rows and grows on Show more', async () => {
    // The evidence list moved to the case's own page, so the case is a route input now rather
    // than a signal set from the outside.
    const fixture = mount(CaseDetail);
    const http = TestBed.inject(HttpTestingController);
    http
      .match(() => true)
      .forEach((request) => request.error(new ProgressEvent('error'), { status: 0 }));

    fixture.componentRef.setInput('caseId', 'case-1');
    settle(fixture);

    const evidence = Array.from({ length: 12 }, (_, i) => ({
      id: `ev-${i}`,
      messageId: `msg-${i}`,
      source: 'SEARCH',
      addedAt: '2026-09-10T12:00:00Z',
    }));
    http
      .match((request) => request.url === 'http://localhost:8084/cases/case-1/evidence')
      .forEach((request) => request.flush(evidence));
    await failEverything(fixture);

    const host = fixture.nativeElement as HTMLElement;
    const shownRows = () =>
      [...host.querySelectorAll('td.mono')].filter((cell) => cell.textContent?.startsWith('msg-'))
        .length;

    expect(shownRows()).toBe(10);
    expect(text(fixture)).toContain('Showing 10 of 12');

    const showMore = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent?.trim() === 'Show more',
    );
    expect(showMore).toBeTruthy();
    showMore!.click();
    settle(fixture);

    expect(shownRows()).toBe(12);
    // Everything shown; the growth control has nothing left to offer.
    expect(text(fixture)).not.toContain('Showing 12 of 12');
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

  /**
   * FR-2.1: a matter has an opening date, and the list is where someone looks for it. Wall clock
   * only — the relative form ("3d ago") already sits in the detail panel below, and a column that
   * re-renders differently every minute is no use for reconciling a list against a log.
   */
  it('dates every case in the list, to the second and without a relative suffix', async () => {
    const fixture = mount(CasesPage);
    const http = TestBed.inject(HttpTestingController);

    http
      .expectOne((request) => request.url === 'http://localhost:8084/cases')
      .flush({
        content: [
          {
            caseId: '9c1fb101-1b94-4321-813f-329f8b71c03f',
            name: 'Q3 Broker Investigation',
            description: 'Insider trading probe',
            matterType: 'INVESTIGATION',
            owner: 'teresa',
            status: 'CLOSED',
            createdAt: '2026-09-08T22:09:12Z',
            updatedAt: null,
            closedAt: null,
          },
        ],
        number: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      });
    await failEverything(fixture);

    const host = fixture.nativeElement as HTMLElement;
    const headers = [...host.querySelectorAll('th')].map((th) => th.textContent?.trim());
    expect(headers).toContain('Created on');

    const created = [...host.querySelectorAll('tbody tr')][0].children[
      headers.indexOf('Created on')
    ];
    // Date and time in one cell, as one field.
    expect(created.textContent).toMatch(/\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4}.*\d{1,2}:\d{2}:\d{2}/);
    expect(created.textContent).not.toContain('ago');
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
