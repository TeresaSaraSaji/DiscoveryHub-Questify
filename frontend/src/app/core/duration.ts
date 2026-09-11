/**
 * ISO-8601 durations, because that is how P2.2 expresses a retention period.
 *
 * The API is asymmetric — it returns `periodSeconds` and accepts `period` as an ISO-8601 string —
 * so both directions live here rather than being inlined at each call site.
 */

const SECONDS_PER_DAY = 86_400;

/**
 * The compact units, and the only place their lengths are decided.
 *
 * A month is 30 days and a year is 365, because a retention period is a fixed span counted from
 * each message's own date, not a date on a calendar — there is no month to be the length of.
 *
 * `m` and `M` are the one pair where case decides the meaning, and they differ by a factor of
 * 43,200. Everything else accepts either case, so the shift key only matters where it has to:
 * `7d` and `7D` are both a week short of `7Y`, but `7m` and `7M` are seven minutes and seven
 * months. Seven minutes would make the whole corpus eligible on the next run.
 */
const UNIT_SECONDS: Readonly<Record<string, number>> = {
  s: 1,
  S: 1,
  m: 60,
  h: 3_600,
  H: 3_600,
  d: SECONDS_PER_DAY,
  D: SECONDS_PER_DAY,
  w: 7 * SECONDS_PER_DAY,
  W: 7 * SECONDS_PER_DAY,
  M: 30 * SECONDS_PER_DAY,
  y: 365 * SECONDS_PER_DAY,
  Y: 365 * SECONDS_PER_DAY,
};

/** Spelt-out units, matched case-insensitively because none of them are ambiguous. */
const WORD_SECONDS: Readonly<Record<string, number>> = {
  sec: 1,
  secs: 1,
  second: 1,
  seconds: 1,
  min: 60,
  mins: 60,
  minute: 60,
  minutes: 60,
  hour: 3_600,
  hours: 3_600,
  day: SECONDS_PER_DAY,
  days: SECONDS_PER_DAY,
  week: 7 * SECONDS_PER_DAY,
  weeks: 7 * SECONDS_PER_DAY,
  month: 30 * SECONDS_PER_DAY,
  months: 30 * SECONDS_PER_DAY,
  year: 365 * SECONDS_PER_DAY,
  years: 365 * SECONDS_PER_DAY,
};

/** Largest unit first, so 220_752_000 comes out as `7Y` rather than `2555d`. */
const COMPACT_ORDER: readonly (readonly [string, number])[] = [
  ['Y', 365 * SECONDS_PER_DAY],
  ['M', 30 * SECONDS_PER_DAY],
  ['w', 7 * SECONDS_PER_DAY],
  ['d', SECONDS_PER_DAY],
  ['h', 3_600],
  ['m', 60],
  ['s', 1],
];

/**
 * Seconds to the short form the retention table shows and its input accepts: 220_752_000 -> `7Y`,
 * 120 -> `2m`.
 *
 * Only exact divisions are used, so a period is never rounded into a prettier unit than it is:
 * 2555 days is `7Y`, but 2556 is `2556d` rather than a `7Y` that would delete a day early.
 */
export function toCompact(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return '0s';
  }
  const whole = Math.round(seconds);
  for (const [unit, size] of COMPACT_ORDER) {
    if (whole % size === 0) {
      return `${whole / size}${unit}`;
    }
  }
  return `${whole}s`;
}

/** Seconds to the shortest exact ISO-8601 duration: 220_752_000 -> `P2555D`, 120 -> `PT2M`. */
export function toIso(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return 'PT0S';
  }
  const whole = Math.round(seconds);
  if (whole % SECONDS_PER_DAY === 0) {
    return `P${whole / SECONDS_PER_DAY}D`;
  }
  if (whole % 3600 === 0) {
    return `PT${whole / 3600}H`;
  }
  if (whole % 60 === 0) {
    return `PT${whole / 60}M`;
  }
  return `PT${whole}S`;
}

/**
 * What a human reads next to it. Approximate on purpose: "7 years" is the useful fact about
 * `P2555D`, and the exact form is shown alongside it.
 */
export function humanize(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return 'none';
  }
  const days = seconds / SECONDS_PER_DAY;
  if (days >= 365) {
    const years = days / 365;
    return `${trim(years)} year${trim(years) === '1' ? '' : 's'}`;
  }
  if (days >= 1) {
    return `${trim(days)} day${trim(days) === '1' ? '' : 's'}`;
  }
  const hours = seconds / 3600;
  if (hours >= 1) {
    return `${trim(hours)} hour${trim(hours) === '1' ? '' : 's'}`;
  }
  const minutes = seconds / 60;
  if (minutes >= 1) {
    return `${trim(minutes)} minute${trim(minutes) === '1' ? '' : 's'}`;
  }
  return `${Math.round(seconds)} seconds`;
}

/**
 * Parse what someone typed into an ISO-8601 duration, or return null.
 *
 * Accepts the compact form the table shows (`7Y`, `3M`, `2w`, `90d`, `2m`), the words (`7 years`),
 * and the ISO form itself (`P2555D`, `PT2M`) so an older bookmark or a copied API value still
 * works.
 *
 * Everything is converted to seconds and re-emitted through {@link toIso}, which only ever
 * produces days, hours, minutes or seconds. That is not tidiness: the server binds this to a
 * `java.time.Duration`, which has no months or years, so `P7M` and `P7Y` come back 400. Months
 * and years have to leave here already counted into days.
 *
 * Validation is client-side courtesy only — the server rejects a non-positive period with a 400
 * regardless, and that error is shown as-is.
 */
export function parseToIso(input: string): string | null {
  const text = input.trim();
  if (!text) {
    return null;
  }
  if (/^P(?!$)(\d+D)?(T(?=\d)(\d+H)?(\d+M)?(\d+S)?)?$/i.test(text)) {
    return text.toUpperCase();
  }
  const shorthand = /^(\d+(?:\.\d+)?)\s*([A-Za-z]+)$/.exec(text);
  if (!shorthand) {
    return null;
  }
  const amount = Number(shorthand[1]);
  const unit = shorthand[2];
  if (amount <= 0) {
    return null;
  }
  // Single letters keep their case, so `m` and `M` stay distinct. Words do not need to.
  const size = unit.length === 1 ? UNIT_SECONDS[unit] : WORD_SECONDS[unit.toLowerCase()];
  if (!size) {
    return null;
  }
  return toIso(Math.round(amount * size));
}

function trim(value: number): string {
  return (Math.round(value * 10) / 10).toString();
}
