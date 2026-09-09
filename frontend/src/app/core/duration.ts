/**
 * ISO-8601 durations, because that is how P2.2 expresses a retention period.
 *
 * The API is asymmetric — it returns `periodSeconds` and accepts `period` as an ISO-8601 string —
 * so both directions live here rather than being inlined at each call site.
 */

const SECONDS_PER_DAY = 86_400;

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
 * Accepts the ISO form directly (`P2555D`, `PT2M`) and the shorthand a person actually types
 * (`90d`, `12h`, `2m`, `7 years`). Validation is client-side courtesy only — the server rejects a
 * non-positive period with a 400 regardless, and that error is shown as-is.
 */
export function parseToIso(input: string): string | null {
  const text = input.trim();
  if (!text) {
    return null;
  }
  if (/^P(?!$)(\d+D)?(T(?=\d)(\d+H)?(\d+M)?(\d+S)?)?$/i.test(text)) {
    return text.toUpperCase();
  }
  const shorthand =
    /^(\d+(?:\.\d+)?)\s*(y|year|years|d|day|days|h|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)$/i.exec(
      text,
    );
  if (!shorthand) {
    return null;
  }
  const amount = Number(shorthand[1]);
  const unit = shorthand[2].toLowerCase();
  if (amount <= 0) {
    return null;
  }
  if (unit.startsWith('y')) {
    return `P${Math.round(amount * 365)}D`;
  }
  if (unit.startsWith('d')) {
    return `P${Math.round(amount)}D`;
  }
  if (unit.startsWith('h')) {
    return `PT${Math.round(amount)}H`;
  }
  if (unit.startsWith('m')) {
    return `PT${Math.round(amount)}M`;
  }
  return `PT${Math.round(amount)}S`;
}

function trim(value: number): string {
  return (Math.round(value * 10) / 10).toString();
}
