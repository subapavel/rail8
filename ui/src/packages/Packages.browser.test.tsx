import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { chooseLanguage } from '../language';
import { Shell } from '../Shell';
import { fakeStateOf, type Seed } from './contract/seed';
import type { PackagesApi } from './api';
import { fakePackagesApi, type FakeState } from './fake';
import { GIT_REFUSALS, NEW_PACKAGE_REFUSALS, REASONS } from './rules';
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
/** The names the rows' links show, in their order. */
const listed = () => packages().getByRole('row').elements().slice(1).map((r) => r.querySelector('td button')?.textContent);

afterEach(() => {
  root?.unmount();
  root = undefined;
  document.body.innerHTML = '';
  chooseLanguage('en');
  localStorage.removeItem('rail8.language');
});

describe('the Packages screen', () => {
  test('lists each package with its version, branch, last commit, changes and what the node runs, and no explaining text', async () => {
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
    await expect.element(page.getByRole('heading', { level: 2, name: 'Packages (5)' })).toBeVisible();
    expect(document.body.textContent).not.toMatch(/Deployed state on the node|The packages of the working folder|127\.0\.0\.1:8081/);
    expect(listed()).toEqual(['billing', 'orders', 'returns', 'shipping', 'stock']);
    expect(packages().getByRole('columnheader').elements().map((h) => h.textContent)).toEqual(['Package', 'Description', 'Branch', 'Last commit', 'Changes', 'Deployed']);

    const orders = rowOf('orders');
    await expect.element(orders).toMatchTextContent('orders1.0.0');
    await expect.element(orders).toMatchTextContent('main');
    await expect.element(orders).toMatchTextContent('second');
    await expect.element(orders).toMatchTextContent('Seed Author, 22 days ago');
    expect(orders.element().textContent).not.toMatch(/ahead|behind|fetch/);
    await expect.element(orders.getByText('Deployed', { exact: true })).toBeVisible();
    await expect.element(orders).toMatchTextContent(/[0-9a-f]{7} 3 days ago/);
    expect(orders.element().querySelectorAll('td')[4]?.textContent).toBe('-');

    await expect.element(rowOf('billing').getByText('Behind 2')).toBeVisible();
    await expect.element(rowOf('shipping').getByText('Deployed, with changes since')).toBeVisible();
    await expect.element(rowOf('shipping').getByText('2 files', { exact: true })).toBeVisible();
    await expect.element(rowOf('stock').getByText('Failed')).toBeVisible();
    await rowOf('stock').getByText('Failed').hover();
    await expect.element(page.getByRole('tooltip').getByText('flows/stock.yaml: no endpoint stock-in')).toBeVisible();
    await expect.element(rowOf('returns')).toMatchTextContent('No commit yet');
    await expect.element(rowOf('returns').getByText('Not running', { exact: true })).toBeVisible();
  });

  test('shows a package whose folder is named otherwise with its folder, and one whose package.yaml cannot be read by its folder', async () => {
    open({
      clones: [
        { folder: 'orders-v2', manifest: 'schemaVersion: 1\nkind: package\nname: orders\nversion: 2.0.0\n' },
        { folder: 'broken', manifest: 'name: [' },
      ],
    });

    await expect.poll(listed).toEqual(['broken', 'orders']);
    await expect.element(rowOf('orders-v2')).toMatchTextContent('orders2.0.0in the folder orders-v2');
    await expect.element(rowOf('broken').getByText(/^package\.yaml is no YAML/)).toBeVisible();
  });

  test('finds packages by name and description as they are typed, counts what it shows, and says when none matches', async () => {
    open({
      clones: [
        { folder: 'orders', manifest: 'schemaVersion: 1\nkind: package\nname: orders\nversion: 1.0.0\ndescription: Orders from the web shop to the ERP.\n' },
        { folder: 'billing', manifest: 'schemaVersion: 1\nkind: package\nname: billing\nversion: 1.0.0\ndescription: Invoices to the ledger.\n' },
        { folder: 'shipping' },
      ],
    });
    await expect.element(rowOf('orders')).toMatchTextContent('Orders from the web shop to the ERP.');
    const search = page.getByLabelText('Search packages', { exact: true });

    await search.fill('INVOICE');
    await expect.poll(listed).toEqual(['billing']);
    await expect.element(page.getByRole('heading', { level: 2, name: 'Packages (1)' })).toBeVisible();
    await search.fill('ship');
    await expect.poll(listed).toEqual(['shipping']);
    await search.fill('payroll');
    await expect.element(packages().getByText('No package matches “payroll”')).toBeVisible();
    await expect.element(page.getByRole('heading', { level: 2, name: 'Packages (0)' })).toBeVisible();
    await search.fill('');
    await expect.poll(listed).toEqual(['billing', 'orders', 'shipping']);
  });

  test('sorts by name and by the last commit from the column headers', async () => {
    // the seed makes the commits one after the other, folder by folder: orders' last commit is the oldest
    open({ clones: [{ folder: 'orders', commits: ['first', 'second', 'third'] }, { folder: 'billing' }, { folder: 'stock', commits: ['first', 'second'] }] });
    await expect.poll(listed).toEqual(['billing', 'orders', 'stock']);

    await packages().getByRole('columnheader', { name: 'Package' }).click();
    await expect.poll(listed).toEqual(['stock', 'orders', 'billing']);
    await packages().getByRole('columnheader', { name: 'Last commit' }).click();
    await expect.poll(listed).toEqual(['orders', 'billing', 'stock']);
  });

  test('has New package as its main action, Get a package… beside it, and Refresh as an icon with its name', async () => {
    open({ clones: [{ folder: 'orders' }] });

    const refresh = page.getByRole('button', { name: 'Refresh' });
    await expect.element(refresh).toBeVisible();
    expect(refresh.element().textContent).toBe('');
    await refresh.hover();
    await expect.element(page.getByRole('tooltip').getByText('Refresh')).toBeVisible();
    const actions = [...document.querySelectorAll('.ant-page-header-heading-extra button')].map((b) => b.getAttribute('aria-label') ?? b.textContent);
    expect(actions).toEqual(['Refresh', 'Get a package…', 'New package']);
    await expect.element(page.getByRole('button', { name: 'New package' })).toHaveClass(/ant-btn-primary/);
    await expect.element(page.getByRole('button', { name: 'Get a package…' })).not.toHaveClass(/ant-btn-primary/);
  });

  test('says in one line on how many nodes of the pool the commit the node runs runs, and when it was deployed', async () => {
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

    const orders = rowOf('orders');
    await expect.element(orders.getByText('Deployed', { exact: true })).toBeVisible();
    await expect.element(orders).toMatchTextContent(`Deployed${second} on 1 of 3 nodes, 3 days ago`);
    expect(orders.element().textContent).not.toMatch(/node-b|Behind the pool/);
    expect(orders.element().querySelectorAll('.ant-badge').length).toBe(1);
    await expect.element(rowOf('billing').getByText('Not running', { exact: true })).toBeVisible();
  });

  test('a node that answers no pool route, or a pool that cannot be read, shows the node alone', async () => {
    open({ clones: [{ folder: 'orders' }], node: [{ repository: 'orders', commit: 'first' }] });

    await expect.element(rowOf('orders').getByText('Deployed', { exact: true })).toBeVisible();
    expect(rowOf('orders').element().textContent).not.toMatch(/ of \d+ nodes?|pool/);

    root?.unmount();
    document.body.innerHTML = '';
    open({ clones: [{ folder: 'orders' }], node: [{ repository: 'orders', commit: 'first' }], pool: 'fails' });

    await expect.element(rowOf('orders')).toMatchTextContent(/[0-9a-f]{7} 3 days ago/);
    expect(rowOf('orders').element().textContent).not.toMatch(/ of \d+ nodes?|pool/);
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

  test('lists no folder that is no package', async () => {
    open({ clones: [{ folder: 'orders' }, { folder: 'loose', manifest: null }], plain: ['docs'], worktrees: [{ folder: 'tree', of: 'orders' }] });

    await expect.poll(listed).toEqual(['orders']);
    expect(document.body.textContent).not.toMatch(/docs|loose|tree|no package/);
  });

  test('says when the node gives no answer, and why, and reads it again on Refresh', async () => {
    const state = open({ clones: [{ folder: 'orders' }], node: 'silent' });

    await expect.element(page.getByRole('alert').filter({ hasText: 'The node at 127.0.0.1:8081 does not answer' })).toBeVisible();
    await expect.element(rowOf('orders').getByText('Unknown')).toBeVisible();

    state.node = { host: '127.0.0.1:8081', answers: true, running: [] };
    await page.getByRole('button', { name: 'Refresh' }).click();
    await expect.element(rowOf('orders').getByText('Not running', { exact: true })).toBeVisible();
    await expect.element(page.getByRole('alert')).not.toBeInTheDocument();
  });

  test('says when git is missing', async () => {
    open({ clones: [{ folder: 'orders' }], git: 'missing' });

    await expect.element(page.getByRole('alert').filter({ hasText: 'The packages could not be read' })).toMatchTextContent(GIT_REFUSALS.missing.error);
  });

  test('without a working folder it says how to set one', async () => {
    open({ workspace: false });

    await expect.element(page.getByRole('heading', { level: 2, name: 'No working folder' })).toBeVisible();
  });

  test('says when the working folder holds no package', async () => {
    open({ plain: ['docs'] });

    await expect.element(page.getByRole('heading', { level: 2, name: 'No packages' })).toBeVisible();
    expect(document.body.textContent).not.toMatch(/docs/);
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
  // the page's, which comes before the one an empty table offers
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
    await expect.element(rowOf('orders')).toMatchTextContent('orders1.0.0');
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

  test('says when the clone is no package, and does not list it', async () => {
    open({ clones: [{ folder: 'billing' }], sources: [{ path: 'acme/orders.git', manifest: null, files: { 'README.md': 'orders\n' } }] });

    await getButton().click();
    await field().fill(address);
    await dialog().getByRole('button', { name: 'Get', exact: true }).click();

    const got = page.getByRole('dialog', { name: 'Got orders' });
    await expect.element(got.getByText('The folder orders holds the clone, but it is no package')).toBeVisible();
    await expect.element(got).toMatchTextContent(REASONS.noManifest.error);
    await got.getByRole('button', { name: 'Close' }).last().click();
    await expect.poll(listed).toEqual(['billing']);
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
  // the page's, which comes before the one an empty table offers
  const newButton = () => page.getByRole('button', { name: 'New package' }).first();

  test('makes the package in the folder of its name, says a deployed environment needs the git host, lists it, and opens its tab', async () => {
    open({ clones: [{ folder: 'billing' }] });

    await newButton().click();
    await expect.element(dialog().getByText('A deployed environment needs the repository on the git host first', { exact: false })).toBeVisible();
    await expect.element(create()).toBeDisabled();
    await repositoryField().fill('.hidden');
    await expect.element(dialog().getByText(NEW_PACKAGE_REFUSALS.repository.error)).toBeVisible();
    await repositoryField().fill('orders');
    await nameField().fill('Orders');
    await expect.element(dialog().getByText(NEW_PACKAGE_REFUSALS.name.error)).toBeVisible();
    await expect.element(create()).toBeDisabled();
    await nameField().fill('orders-app');
    await create().click();

    await expect.element(page.getByRole('tab', { name: /orders$/, selected: true })).toBeVisible();
    await expect.element(dialog()).not.toBeInTheDocument();
    await page.getByRole('tab', { name: /Packages$/ }).click();
    await expect.element(rowOf('orders')).toMatchTextContent('orders-app0.1.0in the folder orders');
    await expect.element(rowOf('orders')).toMatchTextContent('Create package orders-app');
  });

  test('says why it was refused, and keeps the names to try again', async () => {
    open({ clones: [{ folder: 'orders' }] });

    await newButton().click();
    await repositoryField().fill('orders');
    await nameField().fill('orders');
    await create().click();

    await expect.element(dialog().getByRole('alert').filter({ hasText: 'The package was not made' })).toMatchTextContent(NEW_PACKAGE_REFUSALS.exists.error);
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

describe('the Packages screen in another language', () => {
  test('shows its texts, its ages and counts with their plurals, the refusals of the address and Ant Design in the language chosen', async () => {
    chooseLanguage('cs');
    const pins = [{ name: 'orders', repository: 'orders', commit: 'first', content: `sha256:${'1'.repeat(64)}` }];
    open({
      clones: [
        { folder: 'orders', commits: ['first', 'second'], upstream: { at: 'first', more: ['theirs'] } },
        { folder: 'billing', commits: ['first', 'second', 'third'] },
        { folder: 'shipping' },
      ],
      node: [
        { repository: 'orders', commit: 'second' },
        { repository: 'billing', commit: 'first', dependencies: pins },
        { repository: 'shipping', commit: 'first', dependencies: pins },
      ],
    });

    await expect.element(page.getByRole('heading', { level: 1, name: 'Balíčky' })).toBeVisible();
    await expect.element(page.getByRole('heading', { level: 2, name: 'Balíčky (3)' })).toBeVisible();
    const czech = page.getByRole('table', { name: 'Balíčky' });
    const row = (repository: string) => czech.getByRole('row').filter({ hasText: repository });
    await expect.element(row('orders')).toMatchTextContent('Seed Author, před 22 dny');
    await expect.element(row('orders')).toMatchTextContent(/[0-9a-f]{7} před 3 dny/);
    await expect.element(row('billing').getByText('Pozadu o 2')).toBeVisible();
    await expect.element(page.getByRole('button', { name: 'Obnovit' })).toBeVisible();
    await page.getByLabelText('Hledat balíčky', { exact: true }).fill('nic');
    await expect.element(czech.getByText('Žádný balíček neodpovídá „nic“')).toBeVisible();

    await page.getByRole('button', { name: 'Získat balíček…' }).first().click();
    const dialog = page.getByRole('dialog', { name: 'Získat balíček' });
    await dialog.getByLabelText('Adresa', { exact: true }).fill('https://git.example.com/acme/my orders.git');
    await expect.element(dialog.getByText('vložte jen adresu: obsahuje bílé znaky')).toBeVisible();
    await expect.element(dialog.getByRole('button', { name: 'Získat', exact: true })).toBeDisabled();
    await dialog.getByRole('button', { name: 'Zavřít', exact: true }).click();
    await expect.element(dialog).not.toBeInTheDocument();
  });
});
