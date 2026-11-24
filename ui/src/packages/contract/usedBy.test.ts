import { afterEach, describe, expect, test } from 'vitest';
import { httpWorld } from './httpWorld';
import { fakeWorld, type Build, type RunningSeed, type Seed, type World } from './seed';

const CONTENT = `sha256:${'1'.repeat(64)}`;
const ABSENT = '0123456789abcdef0123456789abcdef01234567';

/** The package `name` that the node runs at the commit `first` of its own clone, pinning the commit `pinned` of `inventory`, and the first commit of `stock`. */
const pinning = (name: string, pinned: string): RunningSeed => ({
  repository: name,
  commit: 'first',
  dependencies: [
    { name: 'inventory', version: '1.0.0', repository: 'inventory', commit: pinned, content: CONTENT },
    { name: 'stock', version: '1.0.0', repository: 'stock', commit: 'first', content: CONTENT },
  ],
});

// Where a package is used as a dependency, on both implementations of PackagesApi: the real git half over real git, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('the %s git half shows where a package is used', (_name, build) => {
  let world: World | undefined;
  afterEach(async () => {
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed) => (world = await build(seed));
  /** `inventory` with three commits, and the clones `orders`, `billing` and `shipping` beside it. */
  const pool = (node: Seed['node']) =>
    open({
      clones: [{ folder: 'inventory', commits: ['first', 'second', 'third'] }, { folder: 'orders' }, { folder: 'billing' }, { folder: 'shipping' }, { folder: 'stock' }],
      node,
    });

  test('two consumers pin two commits: one the commit the node runs, one behind it; each related to HEAD too', async () => {
    const { api } = await pool([{ repository: 'inventory', commit: 'second' }, pinning('orders', 'second'), pinning('billing', 'first'), { repository: 'shipping', commit: 'first' }]);
    const id = (label: string) => world!.id('inventory', label);
    const state = await api.get('inventory');

    expect(state.usedBy).toEqual({
      node: 'answered',
      host: state.deployed.host,
      runs: id('second'),
      consumers: [
        {
          name: 'billing',
          version: '1.0.0',
          repository: 'billing',
          pin: { version: '1.0.0', commit: id('first'), content: CONTENT },
          commit: { at: expect.any(String) as string, subject: 'first' },
          runs: { relation: 'behind', commits: 1 },
          head: { relation: 'behind', commits: 2 },
        },
        {
          name: 'orders',
          version: '1.0.0',
          repository: 'orders',
          pin: { version: '1.0.0', commit: id('second'), content: CONTENT },
          commit: { at: expect.any(String) as string, subject: 'second' },
          runs: { relation: 'same' },
          head: { relation: 'behind', commits: 1 },
        },
      ],
    });
    const listed = await api.list();
    expect(listed.packages.find((p) => p.repository === 'inventory')!.pinnedBehind).toBe(1);
    expect(listed.packages.filter((p) => p.pinnedBehind !== undefined).map((p) => p.repository)).toEqual(['inventory']);
    expect((await api.get('stock')).usedBy).toMatchObject({ consumers: [{ name: 'billing' }, { name: 'orders' }] });
  });

  test('a package nobody pins is used by none, and its row carries no mark', async () => {
    const { api } = await pool([{ repository: 'inventory', commit: 'third' }, { repository: 'orders', commit: 'first' }]);
    const state = await api.get('inventory');
    expect(state.usedBy).toEqual({ node: 'answered', host: state.deployed.host, runs: world!.id('inventory', 'third'), consumers: [] });
    expect((await api.list()).packages.find((p) => p.repository === 'inventory')).not.toHaveProperty('pinnedBehind');
  });

  test('a pin ahead of what runs, and a pin of a commit the clone lacks, which has no date and is on another line', async () => {
    const { api } = await pool([{ repository: 'inventory', commit: 'first' }, pinning('orders', 'third'), pinning('billing', ABSENT)]);
    const { usedBy } = await api.get('inventory');
    expect(usedBy).toMatchObject({
      consumers: [
        { name: 'billing', pin: { commit: ABSENT }, commit: null, runs: { relation: 'other' }, head: { relation: 'other' } },
        { name: 'orders', runs: { relation: 'ahead', commits: 2 }, head: { relation: 'same' } },
      ],
    });
    expect((await api.list()).packages.find((p) => p.repository === 'inventory')).not.toHaveProperty('pinnedBehind');
  });

  test('a pin compared with a commit the node runs that the clone lacks is on another line', async () => {
    const { api } = await pool([{ repository: 'inventory', commit: ABSENT }, pinning('orders', 'first')]);
    const { usedBy } = await api.get('inventory');
    expect(usedBy).toMatchObject({ runs: ABSENT, consumers: [{ name: 'orders', runs: { relation: 'other' }, head: { relation: 'behind', commits: 2 } }] });
  });

  test('a package the node does not run is related to HEAD alone', async () => {
    const { api } = await pool([pinning('orders', 'second')]);
    const { usedBy } = await api.get('inventory');
    expect(usedBy).toMatchObject({ node: 'answered', runs: null, consumers: [{ name: 'orders', head: { relation: 'behind', commits: 1 } }] });
    expect(usedBy.node === 'answered' && usedBy.consumers[0]).not.toHaveProperty('runs');
  });

  test('a node that does not answer is said so, with nothing in its place', async () => {
    const { api } = await pool('silent');
    const { usedBy } = await api.get('inventory');
    expect(usedBy).toEqual({ node: 'unreachable', host: expect.any(String) as string, error: expect.stringContaining('does not answer') as string });
    expect((await api.list()).packages.find((p) => p.repository === 'inventory')).not.toHaveProperty('pinnedBehind');
  });
});
