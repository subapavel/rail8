import { msg, say, type Message, type Texts } from '../language';
import { ADDRESS_REFUSALS, MAX_ADDRESS, NEW_PACKAGE_REFUSALS } from './rules';

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

/**
 * `error`, one of `refusals` as the rules word it in English for the development server too, in `texts`' language when
 * `words` holds that refusal; any other text as it is.
 */
export function refusalIn<K extends string>(texts: Texts, refusals: Readonly<Record<K, string>>, words: Readonly<Partial<Record<K, Message>>>, error: string): string {
  const name = (Object.keys(refusals) as K[]).find((key) => refusals[key] === error);
  const message = name === undefined ? undefined : words[name];
  return message === undefined ? error : say(texts, message);
}
