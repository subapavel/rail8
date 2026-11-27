import { createIntl, createIntlCache, type IntlShape } from 'react-intl';
import { currentLanguage, messagesOf, type Language, type Message } from './language';

/** The values a refusal names, by name: raw strings and numbers, never text to show as it is. */
export type RefusalValues = Readonly<Record<string, string | number>>;

/** What the body of a refusal says: its stable code, the values its text names, and its English text. */
export interface Refusal {
  code: string | undefined;
  values: RefusalValues;
  english: string | undefined;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * What `body` says, as the node and the UI's server refuse: `{error, code, values}`, or an RFC 9457 problem whose text
 * is its `detail` and whose code is its `code`, or else the last part of its `type` (`urn:rail8:mapping:problem:<code>`).
 * A part that is missing or of another shape is undefined, or no values.
 */
export function refusalOf(body: unknown): Refusal {
  if (!isRecord(body)) return { code: undefined, values: {}, english: undefined };
  const english = typeof body.error === 'string' ? body.error : typeof body.detail === 'string' ? body.detail : undefined;
  let code = typeof body.code === 'string' && body.code !== '' ? body.code : undefined;
  if (code === undefined && typeof body.type === 'string' && body.type.startsWith('urn:rail8:')) code = body.type.slice(body.type.lastIndexOf(':') + 1);
  const values: Record<string, string | number> = {};
  if (isRecord(body.values)) {
    for (const [name, value] of Object.entries(body.values)) if (typeof value === 'string' || typeof value === 'number') values[name] = value;
  }
  return { code, values, english };
}

/** The message id of the UI's own text for the refusal `code`, in `ui/locales/<language>/refusal.json`. */
export const refusalKey = (code: string) => `refusal.${code}`;

/** Each language's formatter of refusals, and whether its last text lacked a value. */
const formatters = new Map<Language, { intl: IntlShape; failed: boolean }>();
const cache = createIntlCache();

function formatterOf(language: Language) {
  let found = formatters.get(language);
  if (found === undefined) {
    const made: { intl?: IntlShape; failed: boolean } = { failed: false };
    made.intl = createIntl({ locale: language, defaultLocale: 'en', messages: messagesOf(language), onError: () => (made.failed = true) }, cache);
    found = made as { intl: IntlShape; failed: boolean };
    formatters.set(language, found);
  }
  return found;
}

/**
 * The UI's own text for the refusal `body` in `language` (the one the UI shows now by default): `words[code]` when the
 * caller's module words its code, else `refusal.<code>`, filled with the refusal's values. Undefined when the body has no
 * code, when the UI has no text for it, or when the body lacks a value the text needs.
 */
export function ownRefusalText(body: unknown, words: Readonly<Record<string, Message>> = {}, language: Language = currentLanguage()): string | undefined {
  const { code, values } = refusalOf(body);
  if (code === undefined) return undefined;
  const message: Message | undefined = Object.hasOwn(words, code) ? words[code] : { id: refusalKey(code) };
  const formatter = formatterOf(language);
  if (message === undefined || !(message.id in formatter.intl.messages)) return undefined;
  formatter.failed = false;
  const text = formatter.intl.formatMessage({ id: message.id }, { ...values, ...(message.values as RefusalValues | undefined) });
  return formatter.failed ? undefined : text;
}

/**
 * The text of the refusal `body` in `language`: the UI's own text for it (see {@link ownRefusalText}), else the English
 * text the body carries. Undefined when the body carries no text and the UI has none for it.
 */
export function refusalText(body: unknown, words: Readonly<Record<string, Message>> = {}, language: Language = currentLanguage()): string | undefined {
  return ownRefusalText(body, words, language) ?? refusalOf(body).english;
}

/**
 * The codes of refusals the UI shows in the node's or its server's English, each with why in its comment: their text carries what a
 * parser or git said, which no fixed text can say, or no screen of the UI can cause them. Every other code a user can
 * meet has a text in `ui/locales/<language>/refusal.json`, or in the words of the module whose server answers it.
 */
export const NOT_TRANSLATED: ReadonlySet<string> = new Set([
  'bad-request', // a request of another shape, which no screen sends; its text says what is wrong with it
  'no-texts', // a form shows its component's English where the node has no translations, and never this refusal
  'invalid-request', // a request of another shape, which no screen sends; its text says what is wrong with it
  'malformed-input', // its text says where the parser found the test input malformed
  'endpoint-file-refused', // its text says what the endpoint file parser refused
  'flow-file-refused', // its text says what the flow file parser refused
  'git-version-failed', // its text carries what git --version said
  'git-version-unknown', // its text carries what git --version answered
  'program-not-started', // its text carries the error of the system that did not start git or the command line
  'undo-calls', // an internal limit of the server, which no screen can reach
  'commit-failed-noted', // its text joins git's or the runner's words, what the undo found, the paths and a command
  'discard-unread', // its text carries git's error
  'check-node-unreachable', // its text carries the connection's failure
  'deploy-node-unreachable', // its text carries the connection's failure
  'node-refused', // the node's own text, which came without a code
  'clone-cleanup-failed', // its text carries the error of the system
  'new-package-cleanup-failed', // its text carries the error of the system
]);
