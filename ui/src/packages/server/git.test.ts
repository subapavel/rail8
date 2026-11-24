import { describe, expect, test } from 'vitest';
import { parseLog } from './git.ts';

const ID = 'a'.repeat(40);
const PARENT = 'b'.repeat(40);

describe('the output of git log, read', () => {
  // git 2.45 and later write a time in UTC as Z, older ones as +00:00
  test.each([
    ['2026-11-24T05:00:45Z', '2026-11-24T05:00:45+00:00'],
    ['2026-11-24T05:00:45+00:00', '2026-11-24T05:00:45+00:00'],
    ['2026-11-24T10:30:45+05:30', '2026-11-24T10:30:45+05:30'],
    ['2026-11-24T00:00:45-05:00', '2026-11-24T00:00:45-05:00'],
  ])('a commit git dates %s is answered at %s', (written, answered) => {
    expect(parseLog(`${ID}\0${PARENT}\0Seed Author\0${written}\0second`)).toEqual([
      { id: ID, parents: [PARENT], subject: 'second', author: 'Seed Author', at: answered },
    ]);
  });
});
