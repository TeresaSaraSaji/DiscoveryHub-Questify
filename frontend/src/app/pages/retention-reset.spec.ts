import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { RetentionDisposition } from './retention-disposition/retention-disposition';

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

const POLICIES = [
  { messageType: 'EMAIL', periodSeconds: 2555 * 86_400, updatedAt: null, updatedBy: 'seed' },
  { messageType: 'CHAT', periodSeconds: 1095 * 86_400, updatedAt: null, updatedBy: 'seed' },
];

describe('retention policy Reset', () => {
  it('puts the typed period back to what is stored', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(RetentionDisposition);
    settle(fixture);

    const http = TestBed.inject(HttpTestingController);
    for (const request of http.match(() => true)) {
      if (request.request.url.endsWith('/retention/policies')) {
        request.flush(POLICIES);
      } else {
        request.error(new ProgressEvent('error'), { status: 0 });
      }
    }
    await settleAsync(fixture);

    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('input.input--period');
    expect(input, 'the period input should render').toBeTruthy();
    expect(input!.value).toBe('7Y');

    // Type something else, the way a person would.
    input!.value = '30m';
    input!.dispatchEvent(new Event('input'));
    await settleAsync(fixture);
    expect(input!.value, 'the typed value should stick').toBe('30m');

    // Now press Reset.
    const reset = Array.from(el.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Reset',
    );
    expect(reset, 'a Reset button should render').toBeTruthy();
    reset!.click();
    await settleAsync(fixture);

    expect(input!.value, 'Reset should restore the stored period').toBe('7Y');
  });
});
