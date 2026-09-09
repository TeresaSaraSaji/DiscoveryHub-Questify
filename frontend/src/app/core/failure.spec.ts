import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { describe as describeService } from './api-config';
import { classify } from './failure';

describe('classify', () => {
  it('calls a refused connection unreachable, not a server error', () => {
    // The browser reports connection-refused, DNS failure, an unanswered CORS preflight and a
    // timeout all as status 0. Every one of them means nothing answered.
    const failure = classify(new HttpErrorResponse({ status: 0 }), describeService('p22'));

    expect(failure.kind).toBe('unreachable');
    expect(failure.message).toContain('P2.2 Disposition');
  });

  it('names the service and asks the two questions worth asking', () => {
    const failure = classify(new HttpErrorResponse({ status: 0 }), describeService('p4hold'));

    expect(failure.kind).toBe('unreachable');
    expect(failure.message).toContain('P4h Legal Hold');
    // Every service exists now, so "is it running, and does it allow this origin?" is the whole
    // diagnosis — and the second half is the one that actually catches people out.
    expect(failure.message).toContain('origin');
  });

  it('surfaces the message a service gave rather than inventing one', () => {
    const failure = classify(
      new HttpErrorResponse({
        status: 400,
        error: { status: 400, error: 'Bad Request', message: 'retention period must be positive' },
      }),
      describeService('p22'),
    );

    expect(failure.kind).toBe('rejected');
    expect(failure.message).toBe('retention period must be positive');
  });

  it('keeps a concurrent-run refusal distinguishable from a failure', () => {
    // A second sweep is refused with 409, and the page attaches to the run already in flight
    // rather than reporting an error, so this classification is load-bearing.
    const failure = classify(
      new HttpErrorResponse({
        status: 409,
        error: { message: 'a disposition run is in progress' },
      }),
    );

    expect(failure.kind).toBe('conflict');
  });

  it('does not render an HTML error page as a message', () => {
    const failure = classify(
      new HttpErrorResponse({ status: 502, error: '<html><body>Bad Gateway</body></html>' }),
      describeService('p2'),
    );

    expect(failure.kind).toBe('server');
    expect(failure.message).not.toContain('<html>');
    expect(failure.message).toContain('502');
  });

  it('treats a non-HTTP throw as unknown instead of pretending it was a response', () => {
    expect(classify(new Error('boom')).kind).toBe('unknown');
  });
});
