import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type DeployBody } from '../api';
import { refusalText } from '../../refusal';
import type { FakeNodeRefusal } from '../fake';
import { PACKAGE_WORDS } from '../refusals';
import { DEPLOY_MS } from '../rules';
import { httpWorld } from './httpWorld';
import { fakeWorld, type Build, type CloneSeed, type Seed, type World } from './seed';

const ABSENT = '0123456789abcdef0123456789abcdef01234567';

/** Two commits whose flows differ: `first` runs a and b, `second` changes a, drops b and adds c. */
const FLOWS: Omit<CloneSeed, 'folder'> = {
  commits: ['first', 'second'],
  commitFiles: {
    first: { 'flows/a.yaml': 'a, first\n', 'flows/b.yaml': 'b\n' },
    second: { 'flows/a.yaml': 'a, second\n', 'flows/b.yaml': null, 'flows/c.yaml': 'c\n' },
  },
};

// The deploy cases on both implementations of PackagesApi: the real git half over real git and a fake node, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('the %s git half deploys', (_, build) => {
  let world: World | undefined;
  afterEach(async () => {
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed) => (world = await build(seed));
  const one = (clone: Omit<CloneSeed, 'folder'>, seed: Seed = {}) => open({ ...seed, clones: [{ folder: 'orders', ...clone }] });
  const refusalOf = (call: Promise<unknown>) =>
    call.then(
      () => expect.unreachable('the call was not refused'),
      (error: unknown) => {
        expect(error).toBeInstanceOf(PackagesError);
        return error as PackagesError;
      },
    );

  test("a deploy in a pool answers the pool as it is after the deploy: its record names the commit, which its nodes have not followed yet", async () => {
    const { api } = await one(FLOWS, {
      node: [{ repository: 'orders', commit: 'first' }],
      pool: { records: [{ repository: 'orders', commit: 'first' }], nodes: [{ node: 'node-a', packages: [{ repository: 'orders', commit: 'first' }] }] },
    });
    const first = world!.id('orders', 'first');
    const second = world!.id('orders', 'second');

    const deployed = await api.deploy('orders', { commit: second, check: false, expect: first });

    expect(deployed.package?.deployed).toMatchObject({ relation: 'deployed', commit: second, pool: { record: second, nodes: [{ node: 'node-a', relation: 'behind', commit: first, follows: false }] } });
  });

  test('a check, then a deploy of what it checked, then a deploy of an earlier commit: each sends 40 hex digits, the check’s replaces as expect, and the actor', async () => {
    const { api } = await one(FLOWS);
    const first = world!.id('orders', 'first');
    const second = world!.id('orders', 'second');

    const checked = await api.deploy('orders', { commit: second, check: true });
    expect(checked).toMatchObject({
      deploy: { package: 'orders', version: '1.0.0', repository: 'orders', commit: second, flows: ['orders/a', 'orders/c'], added: ['orders/a', 'orders/c'], swapped: [], removed: [] },
      replaces: 'none',
      package: { deployed: { relation: 'none' } },
    });

    const deployed = await api.deploy('orders', { commit: second, check: false, expect: checked.replaces });
    expect(deployed).toMatchObject({ deploy: { commit: second, added: ['orders/a', 'orders/c'] }, replaces: 'none', package: { deployed: { relation: 'deployed', commit: second } } });
    expect(deployed.package).toEqual(await api.get('orders'));

    // going back is a deploy of an earlier commit, through the same check
    const back = await api.deploy('orders', { commit: first, check: true });
    expect(back).toMatchObject({ deploy: { flows: ['orders/a', 'orders/b'], added: ['orders/b'], swapped: ['orders/a'], removed: ['orders/c'] }, replaces: second });
    const gone = await api.deploy('orders', { commit: first, check: false, expect: back.replaces });
    expect(gone.package).toMatchObject({ deployed: { relation: 'behind', commit: first, commits: 1 } });

    // a header carries no character outside printable ASCII: Jiří Novák is Ji?? Nov?k
    const actor = 'Ji?? Nov?k <jiri@example.com>';
    expect(world!.sent()).toEqual([
      { check: true, body: { repository: 'orders', commit: second }, actor },
      { check: false, body: { repository: 'orders', commit: second, expect: 'none' }, actor },
      { check: true, body: { repository: 'orders', commit: first }, actor },
      { check: false, body: { repository: 'orders', commit: first, expect: second }, actor },
    ]);
  });

  test('without a git identity the actor is ui', async () => {
    const { api } = await one({}, { identity: null });
    await api.deploy('orders', { commit: world!.id('orders', 'first'), check: true });
    expect(world!.sent().map((s) => s.actor)).toEqual(['ui']);
  });

  test.each<[number, FakeNodeRefusal]>([
    [
      422,
      {
        status: 422,
        error: "package 'orders' is invalid: 2 problem(s)",
        code: 'package-invalid',
        values: { package: 'orders', count: 2 },
        problems: ['flows/a.yaml: the step 1 names no type', 'endpoints/in.yaml: no such component'],
      },
    ],
    [500, { status: 500, error: 'the start of orders/a failed and was rolled back', code: 'flow-did-not-start', values: { flow: 'orders/a' }, problems: ['orders/a: port 8080 is taken'] }],
    [409, { status: 409, error: 'another deploy runs on node-b', code: 'deploy-running', values: {} }],
    [400, { status: 400, error: 'the node takes no such body' }],
  ])('a %s of the node is passed on with its error, its code and values and its problems, and nothing is deployed', async (status, refusal) => {
    const { api } = await one(FLOWS, { deploys: refusal });
    const second = world!.id('orders', 'second');
    const passed = { status, code: refusal.code ?? 'node-refused', values: refusal.values ?? {}, english: refusal.error, message: refusalText(refusal, PACKAGE_WORDS) };

    const checked = await refusalOf(api.deploy('orders', { commit: second, check: true }));
    expect(checked).toMatchObject({ ...passed, problems: refusal.problems });
    const deployed = await refusalOf(api.deploy('orders', { commit: second, check: false, expect: 'none' }));
    expect(deployed).toMatchObject(passed);
    expect((await api.get('orders')).deployed).toMatchObject({ relation: 'none' });
  });

  test('a deploy after the node’s commit changed since its check is the node’s 412, and the node runs on what it ran', async () => {
    const { api } = await one(FLOWS);
    const second = world!.id('orders', 'second');
    const checked = await api.deploy('orders', { commit: second, check: true });
    world!.deployedElsewhere('orders', 'first');

    const refused = await refusalOf(api.deploy('orders', { commit: second, check: false, expect: checked.replaces }));

    expect(refused).toMatchObject({
      status: 412,
      code: 'deploy-stale',
      values: { expect: checked.replaces, records: world!.id('orders', 'first') },
      english: expect.stringMatching(/^the node's commit changed since the check: .*; nothing was deployed$/),
    });
    expect((await api.get('orders')).deployed).toMatchObject({ relation: 'behind', commit: world!.id('orders', 'first') });
  });

  test('a deploy the node does not answer within 5 min is 504, its outcome unknown, and what the node runs can be read after it', async () => {
    const { api } = await one(FLOWS, { deploys: 'silent' });
    const second = world!.id('orders', 'second');
    const checked = await api.deploy('orders', { commit: second, check: true });

    const refused = refusalOf(api.deploy('orders', { commit: second, check: false, expect: checked.replaces }));
    await world!.heard();
    world!.elapse(DEPLOY_MS);

    expect(await refused).toMatchObject({ status: 504, message: expect.stringMatching(/the outcome of the deploy is unknown: read what it runs before you try again$/) });
    expect((await api.get('orders')).deployed).toMatchObject({ node: 'answered' });
  });

  test('a node that refuses the token is 502, saying so', async () => {
    const { api } = await one({}, { tokenRefused: true });
    const refused = await refusalOf(api.deploy('orders', { commit: world!.id('orders', 'first'), check: true }));
    expect(refused).toMatchObject({ status: 502, message: expect.stringContaining("refuses the UI server's token (401)") });
  });

  test('a node that does not answer at all is 502', async () => {
    const { api } = await one({}, { node: 'silent' });
    const refused = await refusalOf(api.deploy('orders', { commit: world!.id('orders', 'first'), check: true }));
    expect(refused).toMatchObject({ status: 502, message: expect.stringContaining('does not answer') });
  });

  test('a commit that is no 40 hex digits, a deploy that names no state or the blind overwrite, and a commit the clone lacks are refused before the node is asked', async () => {
    const { api } = await one({});
    const first = world!.id('orders', 'first');
    const cases: [unknown, number][] = [
      [{ commit: 'HEAD', check: true }, 400],
      [{ commit: first.slice(0, 7), check: true }, 400],
      [{ commit: `-${first.slice(1)}`, check: true }, 400],
      [{ commit: first, check: 'yes' }, 400],
      [{ commit: first, check: false }, 400],
      [{ commit: first, check: false, expect: 'any' }, 400],
      [{ commit: first, check: false, expect: 'HEAD' }, 400],
      [{ commit: first, check: true, expect: 'none' }, 400],
      [{ commit: ABSENT, check: true }, 404],
    ];
    for (const [asked, status] of cases) expect((await refusalOf(api.deploy('orders', asked as DeployBody))).status, JSON.stringify(asked)).toBe(status);
    expect(world!.sent()).toEqual([]);
  });

  test('under RAIL8_READ_ONLY=true a check is answered and a deploy refused', async () => {
    const { api } = await one({}, { readOnly: true });
    const first = world!.id('orders', 'first');
    expect(await api.deploy('orders', { commit: first, check: true })).toMatchObject({ replaces: 'none' });
    expect((await refusalOf(api.deploy('orders', { commit: first, check: false, expect: 'none' }))).status).toBe(403);
    expect(world!.sent().map((s) => s.check)).toEqual([true]);
  });

  test('a page of another site is refused', async () => {
    const { api } = await one({}, { foreignPage: true });
    expect((await refusalOf(api.deploy('orders', { commit: ABSENT, check: true }))).status).toBe(403);
  });
});
