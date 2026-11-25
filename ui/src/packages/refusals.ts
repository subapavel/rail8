import { SERVER_CODES } from '../../server/refusal';
import { msg, type Message, type Texts } from '../language';
import { NOT_TRANSLATED, refusalText } from '../refusal';
import { ADDRESS_REFUSALS, MAX_ADDRESS, NEW_PACKAGE_REFUSALS, PACKAGE_REFUSAL_CODES, type Refused } from './rules';

/** The words of each refusal of an address that Get a package finds as the author types, in the UI's languages. */
export const ADDRESS_TEXTS: Readonly<Partial<Record<keyof typeof ADDRESS_REFUSALS, Message>>> = {
  empty: msg('packages.address.empty'),
  space: msg('packages.address.space'),
  long: msg('packages.address.long', { max: MAX_ADDRESS }),
  character: msg('packages.address.character'),
  option: msg('packages.address.option'),
  transport: msg('packages.address.transport'),
  scheme: msg('packages.address.scheme'),
  local: msg('packages.address.local'),
  httpsUser: msg('packages.address.httpsUser'),
  password: msg('packages.address.password'),
  user: msg('packages.address.user'),
  host: msg('packages.address.host'),
  driveLetter: msg('packages.address.driveLetter'),
  port: msg('packages.address.port'),
  path: msg('packages.address.path'),
  noRepository: msg('packages.address.noRepository'),
  folderCharacter: msg('packages.address.folderCharacter'),
  name: msg('packages.address.name'),
  trailingDot: msg('packages.address.trailingDot'),
  device: msg('packages.address.device'),
};

/** The words of the refusals New package finds in its two fields as the author types, in the UI's languages. */
export const NEW_PACKAGE_TEXTS: Readonly<Partial<Record<keyof typeof NEW_PACKAGE_REFUSALS, Message>>> = {
  repository: msg('packages.new.repository.refused'),
  name: msg('packages.new.name.refused'),
};

/** The message id of the packages' own text for the refusal `code`, in `ui/locales/<language>/packages.json`. */
export const packageRefusalKey = (code: string) => `packages.refusal.${code}`;

/** The entries of `words`, the words of some refusals of `refusals`, by the refusals' codes. */
const byCode = <K extends string>(refusals: Readonly<Record<K, { readonly code: string }>>, words: Readonly<Partial<Record<K, Message>>>) =>
  (Object.entries(words) as [K, Message][]).map(([key, message]) => [refusals[key].code, message] as const);

const shell = new Set<string>(SERVER_CODES);

/**
 * The words of every refusal of the packages' records by its code: the packages' own text, `packages.refusal.<code>`,
 * or for a refusal the dialogs find as the author types the words they show. A code of the UI's server is worded by the
 * shell, and a code left in English has no words.
 */
export const PACKAGE_WORDS: Readonly<Record<string, Message>> = Object.fromEntries([
  ...PACKAGE_REFUSAL_CODES.filter((code) => !shell.has(code) && !NOT_TRANSLATED.has(code)).map((code) => [code, msg(packageRefusalKey(code))] as const),
  ...byCode(ADDRESS_REFUSALS, ADDRESS_TEXTS),
  ...byCode(NEW_PACKAGE_REFUSALS, NEW_PACKAGE_TEXTS),
]);

/** `refused`, a refusal of the packages' rules, in `texts`' language, by its code. */
export function refusedIn(texts: Texts, refused: Refused): string {
  return refusalText(refused, PACKAGE_WORDS, texts.language) ?? refused.error;
}
