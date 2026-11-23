import { load } from 'js-yaml';
import { leaves, same, type SettingChange } from '../leaves';
import { EndpointFileError, readEndpoint, type EndpointDocument } from './files/endpointYaml';
import type { SettingsDescription } from './settings';

/** One changed setting of an endpoint: its key path, the title it is shown under, and its two values on one line each; `none` when absent. */
export interface EndpointSettingChange extends SettingChange {
  title: string;
}

/**
 * How an endpoint file differs between two versions, each read as the settings form reads it:
 *
 * - `status`: `added` and `deleted` when one version is missing; `formatting` when the values are equal and the texts
 *   are not; `same` when the texts are equal; `unreadable` when a version is no endpoint file the form reads, as
 *   `unreadable` says; `modified` otherwise.
 * - `envelope`: the scheme and the direction first, then every other top-level key but `config` and `environments`.
 * - `config`: the component's options, leaf by leaf.
 * - `environments`: each environment whose overlay is only in one version, or differs, in the newer version's order
 *   and then the older one's, with its options leaf by leaf.
 *
 * An added or deleted endpoint lists every setting it has against none.
 */
export interface EndpointChanges {
  status: 'added' | 'deleted' | 'modified' | 'formatting' | 'same' | 'unreadable';
  unreadable: { side: 'before' | 'after'; message: string }[];
  envelope: SettingChange[];
  config: EndpointSettingChange[];
  environments: { name: string; status: 'added' | 'removed' | 'changed'; config: EndpointSettingChange[] }[];
}

/** A value of an option of the type `secret` that is no `secret://` name is shown as this, never as written. */
export const HIDDEN = '(hidden)';

const REFERENCE = /^"secret:\/\/[^"]*"$/;

/** The envelope of an endpoint file: its top-level keys but `config` and `environments`, scheme and direction first. */
function envelopeOf(doc: Record<string, unknown> | null): Record<string, unknown> {
  if (doc === null) return {};
  const rest = Object.entries(doc).filter(([k]) => !['scheme', 'direction', 'config', 'environments'].includes(k));
  return { scheme: doc.scheme, direction: doc.direction, ...Object.fromEntries(rest) };
}

/**
 * The options that differ between `before` and `after`, leaf by leaf, each under the title of its field in the
 * component's settings description: the tab's label and the field's name, as the settings form shows them, followed
 * by the rest of the key path. A key the description has no field for, or every key when there is no description, is
 * shown under its key path. The value of a `secret` field that is no `secret://` name reads {@link HIDDEN}.
 */
function optionsChanges(before: Record<string, unknown>, after: Record<string, unknown>, description: SettingsDescription | undefined): EndpointSettingChange[] {
  const fields = new Map((description?.described === true ? description.fields : []).map((f) => [f.name, f]));
  const tabs = new Map((description?.tabs ?? []).map((t) => [t.id, t.label]));
  return leaves(before, after).map((change) => {
    const name = /^[^.[]*/.exec(change.key)![0];
    const field = fields.get(name);
    if (field === undefined) return { ...change, title: change.key };
    const tab = tabs.get(field.tab);
    const title = `${tab === undefined ? '' : `${tab} › `}${field.name}${change.key.slice(name.length)}`;
    const hide = (value: string) => (field.type === 'secret' && value !== 'none' && !REFERENCE.test(value) ? HIDDEN : value);
    return { ...change, title, before: hide(change.before), after: hide(change.after) };
  });
}

/**
 * How the endpoint file changed from `before` to `after`, each its text or null when the file has no version there,
 * as {@link EndpointChanges} describes. `descriptions` holds the settings descriptions of the components the node
 * serves, by scheme; the options are titled by the description of the newer version's scheme, or of the older one's
 * for a deleted endpoint.
 */
export function endpointChanges(before: string | null, after: string | null, descriptions: ReadonlyMap<string, SettingsDescription>): EndpointChanges {
  const out: EndpointChanges = { status: 'modified', unreadable: [], envelope: [], config: [], environments: [] };
  if (before === after) return { ...out, status: 'same' };
  const read = (text: string | null, side: 'before' | 'after'): { doc: Record<string, unknown>; endpoint: EndpointDocument } | null => {
    if (text === null) return null;
    try {
      const endpoint = readEndpoint(text);
      if (endpoint !== null) return { doc: load(text) as Record<string, unknown>, endpoint };
      out.unreadable.push({ side, message: 'the file is not an endpoint' });
    } catch (error) {
      out.unreadable.push({ side, message: error instanceof EndpointFileError ? error.message : String(error) });
    }
    return null;
  };
  const older = read(before, 'before');
  const newer = read(after, 'after');
  if (out.unreadable.length > 0) return { ...out, status: 'unreadable' };
  if (older !== null && newer !== null && same(older.doc, newer.doc)) return { ...out, status: 'formatting' };

  const description = descriptions.get((newer ?? older)!.endpoint.scheme);
  out.envelope = leaves(older === null ? undefined : envelopeOf(older.doc), newer === null ? undefined : envelopeOf(newer.doc));
  out.config = optionsChanges(older?.endpoint.config ?? {}, newer?.endpoint.config ?? {}, description);
  const a = new Map((older?.endpoint.environments ?? []).map((e) => [e.name, e.config]));
  const b = new Map((newer?.endpoint.environments ?? []).map((e) => [e.name, e.config]));
  for (const name of [...b.keys(), ...[...a.keys()].filter((n) => !b.has(n))]) {
    const config = optionsChanges(a.get(name) ?? {}, b.get(name) ?? {}, description);
    const status = !a.has(name) ? 'added' : !b.has(name) ? 'removed' : 'changed';
    if (status !== 'changed' || config.length > 0) out.environments.push({ name, status, config });
  }
  return { ...out, status: before === null ? 'added' : after === null ? 'deleted' : 'modified' };
}
