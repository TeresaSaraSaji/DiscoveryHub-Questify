import { HttpInterceptorFn } from '@angular/common/http';

/** Nothing should be able to hang a panel indefinitely. */
export const DEFAULT_TIMEOUT_MS = 8_000;

/**
 * Give every request a deadline unless it asked for its own.
 *
 * A service that is *down* fails in milliseconds. A service that is wedged — accepting the
 * connection and never answering — is the case that leaves a spinner turning forever, and it is
 * the one an operator is most likely to hit while a sweep is running. A timeout turns that into an
 * error the panel can show and offer a retry for, which is the whole point of loading each panel
 * separately.
 */
export const httpTimeoutInterceptor: HttpInterceptorFn = (req, next) =>
  next(req.timeout ? req : req.clone({ timeout: DEFAULT_TIMEOUT_MS }));
