import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type PackageList, type PackageRow } from '../api';
import { GIT_MISSING, gitTooOld, MAX_PACKAGES, REASONS } from '../rules';
import { httpWorld } from './httpWorld';
import { DEPLOYED_AT, fakeWorld, OLD_GIT, type Build, type Seed, type World } from './seed';

const ABSENT = '0123456789abcdef0123456789abcdef01234567';
const UUID = '0b6f3c1e-7d2a-4c5b-9e8f-1a2b3c4d5e6f';

// The same cases on both implementations of PackagesApi: the real git half over real git, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('the %s git half', (_, build) => {
  let world: World | undefined;
  afterEach(async () => {
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed) => (world = await build(seed));
  const list = async (seed: Seed): Promise<PackageList> => (await open(seed)).api.list();
  const rowOf = (listed: PackageList, repository: string): PackageRow => {
    const row = listed.packages.find((p) => p.repository === repository);
    if (row === undefined) throw new Error(`no package ${repository} in ${JSON.stringify(listed)}`);
    return row;
  };
  const refusalOf = (seed: Seed) =>
    list(seed).then(
      () => expect.unreachable('the list was not refused'),
      (error: unknown) => {
        expect(error).toBeInstanceOf(PackagesError);
        return error as PackagesError;
      },
    );

  test('a clone with package.yaml at its root is a package: its name, version, branch and HEAD', async () => {
    const listed = await list({ clones: [{ folder: 'orders', commits: ['first', 'second'] }] });
    const id = (label: string) => world!.id('orders', label);

    expect(listed).toMatchObject({ configured: true, writable: true, others: [], truncated: false });
    expect(listed.packages).toEqual([
      {
        repository: 'orders',
        package: { name: 'orders', version: '1.0.0' },
        branch: 'main',
        head: { id: id('second'), parents: [id('first')], subject: 'second', author: 'Seed Author', at: expect.stringMatching(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d[+-]\d\d:\d\d$/) },
        upstream: null,
        busy: null,
        changed: 0,
        deployed: { node: 'answered', host: listed.node, relation: 'none' },
      },
    ]);
  });

  // git 2.45 and later write a time in UTC as Z, older ones as +00:00; the git half answers the offset either way
  test.each([
    ['UTC', /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+00:00$/],
    ['Asia/Kolkata', /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+05:30$/],
  ] as const)('a commit made in %s has its time with its offset, in the list, the tab and the history', async (zone, form) => {
    const { api } = await open({ clones: [{ folder: 'orders', zone }] });
    const at = expect.stringMatching(form);

    expect((await api.list()).packages[0]!.head).toMatchObject({ at });
    expect(await api.get('orders')).toMatchObject({ head: { at } });
    expect((await api.log('orders')).items).toEqual([expect.objectContaining({ at })]);
  });

  test('packages come sorted by repository, and a package.yaml that gives no name and version is a problem of its row', async () => {
    const listed = await list({
      clones: [
        { folder: 'shipping', manifest: 'name: shipping\n' },
        { folder: 'billing', manifest: 'name: [billing\n' },
        { folder: 'orders' },
      ],
    });

    expect(listed.packages.map((p) => p.repository)).toEqual(['billing', 'orders', 'shipping']);
    expect(rowOf(listed, 'billing')).toMatchObject({ package: null, problem: expect.stringMatching(/^package\.yaml is no YAML: /) });
    expect(rowOf(listed, 'shipping')).toMatchObject({ package: null, problem: 'package.yaml gives no name and version as text' });
  });

  test('every folder that is no package is listed below the packages, sorted, with the reason; hidden folders are not', async () => {
    const listed = await list({
      clones: [{ folder: 'orders' }, { folder: 'loose', manifest: null }, { folder: 'deep', manifest: null, files: { 'sub/package.yaml': 'name: deep\nversion: 1.0.0\n' } }],
      plain: ['plain', 'bad name', '.hidden'],
      worktrees: [{ folder: 'tree', of: 'orders' }],
      nested: ['inner'],
      links: [{ folder: 'alias', to: 'orders' }],
    });

    expect(listed.packages.map((p) => p.repository)).toEqual(['orders']);
    expect(listed.others).toEqual([
      { folder: 'alias', reason: REASONS.link },
      { folder: 'bad name', reason: REASONS.name },
      { folder: 'deep', reason: REASONS.noManifest },
      { folder: 'inner', reason: REASONS.nested },
      { folder: 'loose', reason: REASONS.noManifest },
      { folder: 'plain', reason: REASONS.noClone },
      { folder: 'tree', reason: REASONS.worktree },
    ]);
  });

  test('a clone with no commit yet has no HEAD, and its files are changed', async () => {
    const listed = await list({ clones: [{ folder: 'orders', commits: [] }], node: [{ repository: 'orders', commit: ABSENT }] });

    expect(rowOf(listed, 'orders')).toMatchObject({ branch: 'main', head: null, upstream: null, changed: 1, deployed: { relation: 'other', commit: ABSENT } });
  });

  test('changed counts every path that differs from HEAD, each untracked file included, and never the temporary file of a save', async () => {
    const listed = await list({
      clones: [
        {
          folder: 'orders',
          commits: ['first', 'second'],
          edits: { 'first.txt': 'edited\n', 'second.txt': null, 'flows/new.yaml': 'kind: flow\n', 'flows/more.yaml': 'kind: flow\n', [`flows/.new.yaml.${UUID}.tmp`]: 'half\n' },
        },
      ],
    });

    expect(rowOf(listed, 'orders').changed).toBe(4);
  });

  test('a merge left open is busy, and its conflict is a changed path', async () => {
    const listed = await list({ clones: [{ folder: 'orders', conflict: true }] });

    expect(rowOf(listed, 'orders')).toMatchObject({ branch: 'main', busy: 'merge', changed: 1 });
  });

  test('the upstream gives the commits ahead and behind as of the last fetch, and its time', async () => {
    const listed = await list({ clones: [{ folder: 'orders', commits: ['first', 'second'], upstream: { at: 'first', more: ['theirs'] } }] });

    expect(rowOf(listed, 'orders').upstream).toEqual({ name: 'origin/main', ahead: 1, behind: 1, fetchedAt: expect.any(String) });
  });

  test('a detached HEAD has no branch and no upstream', async () => {
    const listed = await list({ clones: [{ folder: 'orders', upstream: { at: 'first' }, detached: true }] });

    expect(rowOf(listed, 'orders')).toMatchObject({ branch: null, upstream: null, head: { id: world!.id('orders', 'first') } });
  });

  describe('the deployed state on the node', () => {
    const CASES: { name: string; seed: Seed; deployed: Record<string, unknown>; changed?: number }[] = [
      { name: 'it runs HEAD', seed: { clones: [{ folder: 'orders' }], node: [{ repository: 'orders', commit: 'first' }] }, deployed: { relation: 'deployed', commit: 'first' } },
      {
        name: 'it runs HEAD, and the folder has changes since',
        seed: { clones: [{ folder: 'orders', edits: { 'first.txt': 'edited\n' } }], node: [{ repository: 'orders', commit: 'first' }] },
        deployed: { relation: 'deployed', commit: 'first' },
        changed: 1,
      },
      {
        name: 'it runs an ancestor of HEAD',
        seed: { clones: [{ folder: 'orders', commits: ['first', 'second', 'third'] }], node: [{ repository: 'orders', commit: 'first' }] },
        deployed: { relation: 'behind', commit: 'first', commits: 2 },
      },
      {
        name: 'HEAD is an ancestor of what it runs',
        seed: { clones: [{ folder: 'orders', upstream: { at: 'first', more: ['next', 'last'] } }], node: [{ repository: 'orders', commit: 'last' }] },
        deployed: { relation: 'ahead', commit: 'last', commits: 2 },
      },
      {
        name: 'it runs a commit on another line of history',
        seed: { clones: [{ folder: 'orders', commits: ['first', 'second'], upstream: { at: 'first', more: ['theirs'] } }], node: [{ repository: 'orders', commit: 'theirs' }] },
        deployed: { relation: 'other', commit: 'theirs' },
      },
      {
        name: 'it runs a commit the clone lacks',
        seed: { clones: [{ folder: 'orders' }], node: [{ repository: 'orders', commit: ABSENT }] },
        deployed: { relation: 'other', commit: ABSENT },
      },
      {
        name: 'its deploy failed, with the problems it names',
        seed: { clones: [{ folder: 'orders' }], node: [{ repository: 'orders', commit: 'first', state: 'FAILED', problems: ['flows/orders.yaml: no endpoint orders-in'] }] },
        deployed: { relation: 'deployed', commit: 'first', state: 'FAILED', problems: ['flows/orders.yaml: no endpoint orders-in'] },
      },
    ];

    test.each(CASES)('$name', async ({ seed, deployed, changed }) => {
      const listed = await list(seed);
      const commit = deployed.commit as string;

      expect(rowOf(listed, 'orders').deployed).toEqual({
        node: 'answered',
        host: listed.node,
        state: 'RUNNING',
        problems: [],
        deployedAt: DEPLOYED_AT,
        ...deployed,
        commit: commit === ABSENT ? ABSENT : world!.id('orders', commit),
      });
      if (changed !== undefined) expect(rowOf(listed, 'orders').changed).toBe(changed);
    });

    test('it runs nothing of the repository', async () => {
      const listed = await list({ clones: [{ folder: 'orders' }], node: [{ repository: 'billing', commit: ABSENT }] });

      expect(rowOf(listed, 'orders').deployed).toEqual({ node: 'answered', host: listed.node, relation: 'none' });
    });

    test('the node gives no answer: every row says so, with why', async () => {
      const listed = await list({ clones: [{ folder: 'orders' }, { folder: 'billing' }], node: 'silent' });

      for (const row of listed.packages) {
        expect(row.deployed).toEqual({ node: 'unreachable', host: listed.node, error: expect.stringContaining(`the node at ${listed.node} does not answer`) });
      }
    });

    test('every row names the node the list names', async () => {
      const listed = await list({ clones: [{ folder: 'orders' }, { folder: 'billing' }], node: [{ repository: 'orders', commit: 'first' }] });

      expect(listed.node).toMatch(/^127\.0\.0\.1:\d+$/);
      expect(listed.packages.map((p) => p.deployed.host)).toEqual([listed.node, listed.node]);
    });
  });

  test('the list ends at the 100th package', { timeout: 60_000 }, async () => {
    const clones = Array.from({ length: MAX_PACKAGES + 1 }, (_, n) => ({ folder: `p${String(n).padStart(3, '0')}` }));
    const listed = await list({ clones });

    expect(listed.packages).toHaveLength(MAX_PACKAGES);
    expect(listed.packages.at(-1)!.repository).toBe('p099');
    expect(listed.truncated).toBe(true);
  });

  test('at most 100 folders that are no package are listed', async () => {
    const listed = await list({ plain: Array.from({ length: MAX_PACKAGES + 1 }, (_, n) => `f${String(n).padStart(3, '0')}`) });

    expect(listed.others).toHaveLength(MAX_PACKAGES);
    expect(listed.truncated).toBe(true);
  });

  test('with no working folder set, the list says so and holds nothing', async () => {
    expect(await list({ workspace: false })).toEqual({ configured: false, writable: true, node: expect.any(String), packages: [], others: [], truncated: false });
  });

  test('under RAIL8_READ_ONLY the list says it takes no write', async () => {
    expect(await list({ readOnly: true, clones: [{ folder: 'orders' }] })).toMatchObject({ writable: false, packages: [{ repository: 'orders' }] });
  });

  test('a page of another site is refused', async () => {
    expect((await refusalOf({ foreignPage: true, clones: [{ folder: 'orders' }] })).status).toBe(403);
  });

  test.each([
    ['missing', GIT_MISSING],
    ['old', gitTooOld(OLD_GIT)],
  ] as const)('with git %s every call answers 503 with why', async (git, said) => {
    const refused = await refusalOf({ git, clones: [{ folder: 'orders' }] });

    expect(refused.status).toBe(503);
    expect(refused.message).toBe(said);
  });
});
