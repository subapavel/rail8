import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type LockBody, type PackageState } from '../api';
import { lockText } from '../fake';
import { packageFileChanges } from '../packageFiles';
import { BUILD_CLI, LOCK_REFUSALS } from '../rules';
import { httpWorld } from './httpWorld';
import { fakeWorld, type Build, type CloneSeed, type Seed, type World } from './seed';

const DIGEST = /^sha256:[0-9a-f]{64}$/;
const OLD_CONTENT = `sha256:${'0'.repeat(64)}`;

/** A package.yaml of `orders` that declares the dependencies `names`, each at version 1.0.0. */
const ordersDeclaring = (...names: string[]) =>
  `name: orders\nversion: 1.0.0\ndependencies:\n${names.map((n) => `  - name: ${n}\n    version: 1.0.0\n`).join('')}`;

// The lock cases on both implementations of PackagesApi: the real git half over real git with a fake of rail8 lock, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('the %s git half locks', (_name, build) => {
  let world: World | undefined;
  afterEach(async () => {
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed) => (world = await build(seed));
  /** The package `orders`, declaring `inventory`, and the clones `others` beside it. */
  const orders = (others: CloneSeed[], seed: Seed = {}, clone: Omit<CloneSeed, 'folder'> = {}) =>
    open({ ...seed, clones: [{ folder: 'orders', manifest: ordersDeclaring('inventory'), ...clone }, ...others] });
  const refusalOf = (call: Promise<unknown>) =>
    call.then(
      () => expect.unreachable('the call was not refused'),
      (error: unknown) => {
        expect(error).toBeInstanceOf(PackagesError);
        return error as PackagesError;
      },
    );
  /** The lock of what `state` shows. */
  const bodyOf = (state: PackageState): LockBody => ({
    manifestTag: state.manifestTag,
    lockTag: state.lockTag,
    dependencies: state.dependencies.map((d) => ({ name: d.name, folder: d.candidate?.folder ?? null, head: d.candidate?.head ?? null })),
  });
  const lockOnDisk = async (api: World['api']) => {
    const file = await api.file('orders', 'work', 'orders/package.lock').catch(() => null);
    return file !== null && 'text' in file ? file.text : null;
  };

  test('pins an unpinned dependency to its candidate at its HEAD, and package.lock shows among the changes by its pins', async () => {
    const { api } = await orders([{ folder: 'inventory' }, { folder: 'stock' }]);
    const before = await api.get('orders');
    const head = world!.id('inventory', 'first');
    expect(before.dependencies).toEqual([{ name: 'inventory', version: '1.0.0', pin: null, candidate: { folder: 'inventory', head }, state: 'unpinned' }]);
    expect(before.lockTag).toBeNull();

    const answer = await api.lock('orders', bodyOf(before));

    expect(answer.pins).toEqual([{ name: 'inventory', version: '1.0.0', repository: 'inventory', commit: head, content: expect.stringMatching(DIGEST) as string }]);
    const after = await api.get('orders');
    expect(answer.package).toEqual(after);
    expect(after.dependencies).toEqual([
      { name: 'inventory', version: '1.0.0', pin: { repository: 'inventory', commit: head, content: answer.pins[0]!.content }, candidate: { folder: 'inventory', head }, state: 'pinned' },
    ]);
    expect(after.lockTag).not.toBeNull();
    expect(after.changes.find((c) => c.path === 'orders/package.lock')).toMatchObject({ kind: 'lock', status: 'added' });
    const text = await lockOnDisk(api);
    expect(text).toBe(lockText(answer.pins));
    expect(packageFileChanges(null, text).dependencies).toEqual([
      { name: 'inventory', status: 'added', version: { before: 'none', after: '1.0.0' }, pin: { before: 'none', after: `inventory at ${head}, ${answer.pins[0]!.content}` } },
    ]);
  });

  test('a dependency whose candidate is at another commit than its pin is moved, and a lock pins its HEAD', async () => {
    // the commit ids are known once the world is built, so the pin of first is saved over a seeded one
    const { api } = await orders([{ folder: 'inventory', commits: ['first', 'second'] }], {}, {
      files: { 'package.lock': lockText([{ name: 'inventory', version: '1.0.0', repository: 'inventory', commit: '0'.repeat(40), content: OLD_CONTENT }]) },
    });
    const first = world!.id('inventory', 'first');
    const second = world!.id('inventory', 'second');
    const seeded = await api.file('orders', 'work', 'orders/package.lock');
    const pinned = lockText([{ name: 'inventory', version: '1.0.0', repository: 'inventory', commit: first, content: OLD_CONTENT }]);
    expect(await world!.save('orders/package.lock', pinned, seeded.etag)).toBe(200);
    const before = await api.get('orders');
    expect(before.dependencies[0]).toMatchObject({ pin: { repository: 'inventory', commit: first, content: OLD_CONTENT }, candidate: { folder: 'inventory', head: second }, state: 'moved' });

    const answer = await api.lock('orders', bodyOf(before));

    expect(answer.pins).toMatchObject([{ repository: 'inventory', commit: second }]);
    expect(answer.package!.dependencies[0]!.state).toBe('pinned');
    expect(packageFileChanges(pinned, await lockOnDisk(api)).dependencies).toMatchObject([
      { name: 'inventory', status: 'changed', pin: { before: `inventory at ${first}, ${OLD_CONTENT}`, after: `inventory at ${second}, ${answer.pins[0]!.content}` } },
    ]);
  });

  test('is refused with 412, writing nothing, once a dependency’s HEAD moved since the author looked', async () => {
    const { api } = await orders([{ folder: 'inventory', edits: { 'more.txt': 'more\n' } }]);
    const before = await api.get('orders');
    const inventory = await api.get('inventory');
    await api.commit('inventory', {
      message: 'More',
      branch: inventory.branch!,
      head: inventory.head!.id,
      paths: [{ path: 'inventory/more.txt', tag: inventory.changes.find((c) => c.path === 'inventory/more.txt')!.tag }],
    });

    const refusal = await refusalOf(api.lock('orders', bodyOf(before)));

    expect([refusal.status, refusal.message]).toEqual([412, LOCK_REFUSALS.stale.error]);
    expect(await lockOnDisk(api)).toBeNull();
  });

  test('is refused with 412, writing nothing, once another folder became a dependency’s candidate', async () => {
    const { api } = await orders([{ folder: 'inventory' }, { folder: 'stock' }]);
    const before = await api.get('orders');
    const inventory = await api.file('inventory', 'work', 'inventory/package.yaml');
    const stock = await api.file('stock', 'work', 'stock/package.yaml');
    expect(await world!.save('inventory/package.yaml', 'name: inventory-old\nversion: 1.0.0\n', inventory.etag)).toBe(200);
    expect(await world!.save('stock/package.yaml', 'name: inventory\nversion: 1.0.0\n', stock.etag)).toBe(200);
    expect((await api.get('orders')).dependencies[0]!.candidate).toMatchObject({ folder: 'stock' });

    const refusal = await refusalOf(api.lock('orders', bodyOf(before)));

    expect([refusal.status, refusal.message]).toEqual([412, LOCK_REFUSALS.stale.error]);
    expect(await lockOnDisk(api)).toBeNull();
  });

  test('is refused with 412, writing nothing, once package.yaml changed since the author looked', async () => {
    const { api } = await orders([{ folder: 'inventory' }]);
    const before = await api.get('orders');
    const manifest = await api.file('orders', 'work', 'orders/package.yaml');
    expect(await world!.save('orders/package.yaml', `${ordersDeclaring('inventory')}description: more\n`, manifest.etag)).toBe(200);

    const refusal = await refusalOf(api.lock('orders', bodyOf(before)));

    expect([refusal.status, refusal.message]).toEqual([412, LOCK_REFUSALS.stale.error]);
    expect(await lockOnDisk(api)).toBeNull();
  });

  test('an unpinned dependency with two candidates is missing, and a lock is refused with 409 naming both', async () => {
    const twin = 'name: inventory\nversion: 1.0.0\n';
    const { api } = await orders([
      { folder: 'inventory', manifest: twin },
      { folder: 'inventory-copy', manifest: twin, files: { 'copy.txt': 'a copy\n' } },
    ]);
    const before = await api.get('orders');
    expect(before.dependencies).toEqual([{ name: 'inventory', version: '1.0.0', pin: null, candidate: null, state: 'missing' }]);

    const refusal = await refusalOf(api.lock('orders', bodyOf(before)));

    expect([refusal.status, refusal.message]).toEqual([409, LOCK_REFUSALS.several({ dependency: 'inventory@1.0.0', folders: 'inventory, inventory-copy' }).error]);
    expect(await lockOnDisk(api)).toBeNull();
  });

  test('a dependency whose files differ from its HEAD is changed, and rail8 lock refuses it with its problems (422)', async () => {
    const { api } = await orders([{ folder: 'inventory', edits: { 'first.txt': 'edited\n' } }]);
    const before = await api.get('orders');
    const head = world!.id('inventory', 'first');
    expect(before.dependencies[0]).toMatchObject({ candidate: { folder: 'inventory', head }, state: 'changed' });

    const refusal = await refusalOf(api.lock('orders', bodyOf(before)));
    expect([refusal.status, refusal.message, refusal.problems]).toEqual([422, LOCK_REFUSALS.problems.error, [`dependency inventory@1.0.0: inventory differs from its HEAD ${head}: commit it first`]]);
    expect(await lockOnDisk(api)).toBeNull();
  });

  test('a dependency no folder holds is missing, with no candidate', async () => {
    const { api } = await orders([{ folder: 'stock' }]);
    expect((await api.get('orders')).dependencies).toEqual([{ name: 'inventory', version: '1.0.0', pin: null, candidate: null, state: 'missing' }]);
  });

  test('each dependency shows what the node resolved for it when it runs the package', async () => {
    const { api } = await orders([{ folder: 'inventory' }], {
      node: [{ repository: 'orders', commit: 'first', dependencies: [{ name: 'inventory', repository: 'inventory', commit: 'first', content: OLD_CONTENT }] }],
    });
    expect((await api.get('orders')).dependencies[0]!.running).toEqual({ commit: world!.id('inventory', 'first'), content: OLD_CONTENT });
  });

  test('is refused with 403 on a read-only working folder', async () => {
    const { api } = await orders([{ folder: 'inventory' }], { readOnly: true });
    const refusal = await refusalOf(api.lock('orders', bodyOf(await api.get('orders'))));
    expect([refusal.status, refusal.message]).toEqual([403, LOCK_REFUSALS.readOnly.error]);
  });

  test('is refused with 403 from a page of another site', async () => {
    const { api } = await orders([{ folder: 'inventory' }], { foreignPage: true });
    const refusal = await refusalOf(api.lock('orders', { manifestTag: null, lockTag: null, dependencies: [] }));
    expect(refusal.status).toBe(403);
  });

  test('is refused with 503 that says how to build the command line when it is missing', async () => {
    const { api } = await orders([{ folder: 'inventory' }], { cli: 'missing' });
    const refusal = await refusalOf(api.lock('orders', bodyOf(await api.get('orders'))));
    expect(refusal.status).toBe(503);
    expect(refusal.message).toContain(BUILD_CLI);
    expect(await lockOnDisk(api)).toBeNull();
  });
});
