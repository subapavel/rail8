import { describe, expect, test } from 'vitest';
import { packageFileChanges } from './packageFiles';

const A = 'a'.repeat(40);
const B = 'b'.repeat(40);
const DIGEST = `sha256:${'1'.repeat(64)}`;

const lock = (entries: { name: string; version: string; commit: string }[]) =>
  `# written by rail8 lock\nschemaVersion: 1\nkind: lock\ndependencies:${entries.length === 0 ? ' []' : ''}\n${entries
    .map((e) => `  - name: "${e.name}"\n    version: "${e.version}"\n    content: "${DIGEST}"\n    repository: "${e.name}-repo"\n    commit: "${e.commit}"\n`)
    .join('')}`;

const manifest = (version: string, dependencies: string) => `schemaVersion: 1\nkind: package\nname: invoice\nversion: ${version}\ndependencies:\n${dependencies}`;

describe('packageFileChanges', () => {
  test('lists a dependency whose pin changed, one added and one removed, by name', () => {
    const before = lock([
      { name: 'common', version: '1.2.0', commit: A },
      { name: 'old', version: '0.1.0', commit: A },
    ]);
    const after = lock([
      { name: 'common', version: '1.2.0', commit: B },
      { name: 'fresh', version: '2.0.0', commit: B },
    ]);

    expect(packageFileChanges(before, after)).toEqual({
      status: 'modified',
      unreadable: [],
      fields: [],
      dependencies: [
        { name: 'common', status: 'changed', version: { before: '1.2.0', after: '1.2.0' }, pin: { before: `common-repo at ${A}, ${DIGEST}`, after: `common-repo at ${B}, ${DIGEST}` } },
        { name: 'fresh', status: 'added', version: { before: 'none', after: '2.0.0' }, pin: { before: 'none', after: `fresh-repo at ${B}, ${DIGEST}` } },
        { name: 'old', status: 'removed', version: { before: '0.1.0', after: 'none' }, pin: { before: `old-repo at ${A}, ${DIGEST}`, after: 'none' } },
      ],
    });
  });

  test('lists the fields of package.yaml and a dependency’s version, which it pins nothing for', () => {
    const changes = packageFileChanges(manifest('1.0.0', '  - { name: common, version: 1.2.0 }\n'), manifest('1.1.0', '  - { name: common, version: 1.3.0 }\n'));

    expect(changes.fields).toEqual([{ key: 'version', before: '"1.0.0"', after: '"1.1.0"' }]);
    expect(changes.dependencies).toEqual([{ name: 'common', status: 'changed', version: { before: '1.2.0', after: '1.3.0' }, pin: { before: 'none', after: 'none' } }]);
  });

  test('lists the fields of a folder.yaml, and every field of an added one', () => {
    const before = 'schemaVersion: 1\nkind: folder\ndescription: Orders\n';
    const after = 'schemaVersion: 1\nkind: folder\nnamespace: "urn:example:orders"\ndescription: Orders as the shop sends them\n';

    expect(packageFileChanges(before, after).fields).toEqual([
      { key: 'description', before: '"Orders"', after: '"Orders as the shop sends them"' },
      { key: 'namespace', before: 'none', after: '"urn:example:orders"' },
    ]);
    expect(packageFileChanges(null, before)).toMatchObject({ status: 'added', fields: [{ key: 'schemaVersion' }, { key: 'kind' }, { key: 'description' }] });
  });

  test('says when only comments or formatting changed, and which version is no YAML mapping', () => {
    const text = lock([{ name: 'common', version: '1.2.0', commit: A }]);

    expect(packageFileChanges(text, text.replace('# written by rail8 lock\n', '')).status).toBe('formatting');
    expect(packageFileChanges(text, '- a\n')).toMatchObject({ status: 'unreadable', unreadable: [{ side: 'after', message: 'the file holds no YAML mapping' }] });
    expect(packageFileChanges('a: [', text).unreadable[0]?.side).toBe('before');
  });
});
