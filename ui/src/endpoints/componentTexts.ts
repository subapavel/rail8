import { createIntl, createIntlCache, type IntlShape } from 'react-intl';
import type { EndpointSettings } from './settings';

/** One component's translations into one language, as the node serves them: its text keys to ICU messages. */
export type TextBundle = Readonly<Record<string, string>>;

/**
 * The translations of the components shown, in one language: each scheme's bundle, by scheme, the platform's own under
 * {@link PLATFORM}. They are a table of their own, apart from the UI's own texts, so a component named like a module of
 * the UI takes none of the UI's texts.
 */
export interface ComponentTexts {
  language: string;
  bundles: ReadonlyMap<string, TextBundle>;
}

/** The scheme of the platform's own texts, such as the names of its common tabs. */
export const PLATFORM = 'rail8';

/** No translations: every text shows the English the component describes. */
export const ENGLISH: ComponentTexts = { language: 'en', bundles: new Map() };

const cache = createIntlCache();

/** What formats the messages of one bundle, and whether the last message it formatted failed. */
interface Formatter {
  intl: IntlShape;
  failed: boolean;
}

const formatters = new WeakMap<TextBundle, Formatter>();

function formatterOf(language: string, bundle: TextBundle): Formatter {
  let found = formatters.get(bundle);
  if (found === undefined) {
    const made: { intl?: IntlShape; failed: boolean } = { failed: false };
    made.intl = createIntl({ locale: language, defaultLocale: language, messages: bundle, onError: () => (made.failed = true) }, cache);
    found = made as Formatter;
    formatters.set(bundle, found);
  }
  return found;
}

/**
 * The text of `key` in `texts`' language: the message of the bundle the key belongs to, the platform's for a key that
 * starts `rail8.`, the scheme's for one that starts with the scheme and a dot, formatted as ICU with its tags as text.
 * Else `english`: for a text without a key, a key no bundle holds, or a message that does not format.
 */
export function componentText(texts: ComponentTexts, scheme: string, key: string | undefined, english: string): string {
  if (key === undefined) return english;
  const owner = key.startsWith(`${PLATFORM}.`) ? PLATFORM : key.startsWith(`${scheme}.`) ? scheme : undefined;
  const bundle = owner === undefined ? undefined : texts.bundles.get(owner);
  if (bundle === undefined || typeof bundle[key] !== 'string') return english;
  const formatter = formatterOf(texts.language, bundle);
  formatter.failed = false;
  const shown = formatter.intl.formatMessage({ id: key }, undefined, { ignoreTag: true });
  return formatter.failed || typeof shown !== 'string' ? english : shown;
}

/**
 * `settings` of a component of `scheme` as they show in `texts`' language: each tab's label, each field's label and
 * help, and each choice's label that the component describes, translated; the UI's own texts, such as the reply's
 * limit, as they are.
 */
export function translatedSettings(settings: EndpointSettings, scheme: string, texts: ComponentTexts): EndpointSettings {
  if (texts.bundles.size === 0) return settings;
  const say = (key: string | undefined, english: string) => componentText(texts, scheme, key, english);
  return {
    ...settings,
    tabs: settings.tabs.map((tab) => ({ ...tab, label: say(tab.labelKey, tab.label) })),
    fields: settings.fields.map((field) => ({
      ...field,
      ...(typeof field.label === 'string' ? { label: say(field.labelKey, field.label) } : {}),
      ...(typeof field.help === 'string' ? { help: say(field.helpKey, field.help) } : {}),
      ...(field.choiceLabels === undefined
        ? {}
        : { choiceLabels: Object.fromEntries(Object.entries(field.choiceLabels).map(([value, label]) => [value, say(field.choiceKeys?.[value], label)])) }),
    })),
  };
}
