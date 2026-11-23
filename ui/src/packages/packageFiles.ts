import { parse } from 'yaml';
import { leaves, same, type SettingChange } from '../leaves';

/** One dependency that differs between two versions, by name: its version and its pin on each side, `none` when absent. */
export interface DependencyChange {
  name: string;
  status: 'added' | 'removed' | 'changed';
  version: { before: string; after: string };
  pin: { before: string; after: string };
}

/**
 * How a `package.yaml`, a `package.lock` or a `folder.yaml` differs between two versions, each read as YAML:
 *
 * - `status`: `added` and `deleted` when one version is missing; `formatting` when the values are equal and the texts
 *   are not; `same` when the texts are equal; `unreadable` when a version is no YAML mapping, as `unreadable` says;
 *   `modified` otherwise.
 * - `fields`: every top-level field but `dependencies`, leaf by leaf.
 * - `dependencies`: each dependency added, removed or changed, by name, in the newer version's order and then the
 *   older one's. A pin is `package.lock`'s repository, commit and content digest; `package.yaml` pins nothing.
 */
export interface PackageFileChanges {
  status: 'added' | 'deleted' | 'modified' | 'formatting' | 'same' | 'unreadable';
  unreadable: { side: 'before' | 'after'; message: string }[];
  fields: SettingChange[];
  dependencies: DependencyChange[];
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** A pin on one line: the repository and the commit, then the content digest. */
function pinOf(entry: Record<string, unknown>): string {
  const { repository, commit, content } = entry;
  if (repository === undefined && commit === undefined && content === undefined) return 'none';
  return `${String(repository)} at ${String(commit)}, ${String(content)}`;
}

/** The dependencies of a version, by name, each with its version and pin. */
function dependenciesOf(doc: Record<string, unknown> | null): Map<string, { version: string; pin: string }> {
  const list = doc !== null && Array.isArray(doc.dependencies) ? doc.dependencies : [];
  return new Map(list.filter(isRecord).map((d) => [String(d.name), { version: d.version === undefined ? 'none' : String(d.version), pin: pinOf(d) }]));
}

/** How the package file changed from `before` to `after`, each its text or null when the file has no version there, as {@link PackageFileChanges} describes. */
export function packageFileChanges(before: string | null, after: string | null): PackageFileChanges {
  const out: PackageFileChanges = { status: 'modified', unreadable: [], fields: [], dependencies: [] };
  if (before === after) return { ...out, status: 'same' };
  const read = (text: string | null, side: 'before' | 'after'): Record<string, unknown> | null => {
    if (text === null) return null;
    try {
      const doc: unknown = parse(text);
      if (isRecord(doc)) return doc;
      out.unreadable.push({ side, message: 'the file holds no YAML mapping' });
    } catch (error) {
      out.unreadable.push({ side, message: `the file is no YAML: ${error instanceof Error ? (error.message.split('\n', 1)[0] ?? '') : String(error)}` });
    }
    return null;
  };
  const older = read(before, 'before');
  const newer = read(after, 'after');
  if (out.unreadable.length > 0) return { ...out, status: 'unreadable' };
  if (older !== null && newer !== null && same(older, newer)) return { ...out, status: 'formatting' };

  const fields = (doc: Record<string, unknown> | null) => (doc === null ? undefined : Object.fromEntries(Object.entries(doc).filter(([k]) => k !== 'dependencies')));
  out.fields = leaves(fields(older), fields(newer));
  const a = dependenciesOf(older);
  const b = dependenciesOf(newer);
  for (const name of [...b.keys(), ...[...a.keys()].filter((n) => !b.has(n))]) {
    const was = a.get(name);
    const is = b.get(name);
    if (was !== undefined && is !== undefined && was.version === is.version && was.pin === is.pin) continue;
    out.dependencies.push({
      name,
      status: was === undefined ? 'added' : is === undefined ? 'removed' : 'changed',
      version: { before: was?.version ?? 'none', after: is?.version ?? 'none' },
      pin: { before: was?.pin ?? 'none', after: is?.pin ?? 'none' },
    });
  }
  return { ...out, status: before === null ? 'added' : after === null ? 'deleted' : 'modified' };
}
