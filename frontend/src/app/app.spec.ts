import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from './app';
import { routes } from './app.routes';

describe('the shell', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('renders one nav entry per page, and the service strip', () => {
    const fixture = TestBed.createComponent(App);
    TestBed.tick();
    fixture.detectChanges();
    const shell = fixture.nativeElement as HTMLElement;

    expect(
      [...shell.querySelectorAll('.nav__link')].map((link) => link.textContent?.trim()),
    ).toEqual(['Retention & Disposition', 'Case & Hold', 'Export & Audit']);

    // Five services, always listed — including the two nobody has written, because "which of
    // these is even running?" is the first question anyone asks of this system.
    expect(shell.querySelectorAll('.status__item')).toHaveLength(5);
  });
});
