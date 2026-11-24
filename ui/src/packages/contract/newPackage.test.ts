import { chmod, mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type NewPackageAnswer } from '../api';
import { lockText } from '../fake';
import { NEW_PACKAGE_REFUSALS, REASONS } from '../rules';
import { httpWorld } from './httpWorld';
import { AUTHOR, fakeWorld, type Build, type Seed, type World } from './seed';

const refusalOf = (answer: Promise<unknown>) =>
  answer.then(
    () => expect.unreachable('the new package was not refused'),
    (error: unknown) => {
      expect(error).toBeInstanceOf(PackagesError);
      return error as PackagesError;
    },
  );

// New package, the same cases on both implementations of PackagesApi: the real git half over real git, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('New package on the %s git half', (_, build) => {
  let world: World | undefined;
  afterEach(async () => {
    // nothing hidden is left behind, before the world deletes its folder
    if (world !== undefined) expect(await world.leftovers()).toEqual([]);
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed) => (world = await build(seed));

  test('a new package is a clone on main with one commit by the author, nothing changed, no upstream, and is listed', async () => {
    const { api } = await open({});

    const answer = await api.create('orders', 'orders-app');

    expect(answer).toMatchObject({
      repository: 'orders',
      package: {
        repository: 'orders',
        package: { name: 'orders-app', version: '0.1.0' },
        branch: 'main',
        head: { parents: [], subject: 'Create package orders-app', author: AUTHOR.name },
        upstream: null,
        busy: null,
        changed: 0,
        changes: [],
        dependencies: [],
      },
    });
    const made = (answer as Extract<NewPackageAnswer, { package: object }>).package;
    expect(await api.get('orders')).toEqual(made);
    expect((await api.list()).packages.map((p) => p.repository)).toEqual(['orders']);
    const head = made.head!.id;
    expect((await api.log('orders')).items.map((c) => c.id)).toEqual([head]);
    expect(await api.file('orders', head, 'orders/package.yaml')).toMatchObject({ text: 'schemaVersion: 1\nkind: package\nname: orders-app\nversion: 0.1.0\ndependencies: []\n' });
    expect(await api.file('orders', head, 'orders/package.lock')).toMatchObject({ text: lockText([]) });
    expect(await api.file('orders', head, 'orders/.gitattributes')).toMatchObject({ text: '* -text\n' });
  });

  test.each([
    ['a package', { clones: [{ folder: 'orders' }] }, { packages: ['orders'], others: [] }],
    ['a folder that is no package', { plain: ['orders'] }, { packages: [], others: [{ folder: 'orders', reason: REASONS.noClone }] }],
  ])('a new package over %s of that name is refused with 409, and the folder stays as it was', async (_, seed, listed) => {
    const { api } = await open(seed);
    const before = await api.list();

    expect(await refusalOf(api.create('orders', 'orders'))).toMatchObject({ status: 409, message: NEW_PACKAGE_REFUSALS.exists });
    const after = await api.list();
    expect(after).toEqual(before);
    expect({ packages: after.packages.map((p) => p.repository), others: after.others }).toEqual(listed);
  });

  test('without a git identity it is refused with 422, and nothing is made', async () => {
    const { api } = await open({ identity: null });

    expect(await refusalOf(api.create('orders', 'orders'))).toMatchObject({ status: 422, message: NEW_PACKAGE_REFUSALS.noIdentity });
    expect(await api.list()).toMatchObject({ packages: [], others: [] });
  });

  test.each(['-x', '..', '.hidden', '.', '', 'a/b', 'x'.repeat(101)])('the repository name %j is refused with 400, and nothing is made', async (repository) => {
    const { api } = await open({});

    expect(await refusalOf(api.create(repository, 'orders'))).toMatchObject({ status: 400, message: NEW_PACKAGE_REFUSALS.repository });
    expect(await api.list()).toMatchObject({ packages: [], others: [] });
  });

  test.each(['-x', '..', '.hidden', 'Orders', '1orders', 'orders_app', '', 'x'.repeat(65)])('the package name %j is refused with 422, and nothing is made', async (name) => {
    const { api } = await open({});

    expect(await refusalOf(api.create('orders', name))).toMatchObject({ status: 422, message: NEW_PACKAGE_REFUSALS.name });
    expect(await api.list()).toMatchObject({ packages: [], others: [] });
  });

  test('a package name of 64 characters is taken', async () => {
    const { api } = await open({});

    expect(await api.create('orders', `o${'x'.repeat(63)}`)).toMatchObject({ package: { package: { name: `o${'x'.repeat(63)}` } } });
  });

  test('a read-only server refuses it with 403, and so does the page of another site', async () => {
    const readOnly = await open({ readOnly: true });
    expect(await refusalOf(readOnly.api.create('orders', 'orders'))).toMatchObject({ status: 403, message: NEW_PACKAGE_REFUSALS.readOnly });
    await readOnly.close();
    world = undefined;

    const foreign = await open({ foreignPage: true });
    expect((await refusalOf(foreign.api.create('orders', 'orders'))).status).toBe(403);
  });

  test('without a working folder it is refused with 404', async () => {
    const { api } = await open({ workspace: false });

    expect((await refusalOf(api.create('orders', 'orders'))).status).toBe(404);
  });

  test('it holds the working folder’s lock', async () => {
    const { api, held } = await open({});
    const holding = held();

    expect(await api.create('orders', 'orders')).toMatchObject({ package: { repository: 'orders' } });
    await holding;
  });
});

// What only the real git half shows: the wire, its hooks and signing, and a server that closes.
describe('New package on the real git half', () => {
  let world: World | undefined;
  let dir: string | undefined;
  afterEach(async () => {
    if (world !== undefined) expect(await world.leftovers()).toEqual([]);
    await world?.close();
    world = undefined;
    if (dir !== undefined) await rm(dir, { recursive: true, force: true });
    dir = undefined;
  });

  const open = async (seed: Seed) => (world = await httpWorld(seed));

  test('answers 201 on the wire, with the new folder and its state', async () => {
    const { wire } = await open({});

    expect(await wire!('/git/packages', { repository: 'orders', name: 'orders' })).toMatchObject({ status: 201, body: { repository: 'orders', package: { repository: 'orders', branch: 'main' } } });
  });

  test.each([[null], [[]], [{ repository: 'orders' }], [{ repository: 'orders', name: 7 }], [{ repository: 1, name: 'orders' }]])('the body %j is refused with 400', async (body) => {
    const { wire, workingFolder } = await open({});

    expect(await wire!('/git/packages', body)).toEqual({ status: 400, body: { error: NEW_PACKAGE_REFUSALS.body } });
    expect(await readdir(workingFolder!)).toEqual([]);
  });

  test('a pre-commit hook of the author that refuses is 422 with what it said, and nothing is made', async () => {
    dir = await mkdtemp(join(tmpdir(), 'rail8-hooks-'));
    await writeFile(join(dir, 'pre-commit'), '#!/bin/sh\necho "no commits today" >&2\nexit 1\n');
    await chmod(join(dir, 'pre-commit'), 0o755);
    const { api, workingFolder } = await open({ author: { config: `[core]\n\thooksPath = ${dir}` } });

    expect(await refusalOf(api.create('orders', 'orders'))).toMatchObject({ status: 422, message: NEW_PACKAGE_REFUSALS.gitFailed, output: expect.stringContaining('no commits today') });
    expect(await readdir(workingFolder!)).toEqual([]);
  });

  test('a server closed before it refuses it with 503 and makes nothing', async () => {
    const { api, closeGit, workingFolder } = await open({});

    await closeGit!();

    expect((await refusalOf(api.create('orders', 'orders'))).status).toBe(503);
    expect(await readdir(workingFolder!)).toEqual([]);
  });

  test('a server closed while git commits stops it and leaves nothing', async () => {
    const { api, closesWhile, workingFolder } = await open({});
    closesWhile!('commit');

    expect((await refusalOf(api.create('orders', 'orders'))).status).toBe(503);
    expect(await readdir(workingFolder!)).toEqual([]);
  });

  test('a state that cannot be read once the folder is in place is still a 201, with the problem', async () => {
    const { api, closeGit, atNextLock, workingFolder } = await open({});
    let closed: Promise<void> | undefined;
    atNextLock!({
      after: async () => {
        closed = closeGit!();
      },
    });

    const answer = await api.create('orders', 'orders');
    await closed;

    expect(answer).toMatchObject({ repository: 'orders', package: null, problem: expect.stringContaining('closing') });
    expect(await readdir(workingFolder!)).toEqual(['orders']);
  });
});
