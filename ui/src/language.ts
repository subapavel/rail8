import { useSyncExternalStore, type ReactNode } from 'react';
import { createIntl, createIntlCache, type IntlShape, type PrimitiveType } from 'react-intl';

/** The languages of the UI, English first: it is the default, and the text shown wherever a translation is missing. */
export const LANGUAGES = ['en', 'de', 'cs', 'ru'] as const;
export type Language = (typeof LANGUAGES)[number];

/** Each language's name in itself, as the language switch lists it. */
export const LANGUAGE_NAMES: Readonly<Record<Language, string>> = { en: 'English', de: 'Deutsch', cs: 'Čeština', ru: 'Русский' };

/**
 * The files of translations, `ui/locales/<language>/<module>.json`: each holds the texts of one module of the UI, keyed by
 * ids that start with the module's name and a dot, such as `frame.quickOpen`. A text is an ICU message: `{name}` takes a
 * value, `{count, plural, one {…} few {…} other {…}}` a form for each plural category of the language.
 */
const files = import.meta.glob<Record<string, string>>('../locales/*/*.json', { eager: true, import: 'default' });

/** The texts of one language from every file of translations, keyed by id; the files are read from `files`. */
export function textsOf(language: string, from: Readonly<Record<string, Record<string, string>>> = files): Record<string, string> {
  const texts: Record<string, string> = {};
  for (const [path, messages] of Object.entries(from)) {
    if (path.split('/').at(-2) === language) Object.assign(texts, messages);
  }
  return texts;
}

/**
 * The messages the UI shows in `language`: the English texts, each replaced by the language's own where it has one, so
 * that a language can grow text by text. The texts are read from `from`, the files of translations by default.
 */
export function messagesOf(language: Language, from: Readonly<Record<string, Record<string, string>>> = files): Record<string, string> {
  return { ...textsOf('en', from), ...(language === 'en' ? {} : textsOf(language, from)) };
}

/**
 * The language to start in: `saved`, the one the user chose before, when it is one of the UI's; else the first of the
 * browser's `preferred` languages the UI has, by its primary subtag (`de-AT` is German); else English.
 */
export function pickLanguage(saved: string | null, preferred: readonly string[]): Language {
  const known = (code: string | null | undefined): code is Language => (LANGUAGES as readonly string[]).includes(code ?? '');
  if (known(saved)) return saved;
  for (const tag of preferred) {
    const primary = tag.toLowerCase().split('-')[0];
    if (known(primary)) return primary;
  }
  return 'en';
}

const preference = 'rail8.language';

function initialLanguage(): Language {
  let saved: string | null = null;
  try {
    saved = localStorage.getItem(preference);
  } catch {
    /* Storage is optional. */
  }
  return pickLanguage(saved, typeof navigator === 'undefined' ? [] : navigator.languages);
}

let current: Language = initialLanguage();
if (typeof document !== 'undefined') document.documentElement.lang = current;
const listeners = new Set<() => void>();
const cache = createIntlCache();
const texts = new Map<Language, Texts>();

/** The language the UI shows now. */
export const currentLanguage = (): Language => current;

/** Calls `listener` after every change of the language; gives the function that stops it. */
export function subscribeLanguage(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Shows the UI in `language` from now on, and saves the choice in localStorage when the browser allows it. */
export function chooseLanguage(language: Language): void {
  if (language === current) return;
  current = language;
  document.documentElement.lang = language;
  try {
    localStorage.setItem(preference, language);
  } catch {
    /* Use the choice for this session. */
  }
  for (const listener of listeners) listener();
}

/**
 * The values a text takes: plain values for `t`, and also elements and functions of the text's tags for `rich`. A list
 * of names takes the form of the language for "a, b or c".
 */
export type Values = Record<string, PrimitiveType | readonly string[]>;
export type RichValues = Record<string, PrimitiveType | readonly string[] | ReactNode | ((chunks: ReactNode[]) => ReactNode)>;

/** What shows the texts of one language: `t` and `rich` by id, and `intl` for dates and numbers. */
export interface Texts {
  language: Language;
  /** The text of `id`, with `values` in its places. */
  t: (id: string, values?: Values) => string;
  /** The text of `id` with elements in its places, or with its tags, such as `<b>…</b>`, drawn by the functions of `values`. */
  rich: (id: string, values: RichValues) => ReactNode;
  intl: IntlShape;
}

/** `values` with each list of names in the form of `intl`'s language for "a, b or c". */
function listed<V>(intl: IntlShape, values: Record<string, V | readonly string[]>): Record<string, V | string> {
  const shown: Record<string, V | string> = {};
  for (const [name, value] of Object.entries(values)) {
    shown[name] = Array.isArray(value) ? intl.formatList(value as readonly string[], { type: 'disjunction' }) : (value as V);
  }
  return shown;
}

/** The texts of `language`, with its dates, numbers and plurals. */
export function textsIn(language: Language): Texts {
  let found = texts.get(language);
  if (found === undefined) {
    const intl = createIntl({ locale: language, defaultLocale: 'en', messages: messagesOf(language) }, cache);
    found = {
      language,
      t: (id, values) => intl.formatMessage({ id }, values && listed(intl, values)),
      rich: (id, values) => intl.formatMessage({ id }, listed(intl, values)),
      intl,
    };
    texts.set(language, found);
  }
  return found;
}

/** The texts of the language the UI shows now; the component renders again when the user chooses another. */
export function useText(): Texts {
  return textsIn(useSyncExternalStore(subscribeLanguage, currentLanguage));
}

/** A text to show later, in the language the UI shows then: the id of its message and the values it takes. */
export interface Message {
  id: string;
  values?: Values;
}

/** The text of `id` with `values`, to show later. */
export const msg = (id: string, values?: Values): Message => (values === undefined ? { id } : { id, values });

/** What a screen or a list shows as a name: a name from data, shown as it is, or a message, shown translated. */
export type Label = string | Message;

/** The text of `label` in `texts`' language: a string as it is, a message translated. */
export const say = ({ t }: Texts, label: Label): string => (typeof label === 'string' ? label : t(label.id, label.values));

/** What tells two labels apart: the string itself, or the message's id. */
export const labelKey = (label: Label): string => (typeof label === 'string' ? label : label.id);

/**
 * An instant, an ISO 8601 time, as minutes in UTC in the form of `texts`' language, such as `Nov 20, 2026, 09:00 UTC`
 * or `20.11.2026, 09:00 UTC`; an unreadable instant as it is.
 */
export function instant({ intl }: Texts, iso: string): string {
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return iso;
  return `${intl.formatDate(at, { dateStyle: 'medium', timeStyle: 'short', hourCycle: 'h23', timeZone: 'UTC' })} UTC`;
}
