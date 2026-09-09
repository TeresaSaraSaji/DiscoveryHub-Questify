import { describe, expect, it } from 'vitest';
import { humanize, parseToIso, toIso } from './duration';

describe('toIso', () => {
  it('renders the seven-year default the way the API states it', () => {
    // 2555 days. If this ever comes out as PT220752000S the retention table becomes unreadable.
    expect(toIso(2555 * 86_400)).toBe('P2555D');
  });

  it('renders the two-minute demo period', () => {
    expect(toIso(120)).toBe('PT2M');
  });

  it('falls back to seconds rather than rounding a period away', () => {
    expect(toIso(90)).toBe('PT90S');
  });

  it('never emits a non-positive duration', () => {
    expect(toIso(0)).toBe('PT0S');
    expect(toIso(-1)).toBe('PT0S');
  });
});

describe('parseToIso', () => {
  it('accepts the ISO form unchanged', () => {
    expect(parseToIso('P2555D')).toBe('P2555D');
    expect(parseToIso('pt2m')).toBe('PT2M');
  });

  it('accepts the shorthand a person actually types', () => {
    expect(parseToIso('90d')).toBe('P90D');
    expect(parseToIso('7 years')).toBe('P2555D');
    expect(parseToIso('2m')).toBe('PT2M');
  });

  it('rejects anything it cannot turn into a positive duration', () => {
    // A zero or negative period would make the entire corpus eligible on the next sweep, so the
    // UI refuses it before the server has to.
    expect(parseToIso('0d')).toBeNull();
    expect(parseToIso('-5d')).toBeNull();
    expect(parseToIso('soon')).toBeNull();
    expect(parseToIso('')).toBeNull();
    expect(parseToIso('P')).toBeNull();
  });
});

describe('humanize', () => {
  it('says what the retention period means', () => {
    expect(humanize(2555 * 86_400)).toBe('7 years');
    expect(humanize(1095 * 86_400)).toBe('3 years');
    expect(humanize(120)).toBe('2 minutes');
  });
});
