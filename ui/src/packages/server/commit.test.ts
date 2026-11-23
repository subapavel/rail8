import { describe, expect, test } from 'vitest';
import { committedBy } from './commit.ts';

describe('committedBy', () => {
  test('reads the abbreviated id from the summary line git commit prints first on stdout', () => {
    expect(committedBy('[main 1a2b3c4] Change a\n 1 file changed, 1 insertion(+)\n', 'main')).toBe('1a2b3c4');
    expect(committedBy('[release/1.0 1a2b3c4d5e] [main deadbeef] a subject that looks like one\n', 'release/1.0')).toBe('1a2b3c4d5e');
  });

  test('takes the marker of a first commit, in any language, between the branch and the id', () => {
    expect(committedBy('[main (root-commit) 704f633] first\n', 'main')).toBe('704f633');
    expect(committedBy('[main (Root-Commit) 704f633] first\n', 'main')).toBe('704f633');
    expect(committedBy('[main (commit racine) 704f633] first\n', 'main')).toBe('704f633');
  });

  test('names no commit for another branch, a later line, or no summary line', () => {
    expect(committedBy('[other 1a2b3c4] Change a\n', 'main')).toBeUndefined();
    expect(committedBy('\n[main 1a2b3c4] Change a\n', 'main')).toBeUndefined();
    expect(committedBy(' 1 file changed\n', 'main')).toBeUndefined();
    expect(committedBy('', 'main')).toBeUndefined();
    expect(committedBy('[main 1a2b3c4 ] Change a\n', 'main')).toBeUndefined();
  });
});
