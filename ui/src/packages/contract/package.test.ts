import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type Change } from '../api';
import { REASONS } from '../rules';
import { httpWorld } from './httpWorld';
import { fakeWorld, type Build, type Seed, type World } from './seed';

const ABSENT = '0123456789abcdef0123456789abcdef01234567';

const FLOW = 'schemaVersion: 1\nkind: flow\nid: orders\nfrom: { endpoint: in }\nsteps:\n  - type: xslt\n    stylesheet: flows/orders.xsl\n';
const ENDPOINT = (id: string) => `schemaVersion: 1\nkind: endpoint\nid: ${id}\nscheme: http\ndirection: in\n`;
const TEST = 'schemaVersion: 1\nkind: test\nflow: orders\ncases:\n  - name: one\n    send: fixtures/order.json\n';
const LARGE = `${'x'.repeat(1024 * 1024)}\n`;

/** A text of several lines that names `what`, so that no two seeded files look alike to git's rename detection. */
const textOf = (what: string) => Array.from({ length: 6 }, (_, n) => `${what} line ${n}`).join('\n') + '\n';

// The read cases of one package on both implementations of PackagesApi: the real git half over real git, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('the %s git half reads a package', (_, build) => {
  let world: World | undefined;
  afterEach(async () => {
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed) => (world = await build(seed));
  const refusalOf = (call: Promise<unknown>) =>
    call.then(
      () => expect.unreachable('the call was not refused'),
      (error: unknown) => {
        expect(error).toBeInstanceOf(PackagesError);
        return error as PackagesError;
      },
    );

  test('its state is its row, its changes against HEAD and its artifacts on disk', async () => {
    const { api } = await open({ clones: [{ folder: 'orders', files: { 'flows/orders.yaml': FLOW } }] });

    const state = await api.get('orders');

    expect(state).toMatchObject({ repository: 'orders', package: { name: 'orders', version: '1.0.0' }, branch: 'main', changed: 0, changes: [], truncated: false });
    expect(state.artifacts).toEqual([
      { path: 'orders/first.txt', kind: 'file' },
      { path: 'orders/flows/orders.yaml', kind: 'flow', id: 'orders' },
      { path: 'orders/package.yaml', kind: 'package' },
    ]);
  });

  test('its changes after an edit, an add, a delete and a rename, with each index state, and each tag the tag of the bytes on disk', async () => {
    const names = ['edit-clean', 'edit-staged', 'edit-differs', 'delete-clean', 'delete-staged', 'rename-staged', 'rename-differs'];
    const { api } = await open({
      clones: [
        {
          folder: 'orders',
          files: { ...Object.fromEntries(names.map((n) => [`${n}.txt`, textOf(n)])), 'flows/orders.yaml': FLOW },
          moves: { 'rename-staged.txt': 'renamed-staged.txt', 'rename-differs.txt': 'renamed-differs.txt' },
          staged: {
            'edit-staged.txt': textOf('edit-staged, staged'),
            'edit-differs.txt': textOf('edit-differs, staged'),
            'add-staged.txt': textOf('add-staged'),
            'add-differs.txt': textOf('add-differs, staged'),
            'delete-staged.txt': null,
          },
          edits: {
            'edit-clean.txt': textOf('edit-clean, edited'),
            'edit-differs.txt': textOf('edit-differs, edited'),
            'add-clean.txt': textOf('add-clean'),
            'add-differs.txt': textOf('add-differs, edited'),
            'delete-clean.txt': null,
            'renamed-differs.txt': `${textOf('rename-differs')}one more line\n`,
            'flows/orders.yaml': FLOW.replace('id: orders', 'id: orders # edited'),
          },
        },
      ],
    });

    const state = await api.get('orders');
    const change = (path: string, status: Change['status'], index: Change['index'], more: Partial<Change> = {}) => ({ path: `orders/${path}`, kind: 'file', status, index, tag: expect.any(String), ...more });

    expect(state.changes).toEqual([
      change('add-clean.txt', 'added', 'clean'),
      change('add-differs.txt', 'added', 'differs'),
      change('add-staged.txt', 'added', 'staged'),
      change('delete-clean.txt', 'deleted', 'clean', { tag: null }),
      change('delete-staged.txt', 'deleted', 'staged', { tag: null }),
      change('edit-clean.txt', 'modified', 'clean'),
      change('edit-differs.txt', 'modified', 'differs'),
      change('edit-staged.txt', 'modified', 'staged'),
      change('flows/orders.yaml', 'modified', 'clean', { kind: 'flow' }),
      change('renamed-differs.txt', 'renamed', 'differs', { renamedFrom: 'orders/rename-differs.txt' }),
      change('renamed-staged.txt', 'renamed', 'staged', { renamedFrom: 'orders/rename-staged.txt' }),
    ]);
    for (const c of state.changes.filter((c) => c.tag !== null)) expect((await api.file('orders', 'work', c.path)).etag).toBe(c.tag);
    expect(state.changed).toBe(state.changes.length);
    expect((await api.changes('orders', state.head!.id, 'work')).changes).toEqual(state.changes);
  });

  test('the changes between two commits, each named by the newer one, with no tag', async () => {
    const { api, id } = await open({
      clones: [
        {
          folder: 'orders',
          files: { 'a.txt': textOf('a'), 'b.txt': textOf('b'), 'c.txt': textOf('c'), 'flows/orders.yaml': FLOW },
          commits: ['first', 'second'],
          commitFiles: { second: { 'a.txt': textOf('a, changed'), 'b.txt': null, 'c.txt': null, 'moved/c.txt': textOf('c'), 'flows/orders.yaml': FLOW.replace('in }', 'other }'), 'big.txt': LARGE } },
        },
      ],
    });
    const first = id('orders', 'first');
    const second = id('orders', 'second');

    const listed = await api.changes('orders', first, second);

    const change = (path: string, status: Change['status'], more: Partial<Change> = {}) => ({ path: `orders/${path}`, kind: 'file', status, index: 'clean', tag: null, ...more });
    expect(listed).toEqual({
      changes: [
        change('a.txt', 'modified'),
        change('b.txt', 'deleted'),
        change('big.txt', 'added', { large: true }),
        change('flows/orders.yaml', 'modified', { kind: 'flow' }),
        change('moved/c.txt', 'renamed', { renamedFrom: 'orders/c.txt' }),
        change('second.txt', 'added'),
      ],
      truncated: false,
    });
  });

  describe('a file and the artifacts', () => {
    const seed: Seed = {
      clones: [
        {
          folder: 'orders',
          files: {
            'flows/orders.yaml': FLOW,
            'flows/orders.xsl': '<xsl:stylesheet/>\n',
            'flows/orders.test.yaml': TEST,
            'fixtures/order.json': '{"id": 1}\n',
            'fixtures/logo.png': 'PNG\0\u0001\u0002',
            'endpoints/in.yaml': ENDPOINT('in'),
            'mappings/order.mapping.json': '{"source": {"bundle": ["../structures/order.xsd"]}}\n',
            'structures/order.xsd': '<xs:schema/>\n',
            'systems/acme-erp.yaml': 'schemaVersion: 1\nkind: system\npartner: acme\nname: erp\n',
            'notes.md': 'nobody names me\n',
            '.hidden/flow.yaml': FLOW,
          },
          edits: { 'endpoints/out.yaml': ENDPOINT('out'), 'flows/orders.yaml': FLOW.replace('in }', 'out }'), 'fixtures/order.json': null },
        },
      ],
    };
    const atCommit = [
      { path: 'orders/endpoints/in.yaml', kind: 'endpoint', id: 'in' },
      { path: 'orders/first.txt', kind: 'file' },
      { path: 'orders/fixtures/logo.png', kind: 'file' },
      { path: 'orders/fixtures/order.json', kind: 'file', usedBy: ['orders/flows/orders.test.yaml'] },
      { path: 'orders/flows/orders.test.yaml', kind: 'test' },
      { path: 'orders/flows/orders.xsl', kind: 'file', usedBy: ['orders/flows/orders.yaml'] },
      { path: 'orders/flows/orders.yaml', kind: 'flow', id: 'orders' },
      { path: 'orders/mappings/order.mapping.json', kind: 'mapping', id: 'order' },
      { path: 'orders/notes.md', kind: 'file' },
      { path: 'orders/package.yaml', kind: 'package' },
      { path: 'orders/structures/order.xsd', kind: 'file', usedBy: ['orders/mappings/order.mapping.json'] },
      { path: 'orders/systems/acme-erp.yaml', kind: 'file' },
    ];

    test('at a commit', async () => {
      const { api, id } = await open(seed);
      const first = id('orders', 'first');

      expect(await api.artifacts('orders', first)).toEqual({ artifacts: atCommit, truncated: false });
      expect(await api.file('orders', first, 'orders/flows/orders.yaml')).toEqual({ text: FLOW, etag: expect.stringMatching(/^"sha256-[0-9a-f]{64}"$/) });
      expect(await api.file('orders', first, 'orders/fixtures/logo.png')).toEqual({ binary: true, size: 6, etag: expect.any(String) });
      expect((await refusalOf(api.file('orders', first, 'orders/endpoints/out.yaml'))).status).toBe(404);
    });

    test('at work, each with its change since HEAD', async () => {
      const { api, id } = await open(seed);

      const work = await api.artifacts('orders', 'work');

      expect(work).toEqual({
        artifacts: [
          atCommit[0],
          { path: 'orders/endpoints/out.yaml', kind: 'endpoint', id: 'out', change: 'added' },
          atCommit[1],
          atCommit[2],
          atCommit[4],
          atCommit[5],
          { ...atCommit[6], change: 'modified' },
          ...atCommit.slice(7),
        ],
        truncated: false,
      });
      expect((await api.get('orders')).artifacts).toEqual(work.artifacts);
      const read = await api.file('orders', 'work', 'orders/flows/orders.yaml');
      expect(read).toMatchObject({ text: FLOW.replace('in }', 'out }') });
      expect(read.etag).not.toBe((await api.file('orders', id('orders', 'first'), 'orders/flows/orders.yaml')).etag);
      expect((await refusalOf(api.file('orders', 'work', 'orders/fixtures/order.json'))).status).toBe(404);
    });
  });

  test('at most 1000 files read for their kind and 1000 other files are listed, on disk and at a commit, and the list says it was cut', async () => {
    const files = Object.fromEntries(Array.from({ length: 1001 }, (_, n) => [`fixtures/${String(n).padStart(4, '0')}.txt`, `${n}\n`]));
    const { api, id } = await open({ clones: [{ folder: 'orders', files: { ...files, 'flows/orders.yaml': FLOW } }] });

    for (const listed of [await api.artifacts('orders', 'work'), await api.artifacts('orders', id('orders', 'first'))]) {
      expect(listed.truncated).toBe(true);
      expect(listed.artifacts.filter((a) => a.kind === 'file')).toHaveLength(1000);
      expect(listed.artifacts.filter((a) => a.kind !== 'file').map((a) => a.path)).toEqual(['orders/flows/orders.yaml', 'orders/package.yaml']);
    }
    expect((await api.get('orders')).truncated).toBe(true);
  });

  test('a change over the read limit is listed as large, and its file is not read', async () => {
    const { api } = await open({ clones: [{ folder: 'orders', edits: { 'big.txt': LARGE } }] });

    const state = await api.get('orders');

    expect(state.changes).toEqual([{ path: 'orders/big.txt', kind: 'file', status: 'added', index: 'clean', tag: expect.any(String), large: true }]);
    expect((await refusalOf(api.file('orders', 'work', 'orders/big.txt'))).status).toBe(413);
  });

  test('HEAD, or any name, is refused as a revision; a commit the clone lacks is 404', async () => {
    const { api, id } = await open({ clones: [{ folder: 'orders' }] });
    const first = id('orders', 'first');

    for (const call of [
      () => api.file('orders', 'HEAD', 'orders/package.yaml'),
      () => api.file('orders', 'main', 'orders/package.yaml'),
      () => api.changes('orders', 'HEAD', 'work'),
      () => api.changes('orders', 'work', 'work'),
      () => api.changes('orders', first, 'HEAD'),
      () => api.artifacts('orders', 'HEAD'),
      () => api.log('orders', { before: 'HEAD' }),
      () => api.file('orders', '-x', 'orders/package.yaml'),
      () => api.file('orders', ':(glob)*', 'orders/package.yaml'),
    ]) {
      expect((await refusalOf(call())).status).toBe(400);
    }
    expect((await refusalOf(api.file('orders', ABSENT, 'orders/package.yaml'))).status).toBe(404);
    expect((await refusalOf(api.changes('orders', ABSENT, 'work'))).status).toBe(404);
  });

  test('a path with a .git segment is 400, one that leaves the package or leads into .git by a link 403, and one outside the package 404', async () => {
    const { api, id } = await open({ clones: [{ folder: 'orders', outside: ['secret.txt'], intoGit: { 'link.txt': 'config', sub: '' } }, { folder: 'billing' }] });

    for (const path of ['orders/.git/config', 'orders/sub/.git/config', 'orders/../billing/package.yaml', 'orders//package.yaml', 'orders/-x/..']) {
      expect((await refusalOf(api.file('orders', 'work', path))).status, path).toBe(400);
    }
    for (const path of ['-x', ':(glob)*', 'orders', 'billing/package.yaml', 'orders/none.txt', 'orders/:(glob)*']) {
      expect((await refusalOf(api.file('orders', 'work', path))).status, path).toBe(404);
    }
    expect((await refusalOf(api.file('orders', id('orders', 'first'), 'orders/.git/config'))).status).toBe(400);
    for (const path of ['orders/secret.txt', 'orders/link.txt', 'orders/sub/config']) {
      expect((await refusalOf(api.file('orders', 'work', path))).status, path).toBe(403);
    }
  });

  test('a file whose name holds a line break is listed among the changes like any other', async () => {
    const { api, id } = await open({ clones: [{ folder: 'orders', files: { 'a\nb.yaml': 'kind: flow\n', 'c.yaml': 'kind: endpoint\n' }, edits: { 'a\nb.yaml': null, 'c.yaml': null } }] });

    const state = await api.get('orders');
    expect(state.changes.map((c) => [c.path, c.status])).toEqual([
      ['orders/a\nb.yaml', 'deleted'],
      ['orders/c.yaml', 'deleted'],
    ]);
    expect(state.changes[1]!.kind).toBe('endpoint');
    expect((await api.artifacts('orders', id('orders', 'first'))).artifacts.map((a) => a.path)).toContain('orders/c.yaml');
  });

  test('a folder that is no package, or no repository name, is refused', async () => {
    const { api } = await open({ clones: [{ folder: 'loose', manifest: null }], plain: ['plain'] });

    expect(await refusalOf(api.get('loose'))).toMatchObject({ status: 404, message: `loose is no package: ${REASONS.noManifest.error}` });
    expect((await refusalOf(api.get('plain'))).status).toBe(404);
    expect((await refusalOf(api.get('none'))).status).toBe(404);
    for (const name of ['-x', '.git', ':(glob)*']) expect((await refusalOf(api.get(name))).status, name).toBe(400);
  });

  test('its history, newest first, page by page, with the commit the node runs marked', async () => {
    const { api, id } = await open({ clones: [{ folder: 'orders', commits: ['first', 'second', 'third'] }], node: [{ repository: 'orders', commit: 'second' }] });

    const page = await api.log('orders', { limit: 2 });

    expect(page.items.map((c) => [c.subject, c.deployed])).toEqual([
      ['third', undefined],
      ['second', true],
    ]);
    expect(page.items[0]).toMatchObject({ id: id('orders', 'third'), parents: [id('orders', 'second')], author: 'Seed Author' });
    expect(page.next).toBe(id('orders', 'first'));
    expect(await api.log('orders', { before: page.next!, limit: 2 })).toMatchObject({ items: [{ id: id('orders', 'first'), parents: [] }], next: null });
    expect((await api.log('orders')).items).toHaveLength(3);
    for (const limit of [0, 101]) expect((await refusalOf(api.log('orders', { limit }))).status).toBe(400);
  });

  test('a clone with no commit yet has no history', async () => {
    const { api } = await open({ clones: [{ folder: 'orders', commits: [] }] });

    expect(await api.log('orders')).toEqual({ items: [], next: null });
  });
});
