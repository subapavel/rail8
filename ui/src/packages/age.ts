import type { Texts } from '../language';

const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * How long before `now` the ISO 8601 time `at` was, in its largest whole unit and in `texts`' language: "just now" under
 * a minute (and for a time after `now`), then minutes, hours, days up to 60, months of 30 days up to two years, then
 * years, such as "3 days ago". An unreadable time gives back `at` itself.
 */
export function ageOf({ t, intl }: Texts, at: string, now: Date): string {
  const then = Date.parse(at);
  if (Number.isNaN(then)) return at;
  const seconds = Math.floor((now.getTime() - then) / 1000);
  const ago = (n: number, unit: 'minute' | 'hour' | 'day' | 'month' | 'year') => intl.formatRelativeTime(-n, unit);
  if (seconds < MINUTE) return t('packages.age.now');
  if (seconds < HOUR) return ago(Math.floor(seconds / MINUTE), 'minute');
  if (seconds < DAY) return ago(Math.floor(seconds / HOUR), 'hour');
  if (seconds < 60 * DAY) return ago(Math.floor(seconds / DAY), 'day');
  if (seconds < 730 * DAY) return ago(Math.floor(seconds / (30 * DAY)), 'month');
  return ago(Math.floor(seconds / (365 * DAY)), 'year');
}
