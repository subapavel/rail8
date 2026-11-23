import { describe, expect, test } from 'vitest';
import { formatted, MAX_LINE, MAX_LINES } from './formatter.ts';

describe('the one formatter', () => {
  test('replaces the user information of every URL with ***', () => {
    expect(formatted("fatal: unable to access 'https://user:secret@git.example.com/orders.git/': 403\nssh://git@host/x and http://host/y")).toBe(
      "fatal: unable to access 'https://***@git.example.com/orders.git/': 403\nssh://***@host/x and http://host/y",
    );
  });

  test("removes the admin token's value wherever it appears", () => {
    expect(formatted('Authorization: Bearer t0ken-value, again t0ken-value', 't0ken-value')).toBe('Authorization: Bearer ***, again ***');
  });

  test('removes the token before it cuts a line, so no part of it is left', () => {
    const token = 'abcdefghij';
    expect(formatted(`${'x'.repeat(MAX_LINE - 5)}${token}`, token)).toBe(`${'x'.repeat(MAX_LINE - 5)}***`);
  });

  test('keeps at most 40 lines of 200 characters, without trailing white space', () => {
    const text = Array.from({ length: 50 }, (_, i) => `${i}`.padEnd(300, '.')).join('\r\n') + '\n\n';
    const lines = formatted(text).split('\n');

    expect(lines).toHaveLength(MAX_LINES);
    expect(lines.every((line) => line.length === MAX_LINE)).toBe(true);
    expect(lines[39]!.startsWith('39')).toBe(true);
  });
});
