import { describe, expect, it } from 'vitest';
import { humanize, parseToIso, toCompact, toIso } from './duration';

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

describe('toCompact', () => {
  it('states the two standing policies the way the table shows them', () => {
    expect(toCompact(2555 * 86_400)).toBe('7Y');
    expect(toCompact(1095 * 86_400)).toBe('3Y');
  });

  it('picks the largest unit that divides exactly', () => {
    expect(toCompact(90 * 86_400)).toBe('3M');
    expect(toCompact(14 * 86_400)).toBe('2w');
    expect(toCompact(120)).toBe('2m');
    expect(toCompact(7_200)).toBe('2h');
  });

  it('drops to a smaller unit rather than round a period shorter', () => {
    // 2556 days is not seven years. Showing it as 7Y would be a day of retention lost in a label.
    expect(toCompact(2556 * 86_400)).toBe('2556d');
    expect(toCompact(90)).toBe('90s');
  });

  it('never emits a non-positive duration', () => {
    expect(toCompact(0)).toBe('0s');
    expect(toCompact(-1)).toBe('0s');
  });
});

describe('parseToIso', () => {
  it('accepts the ISO form unchanged', () => {
    expect(parseToIso('P2555D')).toBe('P2555D');
    expect(parseToIso('pt2m')).toBe('PT2M');
  });

  it('accepts the compact form the table shows', () => {
    expect(parseToIso('7Y')).toBe('P2555D');
    expect(parseToIso('3Y')).toBe('P1095D');
    expect(parseToIso('2w')).toBe('P14D');
    expect(parseToIso('90d')).toBe('P90D');
    expect(parseToIso('12h')).toBe('PT12H');
    expect(parseToIso('45s')).toBe('PT45S');
    expect(parseToIso('7 years')).toBe('P2555D');
    expect(parseToIso('2m')).toBe('PT2M');
  });

  it('keeps minutes and months apart by case', () => {
    // The whole reason case matters here. Seven minutes and seven months differ by a factor of
    // 43,200, and the wrong one makes the entire corpus eligible on the next run.
    expect(parseToIso('7m')).toBe('PT7M');
    expect(parseToIso('7M')).toBe('P210D');
  });

  it('does not make the shift key matter where it cannot change the meaning', () => {
    expect(parseToIso('7D')).toBe(parseToIso('7d'));
    expect(parseToIso('7y')).toBe(parseToIso('7Y'));
    expect(parseToIso('2W')).toBe(parseToIso('2w'));
  });

  it('never emits months or years, which the server cannot bind', () => {
    // java.time.Duration has no month or year, so P7M and P7Y come back 400. Everything has to
    // leave here already counted into days or smaller.
    for (const input of ['7Y', '7M', '18 months', '2 years']) {
      expect(parseToIso(input)).toMatch(/^P(\d+D)?(T.+)?$/);
    }
  });

  it('rejects anything it cannot turn into a positive duration', () => {
    // A zero or negative period would make the entire corpus eligible on the next sweep, so the
    // UI refuses it before the server has to.
    expect(parseToIso('0d')).toBeNull();
    expect(parseToIso('-5d')).toBeNull();
    expect(parseToIso('soon')).toBeNull();
    expect(parseToIso('')).toBeNull();
    expect(parseToIso('P')).toBeNull();
    expect(parseToIso('7')).toBeNull();
    expect(parseToIso('7q')).toBeNull();
  });
});

describe('humanize', () => {
  it('says what the retention period means', () => {
    expect(humanize(2555 * 86_400)).toBe('7 years');
    expect(humanize(1095 * 86_400)).toBe('3 years');
    expect(humanize(120)).toBe('2 minutes');
  });
});
