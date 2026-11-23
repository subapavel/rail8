import { describe, expect, test } from 'vitest';
import { diffOf, MAX_EDITS } from './LineDiff';

const lines = (prefix: string, count: number) => Array.from({ length: count }, (_, n) => `${prefix}${n}\n`).join('');

describe('diffOf', () => {
  test('marks the added and removed lines and folds a long run of unchanged ones', () => {
    expect(diffOf(lines('a', 10), lines('a', 10).replace('a9\n', 'b9\n'))).toEqual([
      { folded: 6 },
      { mark: ' ', text: 'a6' },
      { mark: ' ', text: 'a7' },
      { mark: ' ', text: 'a8' },
      { mark: '-', text: 'a9' },
      { mark: '+', text: 'b9' },
    ]);
  });

  test(`gives up on two versions more than ${MAX_EDITS} line edits apart`, () => {
    expect(diffOf(lines('a', MAX_EDITS / 2 + 1), lines('b', MAX_EDITS / 2 + 1))).toBeNull();
    expect(diffOf(lines('a', MAX_EDITS / 2 - 1), lines('b', MAX_EDITS / 2 - 1))).not.toBeNull();
  });
});
