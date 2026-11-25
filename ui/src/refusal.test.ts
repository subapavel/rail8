import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';
import { SERVER_CODES } from '../server/refusal';
import { LANGUAGES, msg, textsOf } from './language';
import { NOT_TRANSLATED, refusalKey, refusalOf, refusalText } from './refusal';

const repository = join(import.meta.dirname, '../..');

/** The codes of the node's admin API, as its one list of them names them. */
const nodeCodes = [...readFileSync(join(repository, 'platform/runtime/src/main/java/dev/rail8/runtime/refusal/RefusalCode.java'), 'utf8').matchAll(/^\s+[A-Z][A-Z0-9_]*\("([^"]+)"\)/gm)].map((m) => m[1]!);

/** The codes of the mapping studio's contract: those its problems may carry. */
const contract = JSON.parse(readFileSync(join(repository, 'mapping/contract/openapi.json'), 'utf8')) as {
  components: { schemas: Record<string, { properties: { code?: { enum?: string[]; const?: string } } }> };
};
const mappingCodes = Object.values(contract.components.schemas).flatMap((schema) => schema.properties?.code?.enum ?? (schema.properties?.code?.const === undefined ? [] : [schema.properties.code.const]));

const english = textsOf('en');
const KEBAB = /^[a-z0-9]+(-[a-z0-9]+)*$/;

describe('refusalText', () => {
  test('a refusal the UI knows shows in the language the UI shows, with its values', () => {
    const body = { error: 'no flow shop/orders deployed on this node', code: 'no-flow', values: { flow: 'shop/orders' } };
    expect(refusalText(body, {}, 'cs')).toBe('Na tomto uzlu není nasazený tok shop/orders.');
    expect(refusalText(body, {}, 'en')).toBe('No flow shop/orders is deployed on this node.');
  });

  test('an unknown code shows the English text the refusal carries', () => {
    expect(refusalText({ error: 'the node says no', code: 'not-a-code-of-the-ui', values: {} }, {}, 'cs')).toBe('the node says no');
  });

  test('a refusal without a code shows its English text, and a body without text none', () => {
    expect(refusalText({ error: 'the node says no' }, {}, 'de')).toBe('the node says no');
    expect(refusalText({ status: 500 }, {}, 'de')).toBeUndefined();
    expect(refusalText('not a body', {}, 'de')).toBeUndefined();
  });

  test('a text whose value the refusal lacks shows the English text', () => {
    expect(refusalText({ error: 'the request is too large', code: 'too-large', values: {} }, {}, 'ru')).toBe('the request is too large');
  });

  test("a problem's code is its code, else the last part of its type, and its text its detail", () => {
    const problem = { type: 'urn:rail8:mapping:problem:mapping-exists', title: 'Mapping exists', status: 409, detail: 'a mapping with this id exists' };
    expect(refusalOf(problem)).toEqual({ code: 'mapping-exists', values: {}, english: 'a mapping with this id exists' });
    expect(refusalOf({ ...problem, type: 'urn:rail8:admin:problem:forbidden', code: 'not-allowed', values: { action: 'module.call', caller: 'ci' } }).code).toBe('not-allowed');
    expect(refusalText(problem, {}, 'en')).toBe('A mapping with this id exists.');
  });

  test("a module's own words win over the shell's, with the refusal's values and its own", () => {
    const words = { 'no-flow': msg('frame.node.answered', { status: 418 }) };
    expect(refusalText({ error: 'x', code: 'no-flow', values: { flow: 'f' } }, words, 'en')).toBe('the node answered 418');
  });

  test('values of another shape than a string or a number are left out', () => {
    expect(refusalOf({ error: 'x', code: 'c', values: { a: 'b', n: 2, list: ['x'], none: null } }).values).toEqual({ a: 'b', n: 2 });
  });
});

describe('the codes of refusals', () => {

  test.each([
    ['the node', nodeCodes],
    ["the UI's server", [...SERVER_CODES]],
    ["the mapping studio's contract", mappingCodes],
  ])('every code of %s has an English text, or is on the list of codes not translated', (_, codes) => {
    expect(codes.length).toBeGreaterThan(0);
    expect(codes.filter((code) => !(refusalKey(code) in english) && !NOT_TRANSLATED.has(code))).toEqual([]);
  });

  test("the UI server's codes are kebab-case and each named once", () => {
    expect(SERVER_CODES.filter((code) => !KEBAB.test(code))).toEqual([]);
    expect(new Set(SERVER_CODES).size).toBe(SERVER_CODES.length);
  });

  test.each(LANGUAGES.filter((language) => language !== 'en'))('%s has a text for every refusal English has', (language) => {
    const own = textsOf(language);
    expect(Object.keys(english).filter((key) => key.startsWith('refusal.') && !(key in own))).toEqual([]);
  });

  test('a code not translated has no text', () => {
    for (const code of NOT_TRANSLATED) expect(refusalKey(code) in english, code).toBe(false);
  });
});
