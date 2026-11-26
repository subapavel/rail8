import { describe, expect, test } from 'vitest';
import { GENERATED_ALPHABET, GENERATED_LENGTH, generatePassword } from './password';

/** A source of random bytes that hands out `bytes` in order, then zeros. */
function replay(bytes: readonly number[]) {
  let next = 0;
  return (into: Uint8Array) => {
    for (let i = 0; i < into.length; i++) into[i] = next < bytes.length ? bytes[next++]! : 0;
    return into;
  };
}

describe('a generated password', () => {
  test('has 20 characters of the alphabet, about 116 random bits, within the node’s 12 to 128', () => {
    const password = generatePassword('anna');
    expect(GENERATED_LENGTH).toBe(20);
    expect(password).toHaveLength(GENERATED_LENGTH);
    expect([...password].every((c) => GENERATED_ALPHABET.includes(c))).toBe(true);
    expect(Math.log2(GENERATED_ALPHABET.length) * GENERATED_LENGTH).toBeGreaterThan(110);
    expect(generatePassword('anna')).not.toBe(password);
  });

  test('takes one character for each random byte, skipping the bytes that would favour the first characters', () => {
    const size = GENERATED_ALPHABET.length;
    const top = 256 - (256 % size);
    // the byte `top` and above are skipped; the rest pick `byte % size`
    const password = generatePassword('anna', replay([top, 255, 1, size + 2, ...Array<number>(18).fill(3)]));
    expect(password).toBe(GENERATED_ALPHABET[1]! + GENERATED_ALPHABET[2]! + GENERATED_ALPHABET[3]!.repeat(18));
  });

  test('never contains the name, in any letter case, as the node refuses that', () => {
    const at = (c: string) => GENERATED_ALPHABET.indexOf(c);
    // the first try spells "BeD" inside; the second does not
    const first = [at('B'), at('e'), at('D'), ...Array<number>(17).fill(at('x'))];
    const second = Array<number>(20).fill(at('y'));
    expect(generatePassword('bed', replay([...first, ...second]))).toBe('y'.repeat(20));
  });
});
