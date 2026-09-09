import { Pipe, PipeTransform } from '@angular/core';

/**
 * An instant as "14:32:07 · 3m ago".
 *
 * Both halves are needed. Every timestamp in this system is a UTC `Instant` from Java, and an
 * operator watching a sweep wants "how long ago"; anyone reconciling the ledger against a log
 * wants the wall clock. Showing only one costs a mental subtraction at the worst moment.
 */
@Pipe({ name: 'since' })
export class Since implements PipeTransform {
  transform(
    value: string | null | undefined,
    mode: 'both' | 'relative' | 'clock' = 'both',
  ): string {
    if (!value) {
      return '—';
    }
    const at = new Date(value);
    if (Number.isNaN(at.getTime())) {
      return value;
    }
    const clock = at.toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'medium' });
    if (mode === 'clock') {
      return clock;
    }
    const relative = ago(Date.now() - at.getTime());
    return mode === 'relative' ? relative : `${clock} · ${relative}`;
  }
}

function ago(millis: number): string {
  const seconds = Math.round(millis / 1000);
  if (seconds < 0) {
    return 'in the future';
  }
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 48) {
    return `${hours}h ago`;
  }
  return `${Math.round(hours / 24)}d ago`;
}
