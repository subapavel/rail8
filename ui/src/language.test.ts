import { parse, TYPE, type MessageFormatElement } from '@formatjs/icu-messageformat-parser';
import { describe, expect, test } from 'vitest';
import { LANGUAGES, messagesOf, pickLanguage, textsIn, textsOf } from './language';

const files = import.meta.glob<Record<string, string>>('../locales/*/*.json', { eager: true, import: 'default' });
const terms = import.meta.glob<string>('../locales/*/terms.md', { eager: true, query: '?raw', import: 'default' });
const english = textsOf('en');

/** The names of the values a message takes, its tags included, in every branch of its plurals and selects. */
function argumentsOf(elements: readonly MessageFormatElement[], into = new Set<string>()): Set<string> {
  for (const element of elements) {
    if (element.type === TYPE.argument || element.type === TYPE.number || element.type === TYPE.date || element.type === TYPE.time) into.add(element.value);
    if (element.type === TYPE.tag) {
      into.add(element.value);
      argumentsOf(element.children, into);
    }
    if (element.type === TYPE.plural || element.type === TYPE.select) {
      into.add(element.value);
      for (const option of Object.values(element.options)) argumentsOf(option.value, into);
    }
  }
  return into;
}

describe('the files of translations', () => {
  test.each(Object.keys(files))('every key of %s starts with the name of its file', (path) => {
    const name = path.split('/').at(-1)!.replace(/\.json$/, '');
    const strays = Object.keys(files[path]!).filter((key) => !key.startsWith(`${name}.`));
    expect(strays).toEqual([]);
  });

  test.each(Object.keys(files))('every text of %s is a message the library reads', (path) => {
    for (const [key, text] of Object.entries(files[path]!)) expect(() => parse(text), key).not.toThrow();
  });

  test.each(LANGUAGES.filter((language) => language !== 'en'))('every key of %s is a key of English, with the same values', (language) => {
    const own = textsOf(language);
    expect(Object.keys(own).filter((key) => !(key in english))).toEqual([]);
    for (const [key, text] of Object.entries(own)) {
      expect([...argumentsOf(parse(text))].sort(), key).toEqual([...argumentsOf(parse(english[key]!))].sort());
    }
  });

  test.each(LANGUAGES.filter((language) => language !== 'en'))('%s keeps the terms of its list in English', (language) => {
    const list = terms[`../locales/${language}/terms.md`];
    expect(list, `ui/locales/${language}/terms.md`).toBeDefined();
    const kept = list!.split('\n').filter((line) => line.startsWith('- ')).map((line) => line.slice(2).trim());
    const own = textsOf(language);
    for (const [key, text] of Object.entries(english)) {
      if (!(key in own)) continue;
      // a term that is the whole text, or that stands inside it with its capital letter, as a name
      const named = kept.filter((term) => text === term || new RegExp(`(?<=\\S.*)\\b${term}\\b`).test(text));
      for (const term of named) expect(own[key], `${key} keeps "${term}"`).toContain(term);
    }
  });
});

describe('messagesOf', () => {
  test('a key that a language lacks shows the English text', () => {
    const fake = { '../locales/en/demo.json': { 'demo.a': 'A', 'demo.b': 'B' }, '../locales/cs/demo.json': { 'demo.a': 'Á' } };
    expect(messagesOf('cs', fake)).toEqual({ 'demo.a': 'Á', 'demo.b': 'B' });
  });

  test('every language holds every English key', () => {
    for (const language of LANGUAGES) expect(Object.keys(messagesOf(language)).sort()).toEqual(Object.keys(english).sort());
  });
});

describe('pickLanguage', () => {
  test('the saved choice wins', () => expect(pickLanguage('ru', ['de-DE'])).toBe('ru'));
  test('without one, the first of the browser\'s languages the UI has, by its primary subtag', () => {
    expect(pickLanguage(null, ['sk-SK', 'cs-CZ', 'de'])).toBe('cs');
    expect(pickLanguage('fr', ['de-AT'])).toBe('de');
  });
  test('else English', () => expect(pickLanguage(null, ['fr-FR', 'pl'])).toBe('en'));
});

describe('textsIn', () => {
  test('Czech takes the plural form of each count', () => {
    const { t } = textsIn('cs');
    expect([1, 3, 5, 22].map((count) => t('frame.diff.folded', { count }))).toEqual([
      '1 nezměněný řádek',
      '3 nezměněné řádky',
      '5 nezměněných řádků',
      '22 nezměněných řádků',
    ]);
  });

  test('Russian takes the plural form of each count', () => {
    const { t } = textsIn('ru');
    expect([1, 3, 5, 21, 112].map((count) => t('frame.diff.folded', { count }))).toEqual([
      '1 неизменённая строка',
      '3 неизменённые строки',
      '5 неизменённых строк',
      '21 неизменённая строка',
      '112 неизменённых строк',
    ]);
  });

  test('numbers and lists follow the language', () => {
    expect(textsIn('de').intl.formatNumber(12345.5)).toBe('12.345,5');
    expect(textsIn('cs').t('frame.access.role', { roles: ['developer', 'operator'] })).toBe('Vyžaduje roli developer nebo operator');
    expect(textsIn('en').t('frame.access.role', { roles: ['developer', 'operator'] })).toBe('Needs the role developer or operator');
  });
});
