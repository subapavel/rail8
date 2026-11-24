import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { Shell } from '../Shell';
import { fakeStateOf, type Seed } from './contract/seed';
import type { PackagesApi } from './api';
import { fakePackagesApi, type FakeState } from './fake';
import { GIT_MISSING, NEW_PACKAGE_REFUSALS, REASONS } from './rules';
import { packagesScreen } from './screen';

/** The fake world's seeded commits are made from 09:01 on 1 November 2026; deploys at 10:00 on 20 November. */
const NOW = new Date('2026-11-23T10:00:00Z');

let root: Root | undefined;

function render(state: FakeState, api: PackagesApi = fakePackagesApi(state)) {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={[packagesScreen(api, () => NOW)]} start="packages" />);
}

const open = (seed: Seed) => {
  const { state } = fakeStateOf(seed);
  render(state);
  return state;
};

const packages = () => page.getByRole('table', { name: 'Packages' });
const rowOf = (repository: string) => packages().getByRole('row').filter({ hasText: repository });

afterEach(() => {
  root?.unmount();
  root = undefined;
  document.body.innerHTML = '';
});

describe('the Packages screen', () => {
  test('lists each package with its version, branch, last commit, upstream, changed files and what the node runs', async () => {
    open({
      clones: [
        { folder: 'orders', commits: ['first', 'second'], upstream: { at: 'first', more: ['theirs'] } },
        { folder: 'billing', commits: ['first', 'second', 'third'] },
        { folder: 'shipping', edits: { 'first.txt': 'edited\n', 'new.txt': 'new\n' } },
        { folder: 'stock' },
        { folder: 'returns', commits: [] },
      ],
      node: [
        { repository: 'orders', commit: 'second' },
        { repository: 'billing', commit: 'first' },
        { repository: 'shipping', commit: 'first' },
        { repository: 'stock', commit: 'first', state: 'FAILED', problems: ['flows/stock.yaml: no endpoint stock-in'] },
      ],
    });

    await expect.element(page.getByRole('heading', { level: 1, name: 'Packages' })).toBeVisible();
    await expect.element(page.getByText('Deployed state on the node at 127.0.0.1:8081')).toBeVisible();
    expect(packages().getByRole('row').elements().slice(1).map((r) => r.querySelector('td')?.textContent)).toEqual(['billing', 'orders', 'returns', 'shipping', 'stock']);

    const orders = rowOf('orders');
    await expect.element(orders).toMatchTextContent('orders 1.0.0');
    await expect.element(orders).toMatchTextContent('main');
    await expect.element(orders).toMatchTextContent('second');
    await expect.element(orders).toMatchTextContent('Seed Author, 22 days ago');
    await expect.element(orders).toMatchTextContent('1 ahead, 1 behind origin/main');
    await expect.element(orders).toMatchTextContent('as of the fetch 22 days ago');
    await expect.element(orders.getByText('Deployed', { exact: true })).toBeVisible();
    await expect.element(orders).toMatchTextContent('deployed 3 days ago');

    await expect.element(rowOf('billing').getByText('Behind 2')).toBeVisible();
    await expect.element(rowOf('billing')).toMatchTextContent('No upstream');
    await expect.element(rowOf('shipping').getByText('Deployed, with changes since')).toBeVisible();
    await expect.element(rowOf('stock').getByText('Failed')).toBeVisible();
    await expect.element(rowOf('stock').getByText('flows/stock.yaml: no endpoint stock-in')).toBeVisible();
    await expect.element(rowOf('returns')).toMatchTextContent('No commit yet');
    await expect.element(rowOf('returns').getByText('Not running on 127.0.0.1:8081')).toBeVisible();
  });

  test('marks a package that packages the node runs pin behind the commit it runs, with their number', async () => {
    const pins = (commit: string) => [{ name: 'orders', repository: 'orders', commit, content: `sha256:${'1'.repeat(64)}` }];
    open({
      clones: [{ folder: 'orders', commits: ['first', 'second', 'third'] }, { folder: 'billing' }, { folder: 'shipping' }, { folder: 'stock' }],
      node: [
        { repository: 'orders', commit: 'third' },
        { repository: 'billing', commit: 'first', dependencies: pins('first') },
        { repository: 'shipping', commit: 'first', dependencies: pins('second') },
        { repository: 'stock', commit: 'first', dependencies: pins('third') },
      ],
    });

    await expect.element(rowOf('orders').getByText('Pinned behind by 2')).toBeVisible();
    await rowOf('orders').getByText('Pinned behind by 2').hover();
    await expect.element(page.getByRole('tooltip')).toHaveTextContent('2 packages the node runs pin a commit of it behind the one it runs: see Used by in its tab.');
    await expect.element(rowOf('billing').getByText(/Pinned behind/)).not.toBeInTheDocument();
  });

  test('names how many nodes of the pool run which commit, and the nodes behind its record', async () => {
    const why = 'the commit could not be fetched: connection refused';
    const state = open({
      clones: [{ folder: 'orders', commits: ['first', 'second'] }, { folder: 'billing' }],
      node: [{ repository: 'orders', commit: 'second' }],
      pool: {
        records: [{ repository: 'orders', commit: 'second' }],
        nodes: [
          { node: 'node-a', packages: [{ repository: 'orders', commit: 'second' }] },
          { node: 'node-b', packages: [{ repository: 'orders', commit: 'first', behind: [why] }] },
          { node: 'node-c', packages: [{ repository: 'orders', commit: 'second', state: 'FAILED' }] },
        ],
      },
    });
    const second = state.folders.find((f) => f.name === 'orders')!.commits.find((c) => c.subject === 'second')!.id.slice(0, 7);

    await expect.element(page.getByText('Deployed state on the node at 127.0.0.1:8081, and on every node of its pool')).toBeVisible();
    const orders = rowOf('orders');
    await expect.element(orders.getByText('Behind 1')).toBeVisible();
    await expect.element(orders.getByText('Failed')).toBeVisible();
    await expect.element(orders).toMatchTextContent(`Deployed${second} on 1 of 3 nodes`);
    await expect.element(orders).toMatchTextContent(`Failed${second} on 1 of 3 nodes`);
    await expect.element(orders.getByText(/^Behind the pool's commit/)).toMatchTextContent(`Behind the pool's commit ${second}: node-b`);
    expect(orders.getByText(/^Behind the pool's commit/).element().querySelector('code')?.textContent).toBe(second);
    await expect.element(orders.getByText(`node-b: ${why}`)).toBeVisible();
    await expect.element(rowOf('billing').getByText('Not running', { exact: true })).toBeVisible();
    await expect.element(rowOf('billing')).toMatchTextContent('on 3 of 3 nodes');
  });

  test('a node that answers no pool route shows the single node, and a pool that cannot be read says why', async () => {
    open({ clones: [{ folder: 'orders' }], node: [{ repository: 'orders', commit: 'first' }] });

    await expect.element(rowOf('orders').getByText('Deployed', { exact: true })).toBeVisible();
    expect(rowOf('orders').element().textContent).not.toMatch(/ of \d+ nodes?|pool/);
    expect(page.getByText(/every node of its pool/).elements()).toHaveLength(0);

    root?.unmount();
    document.body.innerHTML = '';
    open({ clones: [{ folder: 'orders' }], node: [{ repository: 'orders', commit: 'first' }], pool: 'fails' });

    await expect.element(rowOf('orders').getByText(/^What each node of the pool runs is unknown: the node at 127\.0\.0\.1:8081 answered 500/)).toBeVisible();
    expect(page.getByText(/every node of its pool/).elements()).toHaveLength(0);
  });

  test('reads Ahead and Other commit, a detached HEAD and a merge left open', async () => {
    open({
      clones: [
        { folder: 'orders', upstream: { at: 'first', more: ['next', 'last'] } },
        { folder: 'billing', commits: ['first', 'second'], upstream: { at: 'first', more: ['theirs'] } },
        { folder: 'stock', detached: true },
        { folder: 'returns', conflict: true },
      ],
      node: [
        { repository: 'orders', commit: 'last' },
        { repository: 'billing', commit: 'theirs' },
      ],
    });

    await expect.element(rowOf('orders').getByText('Ahead 2')).toBeVisible();
    await rowOf('orders').getByText('Ahead 2').hover();
    await expect.element(page.getByRole('tooltip').getByText('The node runs commits that HEAD lacks: pull them.')).toBeVisible();
    await expect.element(rowOf('billing').getByText('Other commit')).toBeVisible();
    await expect.element(rowOf('stock').getByText('Detached HEAD')).toBeVisible();
    await expect.element(rowOf('returns').getByText('Merge in progress')).toBeVisible();
  });

  test('cuts a long commit subject to its column, and shows it whole on hover', async () => {
    const subject = `Rework ${'the order mapping and its tests, '.repeat(8)}once more`;
    open({ clones: [{ folder: 'orders', commits: [subject] }] });

    const cut = rowOf('orders').getByText(subject);
    await expect.element(cut).toBeVisible();
    expect(cut.element().scrollWidth).toBeGreaterThan(cut.element().clientWidth);
    await cut.hover();
    await expect.element(page.getByRole('tooltip').getByText(subject)).toBeVisible();
  });

  test('lists the folders that are no package below the packages, each with the reason', async () => {
    open({ clones: [{ folder: 'orders' }, { folder: 'loose', manifest: null }], plain: ['docs'], worktrees: [{ folder: 'tree', of: 'orders' }] });

    const others = page.getByRole('table', { name: 'Folders that are no package' });
    await expect.element(page.getByRole('heading', { level: 2, name: 'Folders that are no package' })).toBeVisible();
    await expect.element(others.getByRole('row').filter({ hasText: 'docs' })).toMatchTextContent(REASONS.noClone);
    await expect.element(others.getByRole('row').filter({ hasText: 'loose' })).toMatchTextContent(REASONS.noManifest);
    await expect.element(others.getByRole('row').filter({ hasText: 'tree' })).toMatchTextContent(REASONS.worktree);
    expect(packages().getByRole('row').elements()).toHaveLength(2);
  });

  test('says when the node gives no answer, and why, and reads it again on Refresh', async () => {
    const state = open({ clones: [{ folder: 'orders' }], node: 'silent' });

    await expect.element(page.getByRole('alert').filter({ hasText: 'The node at 127.0.0.1:8081 does not answer' })).toBeVisible();
    await expect.element(rowOf('orders').getByText('Unknown')).toBeVisible();

    state.node = { host: '127.0.0.1:8081', answers: true, running: [] };
    await page.getByRole('button', { name: 'Refresh' }).click();
    await expect.element(rowOf('orders').getByText('Not running on 127.0.0.1:8081')).toBeVisible();
    await expect.element(page.getByRole('alert')).not.toBeInTheDocument();
  });

  test('says when git is missing', async () => {
    open({ clones: [{ folder: 'orders' }], git: 'missing' });

    await expect.element(page.getByRole('alert').filter({ hasText: 'The packages could not be read' })).toMatchTextContent(GIT_MISSING);
  });

  test('without a working folder it says how to set one', async () => {
    open({ workspace: false });

    await expect.element(page.getByRole('heading', { level: 2, name: 'No working folder' })).toBeVisible();
  });

  test('says when the working folder holds no package', async () => {
    open({ plain: ['docs'] });

    await expect.element(page.getByRole('heading', { level: 2, name: 'No packages' })).toBeVisible();
    await expect.element(page.getByRole('table', { name: 'Folders that are no package' })).toBeVisible();
    await packages().getByRole('button', { name: 'Get a package…' }).click();
    await expect.element(page.getByRole('dialog', { name: 'Get a package' })).toBeVisible();
    await page.getByRole('dialog', { name: 'Get a package' }).getByRole('button', { name: 'Cancel' }).click();
    await packages().getByRole('button', { name: 'New package' }).click();
    await expect.element(page.getByRole('dialog', { name: 'New package' })).toBeVisible();
  });

  test('is marked read-only under RAIL8_READ_ONLY, and says when not every folder is listed', async () => {
    open({ readOnly: true, plain: Array.from({ length: 101 }, (_, n) => `f${String(n).padStart(3, '0')}`) });

    await expect.element(page.getByText('Read-only', { exact: true })).toBeVisible();
    await expect.element(page.getByRole('alert').filter({ hasText: 'Not every folder of the working folder is listed' })).toBeVisible();
  });
});

describe('Get a package', () => {
  const address = 'https://git.example.com/acme/orders.git';
  const dialog = () => page.getByRole('dialog', { name: 'Get a package' });
  const field = () => dialog().getByLabelText('Address', { exact: true });
  // the toolbar's, which comes before the one an empty table offers
  const getButton = () => page.getByRole('button', { name: 'Get a package…' }).first();

  test('clones the address into the folder of its name, lists it, and opens its tab', async () => {
    open({ clones: [{ folder: 'billing' }], sources: [{ path: 'acme/orders.git', commits: ['first', 'second'] }] });

    await getButton().click();
    await expect.element(dialog().getByRole('button', { name: 'Get', exact: true })).toBeDisabled();
    await field().fill('https://git.example.com/acme/my orders.git');
    await expect.element(dialog().getByText('paste only the address', { exact: false })).toBeVisible();
    await field().fill(`git clone ${address}`);
    await expect.element(dialog().getByText('Goes into the folder orders')).toBeVisible();
    await dialog().getByRole('button', { name: 'Get', exact: true }).click();

    await expect.element(page.getByRole('tab', { name: /orders$/, selected: true })).toBeVisible();
    await expect.element(dialog()).not.toBeInTheDocument();
    await page.getByRole('tab', { name: /Packages$/ }).click();
    await expect.element(rowOf('orders')).toMatchTextContent('orders 1.0.0');
    await expect.element(rowOf('orders')).toMatchTextContent('second');
  });

  test('says why git could not clone, with what git said, and keeps the address to try again', async () => {
    open({ sources: [{ path: 'acme/orders.git', fails: 'credentials' }] });

    await getButton().click();
    await field().fill(address);
    await userEvent.type(field(), '{Enter}');

    const refused = dialog().getByRole('alert').filter({ hasText: 'The package was not got' });
    await expect.element(refused).toMatchTextContent('git could not clone the repository');
    await expect.element(dialog().getByLabelText('What git said')).toMatchTextContent('could not read Username');
    await expect.element(field()).toHaveValue(address);
    expect(packages().getByRole('row').filter({ hasText: 'orders' }).elements()).toEqual([]);
  });

  test('says when the clone is no package, and lists it among the folders that are no package', async () => {
    open({ clones: [{ folder: 'billing' }], sources: [{ path: 'acme/orders.git', manifest: null, files: { 'README.md': 'orders\n' } }] });

    await getButton().click();
    await field().fill(address);
    await dialog().getByRole('button', { name: 'Get', exact: true }).click();

    const got = page.getByRole('dialog', { name: 'Got orders' });
    await expect.element(got.getByText('The folder orders holds the clone, but it is no package')).toBeVisible();
    await expect.element(got).toMatchTextContent(REASONS.noManifest);
    await got.getByRole('button', { name: 'Close' }).last().click();
    await expect.element(page.getByRole('table', { name: 'Folders that are no package' }).getByText('orders')).toBeVisible();
  });

  test('is off on a server that takes no writes', async () => {
    open({ readOnly: true, clones: [{ folder: 'billing' }] });

    await expect.element(getButton()).toBeDisabled();
  });
});

describe('New package', () => {
  const dialog = () => page.getByRole('dialog', { name: 'New package' });
  const repositoryField = () => dialog().getByLabelText('Repository', { exact: true });
  const nameField = () => dialog().getByLabelText('Package name', { exact: true });
  const create = () => dialog().getByRole('button', { name: 'Create', exact: true });
  // the toolbar's, which comes before the one an empty table offers
  const newButton = () => page.getByRole('button', { name: 'New package' }).first();

  test('makes the package in the folder of its name, says a deployed environment needs the git host, lists it, and opens its tab', async () => {
    open({ clones: [{ folder: 'billing' }] });

    await newButton().click();
    await expect.element(dialog().getByText('A deployed environment needs the repository on the git host first', { exact: false })).toBeVisible();
    await expect.element(create()).toBeDisabled();
    await repositoryField().fill('.hidden');
    await expect.element(dialog().getByText(NEW_PACKAGE_REFUSALS.repository)).toBeVisible();
    await repositoryField().fill('orders');
    await nameField().fill('Orders');
    await expect.element(dialog().getByText(NEW_PACKAGE_REFUSALS.name)).toBeVisible();
    await expect.element(create()).toBeDisabled();
    await nameField().fill('orders-app');
    await create().click();

    await expect.element(page.getByRole('tab', { name: /orders$/, selected: true })).toBeVisible();
    await expect.element(dialog()).not.toBeInTheDocument();
    await page.getByRole('tab', { name: /Packages$/ }).click();
    await expect.element(rowOf('orders')).toMatchTextContent('orders-app 0.1.0');
    await expect.element(rowOf('orders')).toMatchTextContent('Create package orders-app');
  });

  test('says why it was refused, and keeps the names to try again', async () => {
    open({ clones: [{ folder: 'orders' }] });

    await newButton().click();
    await repositoryField().fill('orders');
    await nameField().fill('orders');
    await create().click();

    await expect.element(dialog().getByRole('alert').filter({ hasText: 'The package was not made' })).toMatchTextContent(NEW_PACKAGE_REFUSALS.exists);
    await expect.element(repositoryField()).toHaveValue('orders');
  });

  test('says when the new package is in place but its state could not be read, and lists it again', async () => {
    const { state } = fakeStateOf({});
    const fake = fakePackagesApi(state);
    let listed = 0;
    render(state, {
      ...fake,
      list: () => {
        listed += 1;
        return fake.list();
      },
      create: async (repository) => ({ repository, package: null, problem: 'the server is closing' }),
    });

    await newButton().click();
    await repositoryField().fill('orders');
    await nameField().fill('orders');
    const before = listed;
    await create().click();

    const made = page.getByRole('dialog', { name: 'Made orders' });
    await expect.element(made.getByText('The folder orders holds the new package, but its state could not be read')).toBeVisible();
    await expect.element(made).toMatchTextContent('the server is closing');
    expect(listed).toBeGreaterThan(before);
    await made.getByRole('button', { name: 'Close' }).last().click();
    await expect.element(made).not.toBeInTheDocument();
  });

  test('is off on a server that takes no writes', async () => {
    open({ readOnly: true, clones: [{ folder: 'billing' }] });

    await expect.element(newButton()).toBeDisabled();
  });
});
