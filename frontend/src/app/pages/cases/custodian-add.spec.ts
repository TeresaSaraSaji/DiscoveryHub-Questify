import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';
import { CaseDetail } from './case-detail';

const CASE_ID = 'ec927a18-10d9-4b45-9830-799f2ecffab4';

function caseWith(status: string) {
  return {
    caseId: CASE_ID,
    name: 'New1',
    description: null,
    matterType: 'LITIGATION',
    owner: 'investigator',
    status,
    createdAt: '2026-09-01T00:00:00Z',
    closedAt: status === 'CLOSED' ? '2026-09-02T00:00:00Z' : null,
    updatedAt: null,
    version: 0,
  };
}

/** Mount the detail page for a case in the given state, with every other service down. */
async function mountCase(status: string): Promise<ComponentFixture<CaseDetail>> {
  TestBed.configureTestingModule({
    providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
  });
  const fixture = TestBed.createComponent(CaseDetail);
  fixture.componentRef.setInput('caseId', CASE_ID);
  settle(fixture);

  const http = TestBed.inject(HttpTestingController);
  for (const request of http.match(() => true)) {
    if (request.cancelled) continue;
    const url = request.request.url;
    if (url.endsWith(`/cases/${CASE_ID}`)) {
      request.flush(caseWith(status));
    } else if (url.endsWith('/custodians')) {
      request.flush([]);
    } else {
      request.error(new ProgressEvent('error'), { status: 0 });
    }
  }
  await settleAsync(fixture);
  return fixture;
}

const custodianInput = (fixture: ComponentFixture<unknown>) =>
  Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>('input')).find(
    (i) => i.getAttribute('name') === 'custodianId',
  );

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

describe('adding a custodian', () => {
  it('posts the typed custodian to the case', async () => {
    const fixture = await mountCase('ACTIVE');
    const el = fixture.nativeElement as HTMLElement;

    const input = custodianInput(fixture);
    expect(input, 'custodian input should render').toBeTruthy();
    expect(input!.disabled, 'input should be enabled on an ACTIVE case').toBe(false);

    input!.value = 'cust-004';
    input!.dispatchEvent(new Event('input'));
    await settleAsync(fixture);

    const add = Array.from(el.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Add',
    );
    expect(add, 'Add button should render').toBeTruthy();
    expect((add as HTMLButtonElement).disabled, 'Add should be enabled once text is typed').toBe(
      false,
    );

    add!.click();
    await settleAsync(fixture);

    const posted = TestBed.inject(HttpTestingController).match(
      (r) => r.method === 'POST' && r.url.endsWith(`/cases/${CASE_ID}/custodians`),
    );
    expect(posted.length, 'a POST should have been issued').toBe(1);
    expect(posted[0].request.body).toEqual({ custodianId: 'cust-004' });
  });

  it('says why it is disabled on a closed case, rather than only greying out', async () => {
    // case-service answers a custodian on a closed case with a 409, so the control is disabled
    // on purpose. The reason used to live only in the Case detail panel, which is not where
    // someone is looking when they try to type a custodian and cannot.
    const fixture = await mountCase('CLOSED');

    expect(custodianInput(fixture)!.disabled, 'a closed case must not accept custodians').toBe(
      true,
    );
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'This case is closed, so no custodian can be added to it',
    );
  });
});
