import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { ArtifactKinds, type ArtifactKind, type NewArtifactProps } from '../artifacts';
import { say, textsIn } from '../language';
import { Page } from '../Page';
import { Shell } from '../Shell';
import { fakeStateOf, type Seed } from './contract/seed';
import { fakePackagesApi } from './fake';
import { findArtifacts, packagesScreen } from './screen';

const NOW = new Date('2026-11-23T10:00:00Z');

const FLOW = 'schemaVersion: 1\nkind: flow\nid: orders\nfrom: { endpoint: in }\nsteps: []\n';
const ENDPOINT = 'schemaVersion: 1\nkind: endpoint\nid: in\ntype: http\n';
const TEST = 'schemaVersion: 1\nkind: test\nid: orders-test\nflow: orders\n';
const BILLING = '# the billing of orders\nschemaVersion: 1\nkind: folder\nnamespace: urn:acme:billing\n';

/** A package of nested folders: billing with a namespace, its folder inbound with a flow, and a flow and a test of their own. */
const NESTED: Seed = {
  clones: [
    {
      folder: 'orders',
      files: {
        'flows/orders.yaml': FLOW,
        'billing/folder.yaml': BILLING,
        'billing/inbound/invoice.yaml': FLOW.replace('id: orders', 'id: invoice'),
        'endpoints/in.yaml': ENDPOINT,
        'tests/orders-test.yaml': TEST,
      },
    },
  ],
};

/** A New action as a module gives one: a button that names the folder it writes into and the folder within it it starts in. */
const newOf = (word: string) =>
  function New({ folder, within, onCreated }: NewArtifactProps) {
    return <button onClick={onCreated}>{`New ${word} in ${folder} within ${within === undefined ? 'its own folder' : JSON.stringify(within)}`}</button>;
  };

/** A kind as a module registers one, its tab titled by the kind and the path. */
const kindOf = (kind: ArtifactKind['kind'], extra: Partial<ArtifactKind> = {}): ArtifactKind => ({
  kind,
  open: (path) => ({ id: `${kind}:${path}`, title: `${kind} ${path}`, render: () => <Page title={path}>{`The ${kind} editor of ${path}`}</Page> }),
  Change: ({ path }) => <p>{`The change of ${path}`}</p>,
  ...extra,
});

/** The mappings the mapping kind lists in the folder mappings, which is no package, as the mapping service lists them. */
const CORPUS = [
  { path: 'mappings/order-id-copy.mapping.json', kind: 'mapping' as const, id: 'order-id-copy' },
  { path: 'mappings/deep/order-total.mapping.json', kind: 'mapping' as const, id: 'order-total' },
];

/** A mapping of the package orders, which the mapping service lists too. */
const TOTAL = { path: 'orders/mappings/total.mapping.json', kind: 'mapping' as const, id: 'total' };

/** The flow and the endpoint of the folder package, which is no package, as an adapter's folder holds them. */
const ADAPTER = [
  { path: 'package/flows/orders.yaml', kind: 'flow' as const, id: 'orders' },
  { path: 'package/endpoints/in.yaml', kind: 'endpoint' as const, id: 'in' },
];

const kinds = (list: ArtifactKind['list'] = async () => [...CORPUS, TOTAL]) =>
  new Map([
    ['flow', kindOf('flow', { New: newOf('flow'), list: async () => ADAPTER.filter(({ kind }) => kind === 'flow') })],
    ['endpoint', kindOf('endpoint', { list: async () => ADAPTER.filter(({ kind }) => kind === 'endpoint') })],
    ['mapping', kindOf('mapping', { New: newOf('mapping'), list })],
  ]);

let root: Root | undefined;

/** The Packages screen over `api`, the fake world of `seed`, with the kinds `registered`, and Quick open finding its artifacts. */
function render(seed: Seed, registered: ReadonlyMap<string, ArtifactKind> = kinds(), api = fakePackagesApi(fakeStateOf(seed).state)) {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <ArtifactKinds.Provider value={registered}>
      <Shell screens={[packagesScreen(api, () => NOW)]} start="packages" find={(found) => findArtifacts(api, found, () => NOW)} />
    </ArtifactKinds.Provider>,
  );
}

afterEach(() => {
  root?.unmount();
  root = undefined;
  document.body.innerHTML = '';
});

const button = (name: string) => page.getByRole('button', { name, exact: true });
const others = () => page.getByRole('table', { name: 'Folders that are no package' });

async function openArtifacts(repository: string) {
  await page.getByRole('table', { name: 'Packages' }).getByRole('button', { name: repository, exact: true }).click();
  await page.getByRole('tab', { name: /^Artifacts \(\d+\)$/ }).click();
}

const tree = () => page.getByRole('table', { name: 'Artifacts' });
/** The row of the tree whose name is `name`. */
const row = (name: string) => tree().getByRole('row').filter({ has: page.getByRole('button', { name, exact: true }) });
/** The names of the tree's rows, in their order, each indented by its depth. */
const names = () =>
  tree()
    .getByRole('row')
    .elements()
    .slice(1)
    .map((r) => `${'  '.repeat(Number(r.className.match(/ant-table-row-level-(\d+)/)?.[1] ?? 0))}${r.querySelector('td')?.textContent ?? ''}`);

describe('the artifacts of a package', () => {
  test('are one tree of its folders, folders first, each folder with its namespace and every artifact with its kind', async () => {
    render(NESTED);
    await openArtifacts('orders');
    await expect.element(page.getByRole('heading', { level: 2, name: 'Artifacts' })).toBeVisible();
    await expect.poll(names).toEqual(['billingurn:acme:billing', '  inbound', '    invoice', 'endpoints', '  in', 'flows', '  orders', 'tests', '  orders-test', 'package.yaml']);
    await expect.element(row('billing').getByLabelText('Namespace urn:acme:billing')).toBeVisible();
    await expect.element(row('invoice')).toMatchTextContent(/Flow/);
    await expect.element(tree().getByRole('row').filter({ hasText: 'orders-test' })).toMatchTextContent(/Test/);
    expect(page.getByRole('heading', { level: 2, name: 'Flows' }).elements()).toHaveLength(0);
  });

  test('write a new artifact into the folder chosen in the tree, and into its own folder when none is chosen', async () => {
    render(NESTED);
    await openArtifacts('orders');
    await expect.element(button('New flow in orders within its own folder')).toBeVisible();
    await expect.element(button('New mapping in orders within its own folder')).toBeVisible();

    await row('inbound').getByRole('button', { name: 'inbound', exact: true }).click();
    await expect.element(button('inbound')).toHaveAttribute('aria-pressed', 'true');
    await expect.element(page.getByText('New in billing/inbound/')).toBeVisible();
    await expect.element(button('New flow in orders within "billing/inbound"')).toBeVisible();
    await expect.element(button('New mapping in orders within "billing/inbound"')).toBeVisible();

    await button('Choose no folder').click();
    await expect.element(button('New flow in orders within its own folder')).toBeVisible();
    await row('billing').getByRole('button', { name: 'billing', exact: true }).click();
    await button('billing').click();
    await expect.element(button('New flow in orders within its own folder')).toBeVisible();
  });

  test('filter by kind in one click and by text in names, paths and namespaces', async () => {
    render(NESTED);
    await openArtifacts('orders');
    const kinds = page.getByRole('radiogroup', { name: 'Kinds shown' });
    await kinds.getByText('Flows 2').click();
    await expect.poll(names).toEqual(['billingurn:acme:billing', '  inbound', '    invoice', 'flows', '  orders']);
    await kinds.getByText('All').click();

    const filter = page.getByRole('searchbox', { name: 'Filter by name, path or namespace' });
    await filter.fill('acme:billing');
    await expect.poll(names).toEqual(['billingurn:acme:billing', '  inbound', '    invoice']);
    await filter.fill('tests/');
    await expect.poll(names).toEqual(['tests', '  orders-test']);
    await filter.fill('nothing like it');
    await expect.element(page.getByText('No artifact matches the filter.')).toBeVisible();
  });

  test('keep the folders closed while the tab lives, and Expand all opens them', async () => {
    render(NESTED);
    await openArtifacts('orders');
    await row('billing').getByRole('button', { name: /expand|collapse/i }).click();
    await expect.poll(names).toEqual(['billingurn:acme:billing', 'endpoints', '  in', 'flows', '  orders', 'tests', '  orders-test', 'package.yaml']);
    await page.getByRole('tab', { name: /^Changes/ }).click();
    await page.getByRole('tab', { name: /^Artifacts \(\d+\)$/ }).click();
    await expect.poll(names).toEqual(['billingurn:acme:billing', 'endpoints', '  in', 'flows', '  orders', 'tests', '  orders-test', 'package.yaml']);

    await button('Collapse all').click();
    await expect.poll(names).toEqual(['billingurn:acme:billing', 'endpoints', 'flows', 'tests', 'package.yaml']);
    await button('Expand all').click();
    await expect.poll(names).toHaveLength(10);
  });

  test('New folder… makes a folder with its namespace in the chosen folder, which the tree then shows chosen and open', async () => {
    render(NESTED);
    await openArtifacts('orders');
    await row('billing').getByRole('button', { name: /expand|collapse/i }).click();
    await row('billing').getByRole('button', { name: 'billing', exact: true }).click();
    await button('New folder…').click();
    const dialog = page.getByRole('dialog', { name: 'New folder' });
    await expect.element(dialog.getByText('orders/billing/')).toBeVisible();
    const name = dialog.getByRole('textbox', { name: 'Name' });
    await name.fill('Outbound');
    await expect.element(name).toHaveAccessibleDescription(/Outbound is no name of a folder/);
    await name.fill('outbound');
    const namespace = dialog.getByRole('textbox', { name: 'Namespace' });
    await namespace.fill('urn:acme:billing');
    await expect.element(namespace).toHaveAccessibleDescription(/is the namespace of the folder billing already/);
    await namespace.fill('urn:acme:billing:out');
    await dialog.getByRole('textbox', { name: 'Description' }).fill('What billing sends');
    await dialog.getByRole('button', { name: 'Create' }).click();

    await expect.element(dialog).not.toBeInTheDocument();
    await expect.element(row('outbound').getByLabelText('Namespace urn:acme:billing:out')).toBeVisible();
    await expect.element(button('outbound')).toHaveAttribute('aria-pressed', 'true');
    await expect.element(button('New flow in orders within "billing/outbound"')).toBeVisible();
    await expect.element(tree().getByRole('row').filter({ hasText: 'outbound' })).toMatchTextContent(/Added/);
  });

  test('Edit folder… sets the namespace of a folder, keeping what else its folder.yaml says', async () => {
    const seed = NESTED;
    const { state } = fakeStateOf(seed);
    const api = fakePackagesApi(state);
    render(seed, kinds(), api);
    await openArtifacts('orders');
    await button('Edit folder flows…').click();
    const dialog = page.getByRole('dialog', { name: 'Folder flows' });
    await dialog.getByRole('textbox', { name: 'Namespace' }).fill('urn:acme:orders');
    await dialog.getByRole('button', { name: 'Save' }).click();
    await expect.element(row('flows').getByLabelText('Namespace urn:acme:orders')).toBeVisible();

    await button('Edit folder billing…').click();
    const billing = page.getByRole('dialog', { name: 'Folder billing' });
    await expect.element(billing.getByRole('textbox', { name: 'Namespace' })).toHaveValue('urn:acme:billing');
    await billing.getByRole('textbox', { name: 'Namespace' }).fill('urn:acme:invoices');
    await billing.getByRole('button', { name: 'Save' }).click();
    await expect.element(row('billing').getByLabelText('Namespace urn:acme:invoices')).toBeVisible();
    const written = await api.file('orders', 'work', 'orders/billing/folder.yaml');
    expect('text' in written && written.text).toBe('# the billing of orders\nschemaVersion: 1\nkind: folder\nnamespace: urn:acme:invoices\n');
  });

  test('on a read-only server, offer no New, no New folder and no Edit folder', async () => {
    render({ ...NESTED, readOnly: true });
    await openArtifacts('orders');
    await expect.element(row('billing')).toBeVisible();
    expect(page.getByRole('button', { name: /^New |^Edit folder/ }).elements()).toHaveLength(0);
    expect(page.getByRole('columnheader', { name: 'Action' }).elements()).toHaveLength(0);
  });
});

describe('a folder that is no package', () => {
  test('that holds mappings opens from its row in a tab of the tree of its mappings with New mapping only, after Packages', async () => {
    render({ clones: [{ folder: 'orders' }], plain: ['mappings', 'notes'] });
    await expect.element(others().getByRole('row').filter({ hasText: 'notes' }).getByRole('button')).not.toBeInTheDocument();
    await others().getByRole('button', { name: 'mappings', exact: true }).click();

    await expect.element(page.getByRole('heading', { level: 1, name: 'mappings' })).toBeVisible();
    const table = tree();
    await expect.poll(names).toEqual(['deep', '  order-total', 'order-id-copy']);
    await expect.element(button('New mapping in mappings within ""')).toBeVisible();
    await row('deep').getByRole('button', { name: 'deep', exact: true }).click();
    await expect.element(button('New mapping in mappings within "deep"')).toBeVisible();
    expect(page.getByRole('button', { name: /^New flow|^New folder|^Edit folder/ }).elements()).toHaveLength(0);
    const crumbs = page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('listitem');
    await expect.poll(() => crumbs.elements().map((crumb) => crumb.textContent)).toEqual(['Design', 'Packages', 'mappings']);

    await table.getByRole('button', { name: 'order-total' }).click();
    await expect.element(page.getByText('The mapping editor of mappings/deep/order-total.mapping.json')).toBeVisible();
  });

  test('that holds flows and endpoints, as the folder of an adapter does, opens in a tab of their tree, with New flow', async () => {
    render({ plain: ['package', 'src'] });
    await expect.element(others().getByRole('row').filter({ hasText: 'src' }).getByRole('button')).not.toBeInTheDocument();
    await others().getByRole('button', { name: 'package', exact: true }).click();
    await expect.element(page.getByRole('heading', { level: 1, name: 'package' })).toBeVisible();
    await expect.element(button('New flow in package within "flows"')).toBeVisible();
    await expect.poll(names).toEqual(['endpoints', '  in', 'flows', '  orders']);
    await tree().getByRole('button', { name: 'in', exact: true }).click();
    await expect.element(page.getByText('The endpoint editor of package/endpoints/in.yaml')).toBeVisible();
    expect(page.getByRole('button', { name: /^New mapping/ }).elements()).toHaveLength(0);
  });

  test('when its mappings cannot be listed, is no link, and a warning says why', async () => {
    render({ clones: [{ folder: 'orders' }], plain: ['mappings'] }, kinds(async () => Promise.reject(new Error('the mapping service does not answer'))));
    await expect.element(page.getByText('the mapping service does not answer')).toBeVisible();
    await expect.element(others().getByRole('row').filter({ hasText: 'mappings' }).getByRole('button')).not.toBeInTheDocument();
  });
});

describe('the artifacts Quick open finds', () => {
  test("are each package's flows, endpoints and mappings after its tab, its tests on its Artifacts, and the mappings of a folder that is no package, by path", async () => {
    const { state } = fakeStateOf({
      clones: [{ folder: 'orders', files: { 'flows/orders.yaml': FLOW, 'endpoints/in.yaml': ENDPOINT, 'tests/orders-test.yaml': TEST, 'mappings/total.mapping.json': '{}' } }],
      plain: ['mappings'],
    });
    const found = await findArtifacts(fakePackagesApi(state), kinds(), () => NOW);
    const texts = textsIn('en');
    expect(found.map(({ name, path, kind, screen, trail }) => [name, path, say(texts, kind), screen.id, trail.map(({ id }) => id).join(' › ')])).toEqual([
      ['order-total', 'mappings/deep/order-total.mapping.json', 'Mapping', 'mapping:mappings/deep/order-total.mapping.json', 'packages › folder:mappings'],
      ['order-id-copy', 'mappings/order-id-copy.mapping.json', 'Mapping', 'mapping:mappings/order-id-copy.mapping.json', 'packages › folder:mappings'],
      ['in', 'orders/endpoints/in.yaml', 'Endpoint', 'endpoint:orders/endpoints/in.yaml', 'packages › package:orders'],
      ['orders', 'orders/flows/orders.yaml', 'Flow', 'flow:orders/flows/orders.yaml', 'packages › package:orders'],
      ['total', 'orders/mappings/total.mapping.json', 'Mapping', 'mapping:orders/mappings/total.mapping.json', 'packages › package:orders'],
      ['orders-test', 'orders/tests/orders-test.yaml', 'Test', 'package:orders', 'packages'],
    ]);
  });

  test("open a test in its package's tab on its Artifacts, chosen in the tree, and a corpus mapping after its folder's tab", async () => {
    render({ clones: [{ folder: 'orders', files: { 'tests/orders-test.yaml': TEST } }], plain: ['mappings'] });
    await expect.element(page.getByRole('table', { name: 'Packages' })).toBeVisible();
    const search = page.getByRole('textbox', { name: 'Find a screen, a tab or an artifact' });
    await userEvent.keyboard('{Control>}k{/Control}');
    await search.fill('orders-test');
    await page.getByRole('dialog', { name: 'Quick open' }).getByRole('button', { name: /^orders-test/ }).click();
    await expect.element(page.getByRole('heading', { level: 1, name: 'orders' })).toBeVisible();
    await expect.element(page.getByRole('tab', { name: /^Artifacts \(\d+\)$/ })).toHaveAttribute('aria-selected', 'true');
    const chosen = tree().getByRole('row').filter({ hasText: 'orders-test' });
    await expect.element(chosen).toHaveClass(/rail8-chosen/);
    await expect.element(page.getByText('New in tests/')).toBeVisible();

    await userEvent.keyboard('{Control>}k{/Control}');
    await search.fill('order-id');
    await userEvent.keyboard('{Enter}');
    await expect.element(page.getByText('The mapping editor of mappings/order-id-copy.mapping.json')).toBeVisible();
    const crumbs = page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('listitem');
    await expect.poll(() => crumbs.elements().map((crumb) => crumb.textContent)).toEqual(['Design', 'Packages', 'mappings', 'mapping mappings/order-id-copy.mapping.json']);
  });
});
