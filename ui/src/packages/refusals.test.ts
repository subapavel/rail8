import { describe, expect, test } from 'vitest';
import { say, textsIn } from '../language';
import { ADDRESS_TEXTS, NEW_PACKAGE_TEXTS, refusalIn } from './refusals';
import { ADDRESS_REFUSALS, cloneSourceOf, NEW_PACKAGE_REFUSALS } from './rules';

describe('the refusals the dialogs find as the author types', () => {
  const english = textsIn('en');

  test.each(Object.entries(ADDRESS_TEXTS))('the English words of the address refusal %s are the rules’ own', (name, message) => {
    expect(say(english, message)).toBe(ADDRESS_REFUSALS[name as keyof typeof ADDRESS_REFUSALS]);
  });

  test.each(Object.entries(NEW_PACKAGE_TEXTS))('the English words of the new package refusal %s are the rules’ own', (name, message) => {
    expect(say(english, message)).toBe(NEW_PACKAGE_REFUSALS[name as keyof typeof NEW_PACKAGE_REFUSALS]);
  });

  test('a refusal of the rules is given in the language of the texts', () => {
    const source = cloneSourceOf('https://git.example.com/team/orders.git main');
    expect('error' in source && refusalIn(textsIn('cs'), ADDRESS_REFUSALS, ADDRESS_TEXTS, source.error)).toBe('vložte jen adresu: obsahuje bílé znaky');
    expect(refusalIn(textsIn('de'), NEW_PACKAGE_REFUSALS, NEW_PACKAGE_TEXTS, NEW_PACKAGE_REFUSALS.name)).toBe(
      'der Paketname darf höchstens 64 Kleinbuchstaben, Ziffern und \'-\' haben und muss mit einem Buchstaben beginnen',
    );
  });

  test('any other text is given as it is', () => {
    expect(refusalIn(textsIn('ru'), NEW_PACKAGE_REFUSALS, NEW_PACKAGE_TEXTS, 'git said no')).toBe('git said no');
  });
});
