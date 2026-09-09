import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Type } from '@angular/core';
import { beforeEach, describe, expect, it } from 'vitest';
import { CasesPage } from './cases/cases-page';
import { ExportAudit } from './export-audit/export-audit';
import { RetentionDisposition } from './retention-disposition/retention-disposition';

/**
 * Each page rendered with every one of its services down.
 *
 * This is the test that would have caught the real bug in this UI: `Resource.value()` throws in
 * the error state, Angular instantiates projected content eagerly, and a panel's table is
 * therefore evaluated while the panel is displaying a failure instead of it. Nothing about that is
 * visible in a passing service call — it only appears when a service is missing, which is the
 * normal state of P4 and P5 today.
 *
 * So: mount, fail everything, and require the page to still be a page.
 */
function mount<T>(component: Type<T>): ComponentFixture<T> {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
  const fixture = TestBed.createComponent(component);
  // Render, and let the resources' effects issue their requests. Deliberately not
  // `whenStable()`: that waits for pending HTTP, and with the testing backend nothing completes
  // until this test flushes it, so awaiting it here deadlocks.
  settle(fixture);
  return fixture;
}

/**
 * Run change detection and effects, and let a resolved promise through, until the resource state
 * and the DOM agree. Three passes because a resource takes one to start, one to deliver and one
 * to be rendered.
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

describe('every page survives every service being down', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('renders Retention & Disposition and says which service is unreachable', async () => {
    const fixture = mount(RetentionDisposition);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Retention & Disposition');
    // The panels are still there, each reporting its own failure rather than one page-wide error.
    expect(rendered).toContain('Retention policy');
    expect(rendered).toContain('Run a sweep');
    expect(rendered).toContain('is not reachable');
    // Every failed panel offers its own retry.
    expect(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.failure').length,
    ).toBeGreaterThan(1);
  });

  it('renders Case & Hold, and names P4 as unwritten rather than blaming the network', async () => {
    const fixture = mount(CasesPage);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Case & Hold');
    expect(rendered).toContain('Active holds');
    expect(rendered).toContain('not implemented yet');
  });

  it('renders Export & Audit', async () => {
    const fixture = mount(ExportAudit);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('Export & Audit');
    expect(rendered).toContain('Audit trail');
    expect(rendered).toContain('P5 is not written yet');
  });
});

describe('a page whose services answer', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows the retention period in the form the API accepts back', async () => {
    const fixture = mount(RetentionDisposition);
    const http = TestBed.inject(HttpTestingController);

    http.expectOne('http://localhost:8086/retention/policies').flush([
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

  it('reports the blast radius of the next sweep', async () => {
    const fixture = mount(RetentionDisposition);
    const http = TestBed.inject(HttpTestingController);

    http.expectOne('http://localhost:8086/disposition/stats/candidates').flush({
      batchSize: 500,
      candidatesInNextSweep: 412,
      cutoffs: { EMAIL: '2019-01-01T00:00:00Z' },
      activeHolds: 2,
      holdScopeAvailable: true,
      protectedByHoldFlag: 1,
      protectedByHoldScope: 10,
      protectedByCaseEvidence: 1,
      protectedByHold: 12,
      wouldBeDeleted: 400,
    });
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('412');
    expect(rendered).toContain('would be deleted');
    expect(rendered).toContain('saved by a hold');
  });

  it('warns that an unreachable P4 makes a sweep prove nothing', async () => {
    const fixture = mount(RetentionDisposition);
    const http = TestBed.inject(HttpTestingController);

    http.expectOne('http://localhost:8086/disposition/stats').flush({
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
    http.expectOne('http://localhost:8086/disposition/stats/candidates').flush({
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

    // The README's warning, on the screen where it matters, next to the button.
    expect(text(fixture)).toContain('not proof that holds work');
  });

  it('distinguishes "no holds" from "could not ask P4"', async () => {
    const fixture = mount(CasesPage);
    const http = TestBed.inject(HttpTestingController);

    http.expectOne('http://localhost:8084/holds/active').flush([]);
    await failEverything(fixture);

    const rendered = text(fixture);
    expect(rendered).toContain('No hold is in force');
    // An empty list must never be reported as a failure: upstream, unreachable means *held*.
    expect(rendered).not.toContain('P4 Case & Hold is not reachable');
  });
});
