import { describe, expect, test } from 'vitest';
import { ageOf } from './age';

const NOW = new Date('2026-11-23T12:00:00Z');

describe('the age of a time', () => {
  test.each([
    ['2026-11-23T12:00:30Z', 'just now'],
    ['2026-11-23T11:59:31Z', 'just now'],
    ['2026-11-23T11:59:00Z', '1 minute ago'],
    ['2026-11-23T11:01:00Z', '59 minutes ago'],
    ['2026-11-23T11:00:00Z', '1 hour ago'],
    ['2026-11-22T12:00:01Z', '23 hours ago'],
    ['2026-11-22T12:00:00+00:00', '1 day ago'],
    ['2026-11-22T13:00:00+01:00', '1 day ago'],
    ['2026-09-25T12:00:00Z', '59 days ago'],
    ['2026-09-24T12:00:00Z', '2 months ago'],
    ['2024-11-24T12:00:00Z', '24 months ago'],
    ['2024-11-23T12:00:00Z', '2 years ago'],
  ])('%s is %s', (at, age) => {
    expect(ageOf(at, NOW)).toBe(age);
  });

  test('a time it cannot read is given back as it is', () => {
    expect(ageOf('yesterday', NOW)).toBe('yesterday');
  });
});
