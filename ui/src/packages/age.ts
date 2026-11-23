const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const count = (n: number, unit: string) => `${n} ${unit}${n === 1 ? '' : 's'} ago`;

/**
 * How long before `now` the ISO 8601 time `at` was, in its largest whole unit: "just now" under a minute (and for a
 * time after `now`), then minutes, hours, days up to 60, months of 30 days up to two years, then years. An unreadable
 * time gives back `at` itself.
 */
export function ageOf(at: string, now: Date): string {
  const then = Date.parse(at);
  if (Number.isNaN(then)) return at;
  const seconds = Math.floor((now.getTime() - then) / 1000);
  if (seconds < MINUTE) return 'just now';
  if (seconds < HOUR) return count(Math.floor(seconds / MINUTE), 'minute');
  if (seconds < DAY) return count(Math.floor(seconds / HOUR), 'hour');
  if (seconds < 60 * DAY) return count(Math.floor(seconds / DAY), 'day');
  if (seconds < 730 * DAY) return count(Math.floor(seconds / (30 * DAY)), 'month');
  return count(Math.floor(seconds / (365 * DAY)), 'year');
}
