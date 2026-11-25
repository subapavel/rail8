import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test, vi } from 'vitest';
import { SERVER_CODES } from '../../server/refusal';
import { chooseLanguage, LANGUAGES, say, textsIn, textsOf, type Language } from '../language';
import { NOT_TRANSLATED, refusalKey } from '../refusal';
import { httpPackagesApi, PackagesError } from './api';
import { ADDRESS_TEXTS, NEW_PACKAGE_TEXTS, PACKAGE_WORDS, packageRefusalKey, refusedIn } from './refusals';
import { ADDRESS_REFUSALS, cloneSourceOf, COMMIT_REFUSALS, NEW_PACKAGE_REFUSALS, PACKAGE_REFUSAL_CODES } from './rules';

const repository = join(import.meta.dirname, '../../..');

/** The codes of the node's admin API, as its one list of them names them. */
const nodeCodes = new Set(
  [...readFileSync(join(repository, 'platform/runtime/src/main/java/dev/rail8/runtime/refusal/RefusalCode.java'), 'utf8').matchAll(/^\s+[A-Z][A-Z0-9_]*\("([^"]+)"\)/gm)].map((m) => m[1]!),
);

const KEBAB = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const shell = new Set<string>(SERVER_CODES);

/** Runs `check` with the UI shown in `language`, and shows it in English again after. */
async function inLanguage(language: Language, check: () => Promise<void>) {
  vi.stubGlobal('document', { documentElement: {} });
  chooseLanguage(language);
  try {
    await check();
  } finally {
    chooseLanguage('en');
    vi.unstubAllGlobals();
  }
}

/** The packages client over a server that answers every call with `status` and `body`. */
const answering = (status: number, body: unknown) => httpPackagesApi(() => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })));

/** What `call` rejects with. */
async function refusalOf(call: Promise<unknown>): Promise<PackagesError> {
  try {
    await call;
  } catch (error) {
    if (error instanceof PackagesError) return error;
    throw error;
  }
  throw new Error('the call was not refused');
}

describe('the refusals the dialogs find as the author types', () => {
  const english = textsIn('en');

  test.each(Object.entries(ADDRESS_TEXTS))('the English words of the address refusal %s are the rules’ own', (name, message) => {
    expect(say(english, message)).toBe(ADDRESS_REFUSALS[name as keyof typeof ADDRESS_REFUSALS].error);
  });

  test.each(Object.entries(NEW_PACKAGE_TEXTS))('the English words of the new package refusal %s are the rules’ own', (name, message) => {
    expect(say(english, message)).toBe(NEW_PACKAGE_REFUSALS[name as 'repository' | 'name'].error);
  });

  test('a refusal of the rules is given in the language of the texts, by its code', () => {
    const source = cloneSourceOf('https://git.example.com/team/orders.git main');
    expect('error' in source && refusedIn(textsIn('cs'), source)).toBe('vložte jen adresu: obsahuje bílé znaky');
    expect(refusedIn(textsIn('de'), NEW_PACKAGE_REFUSALS.name)).toBe('der Paketname darf höchstens 64 Kleinbuchstaben, Ziffern und \'-\' haben und muss mit einem Buchstaben beginnen');
  });

  test('a refusal whose code has no words is given in its English', () => {
    expect(refusedIn(textsIn('ru'), { code: 'not-a-code-of-the-packages', error: 'git said no', values: {} })).toBe('git said no');
  });
});

describe('the refusals of the packages server', () => {
  test("a refusal shows in the UI's language by its code, with its values, and keeps its code, values and English", async () => {
    const refused = COMMIT_REFUSALS.changed({ path: 'orders/flows/a.yaml' });
    await inLanguage('cs', async () => {
      const error = await refusalOf(answering(412, refused).get('orders'));
      expect(error).toMatchObject({ status: 412, code: 'commit-path-changed', values: { path: 'orders/flows/a.yaml' }, english: refused.error });
      expect(error.message).toBe('orders/flows/a.yaml se od vaší kontroly změnil; nic nebylo commitnuto');
    });
  });

  test("a refusal of an unknown code shows the server's English, with what git said", async () => {
    const body = { error: 'the server says no', code: 'not-a-code-of-the-packages', values: {}, output: 'fatal: no' };
    await inLanguage('cs', async () => {
      expect(await refusalOf(answering(409, body).get('orders'))).toMatchObject({ status: 409, message: 'the server says no', output: 'fatal: no' });
    });
  });

  test('a refusal that is no JSON shows its status line', async () => {
    const api = httpPackagesApi(() => Promise.resolve(new Response('gateway', { status: 502, statusText: 'Bad Gateway' })));
    expect(await refusalOf(api.list())).toMatchObject({ status: 502, message: '502 Bad Gateway', code: undefined });
  });
});

describe('the codes of the packages', () => {
  const english = textsOf('en');

  test.each(PACKAGE_REFUSAL_CODES.map((code) => [code]))('%s is kebab-case', (code) => {
    expect(code).toMatch(KEBAB);
  });

  test('each code of its own names one refusal, and is neither the shell’s nor the node’s', () => {
    const own = PACKAGE_REFUSAL_CODES.filter((code) => !shell.has(code));
    expect(own.filter((code, n) => own.indexOf(code) !== n)).toEqual([]);
    expect(own.filter((code) => nodeCodes.has(code) || refusalKey(code) in english)).toEqual([]);
  });

  test.each(PACKAGE_REFUSAL_CODES.filter((code) => !shell.has(code)).map((code) => [code]))('%s has words in every language, or is left in English', (code) => {
    if (NOT_TRANSLATED.has(code)) {
      expect(PACKAGE_WORDS[code]).toBeUndefined();
      return;
    }
    const words = PACKAGE_WORDS[code];
    expect(words).toBeDefined();
    for (const language of LANGUAGES) expect(Object.keys(textsOf(language)), language).toContain(words!.id);
  });

  test('each code the packages’ own texts word is worded under its own key', () => {
    const worded = Object.entries(PACKAGE_WORDS).filter(([, words]) => words.id.startsWith('packages.refusal.'));
    for (const [code, words] of worded) expect(words.id).toBe(packageRefusalKey(code));
  });
});
