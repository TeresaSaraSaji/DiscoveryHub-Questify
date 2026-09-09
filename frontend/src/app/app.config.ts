import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { httpTimeoutInterceptor } from './core/http-timeout.interceptor';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),
    // `withFetch` for the abort support the timeout interceptor relies on. No error interceptor:
    // failures belong to the panel that made the request, not to a global handler.
    provideHttpClient(withFetch(), withInterceptors([httpTimeoutInterceptor])),
  ],
};
