import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ArchiveApi } from './archive.api';
import { CasesApi } from './cases.api';
import { DispositionApi } from './disposition.api';
import { RetentionPolicy } from './models';
import { valueOr } from './resource-utils';

/**
 * Let signal effects run and the resource's own promise chain settle.
 *
 * `TestBed.tick()` is synchronous and a resource delivers its value through a microtask, so one
 * without the other leaves the resource mid-transition and the assertions read the previous state.
 */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

/**
 * The claim this app makes, tested rather than asserted in a comment: one service being down
 * degrades the panels that need it and nothing else.
 *
 * These are the cases that actually happen. P4 and P5 are not written, so every page has panels
 * pointing at a port with nothing behind it; the pages still have to work.
 */
describe('a failing service does not take the others with it', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('leaves the archive panel resolved when disposition is unreachable', async () => {
    const disposition = TestBed.inject(DispositionApi);
    const archive = TestBed.inject(ArchiveApi);

    const [policies, archiveStats] = TestBed.runInInjectionContext(() => [
      disposition.policiesResource(),
      archive.statsResource(),
    ]);
    await settle();

    const http = TestBed.inject(HttpTestingController);

    // P2.2 is not running: the browser reports this as status 0 with no body.
    http
      .expectOne('http://localhost:8086/retention/policies')
      .error(new ProgressEvent('error'), { status: 0 });
    http.expectOne('http://localhost:8082/stats').flush({ totalMessages: 12_000, onHold: 3 });
    await settle();

    expect(policies.status()).toBe('error');
    expect(archiveStats.status()).toBe('resolved');
    expect(archiveStats.value()?.totalMessages).toBe(12_000);
  });

  it('gives a template a readable list after a failure, where value() would throw', async () => {
    const disposition = TestBed.inject(DispositionApi);
    const policies = TestBed.runInInjectionContext(() => disposition.policiesResource());
    const rows = valueOr(policies, [] as RetentionPolicy[]);
    await settle();

    TestBed.inject(HttpTestingController)
      .expectOne('http://localhost:8086/retention/policies')
      .error(new ProgressEvent('error'), { status: 0 });
    await settle();

    // This is the trap `valueOr` exists for. `value()` throws in the error state and a
    // `defaultValue` does not cover it — only idle and loading. Angular instantiates projected
    // content eagerly, so a panel's table is evaluated even while the panel is displaying the
    // failure instead of it, and the throw would escape as a template error and blank the page.
    expect(() => policies.value()).toThrow();
    expect(rows()).toEqual([]);
  });

  it('recovers on reload without the page being touched', async () => {
    const disposition = TestBed.inject(DispositionApi);
    const policies = TestBed.runInInjectionContext(() => disposition.policiesResource());
    await settle();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('http://localhost:8086/retention/policies')
      .error(new ProgressEvent('error'), { status: 0 });
    await settle();
    expect(policies.status()).toBe('error');

    // What the Reload button in the panel header does.
    policies.reload();
    await settle();
    http.expectOne('http://localhost:8086/retention/policies').flush([
      {
        messageType: 'EMAIL',
        periodSeconds: 220_752_000,
        updatedAt: '2026-01-01T00:00:00Z',
        updatedBy: 'seed',
      },
    ]);
    await settle();

    expect(policies.status()).toBe('resolved');
    expect(policies.value()).toHaveLength(1);
  });
});

describe('requests that would be pointless are not made', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('stays idle until a case is chosen instead of asking about an empty id', async () => {
    const disposition = TestBed.inject(DispositionApi);
    const caseId = signal('');
    const page = signal(0);

    const protectedItems = TestBed.runInInjectionContext(() =>
      disposition.protectedByCaseResource(caseId, page),
    );
    await settle();

    const http = TestBed.inject(HttpTestingController);
    // A request to /cases//protected would come back 404 and be rendered as a failure, which is
    // a lie: nothing has been asked yet.
    http.expectNone(() => true);
    expect(protectedItems.status()).toBe('idle');

    caseId.set('case-1');
    await settle();

    expect(
      http.expectOne((request) => request.url.endsWith('/disposition/cases/case-1/protected')),
    ).toBeTruthy();
  });

  it('does not ask P4 about a message until one is typed', async () => {
    const cases = TestBed.inject(CasesApi);
    const holds = TestBed.runInInjectionContext(() => cases.activeHoldsResource());
    await settle();

    // The holds list loads on sight; the per-message check is imperative and must not.
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('http://localhost:8084/holds/active').flush([]);
    await settle();

    expect(holds.value()).toEqual([]);
    http.expectNone((request) => request.url.includes('/holds/check'));
  });
});

describe('an empty answer is not a failed one', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('resolves an empty hold list rather than treating it as nothing known', async () => {
    // This distinction is load-bearing. Upstream, an unreachable P4 means *held* — both P2 and
    // P2.2 refuse to delete. "No holds are in force" and "we could not ask" are therefore
    // opposite facts, and the UI must never render one as the other.
    const cases = TestBed.inject(CasesApi);
    const holds = TestBed.runInInjectionContext(() => cases.activeHoldsResource());
    await settle();

    TestBed.inject(HttpTestingController).expectOne('http://localhost:8084/holds/active').flush([]);
    await settle();

    expect(holds.status()).toBe('resolved');
    expect(holds.error()).toBeUndefined();
  });
});
