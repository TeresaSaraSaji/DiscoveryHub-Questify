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
    expect(rendered).toContain('Run a sweep');
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

    http.expectNone((request) => request.url.includes('/search'));
    // The one request it does make is the case list, for the "file to case" control.
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
