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

/** A New action as a module gives one: a button that names the folder it writes into and the file it writes beside. */
const newOf = (word: string) =>
  function New({ folder, beside, onCreated }: NewArtifactProps) {
    return <button onClick={onCreated}>{`New ${word} in ${folder} beside ${beside ?? 'nothing'}`}</button>;
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

/** The Packages screen over the fake world of `seed` with the kinds `registered`, and Quick open finding its artifacts. */
function render(seed: Seed, registered: ReadonlyMap<string, ArtifactKind> = kinds()) {
  const { state } = fakeStateOf(seed);
  const api = fakePackagesApi(state);
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

describe('the artifacts of a package', () => {
  test('on a server that takes writes, a kind that writes new ones has its card even when empty, with New in its corner beside the first of the card', async () => {
    render({ clones: [{ folder: 'orders', files: { 'flows/orders.yaml': FLOW } }] });
    await openArtifacts('orders');
    await expect.element(button('New flow in orders beside orders/flows/orders.yaml')).toBeVisible();
    await expect.element(page.getByRole('heading', { level: 2, name: 'Mappings' })).toBeVisible();
    await expect.element(button('New mapping in orders beside nothing')).toBeVisible();
    await expect.element(page.getByRole('heading', { level: 2, name: 'Endpoints' })).not.toBeInTheDocument();
  });

  test('on a read-only server, it offers no New and hides a card that is empty', async () => {
    render({ readOnly: true, clones: [{ folder: 'orders', files: { 'flows/orders.yaml': FLOW } }] });
    await openArtifacts('orders');
    await expect.element(page.getByRole('heading', { level: 2, name: 'Flows' })).toBeVisible();
    await expect.element(page.getByRole('heading', { level: 2, name: 'Mappings' })).not.toBeInTheDocument();
    expect(page.getByRole('button', { name: /^New / }).elements()).toHaveLength(0);
  });
});

describe('a folder that is no package', () => {
  test('that holds mappings opens from its row in a tab of its mappings with New mapping only, after Packages', async () => {
    render({ clones: [{ folder: 'orders' }], plain: ['mappings', 'notes'] });
    await expect.element(others().getByRole('row').filter({ hasText: 'notes' }).getByRole('button')).not.toBeInTheDocument();
    await others().getByRole('button', { name: 'mappings', exact: true }).click();

    await expect.element(page.getByRole('heading', { level: 1, name: 'mappings' })).toBeVisible();
    const table = page.getByRole('table', { name: 'Mappings' });
    await expect.element(table.getByRole('row')).toHaveLength(3);
    await expect.element(button('New mapping in mappings beside mappings/order-id-copy.mapping.json')).toBeVisible();
    await expect.element(page.getByRole('heading', { level: 2, name: 'Flows' })).not.toBeInTheDocument();
    const crumbs = page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('listitem');
    await expect.poll(() => crumbs.elements().map((crumb) => crumb.textContent)).toEqual(['Design', 'Packages', 'mappings']);

    await table.getByRole('button', { name: 'order-total' }).click();
    await expect.element(page.getByText('The mapping editor of mappings/deep/order-total.mapping.json')).toBeVisible();
  });

  test('that holds flows and endpoints, as the folder of an adapter does, opens in a tab of their cards, with New flow', async () => {
    render({ plain: ['package', 'src'] });
    await expect.element(others().getByRole('row').filter({ hasText: 'src' }).getByRole('button')).not.toBeInTheDocument();
    await others().getByRole('button', { name: 'package', exact: true }).click();
    await expect.element(page.getByRole('heading', { level: 1, name: 'package' })).toBeVisible();
    await expect.element(button('New flow in package beside package/flows/orders.yaml')).toBeVisible();
    await page.getByRole('table', { name: 'Endpoints' }).getByRole('button', { name: 'in', exact: true }).click();
    await expect.element(page.getByText('The endpoint editor of package/endpoints/in.yaml')).toBeVisible();
    expect(page.getByRole('heading', { level: 2, name: 'Mappings' }).elements()).toHaveLength(0);
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

  test("open a test in its package's tab on its Artifacts, and a corpus mapping after its folder's tab", async () => {
    render({ clones: [{ folder: 'orders', files: { 'tests/orders-test.yaml': TEST } }], plain: ['mappings'] });
    await expect.element(page.getByRole('table', { name: 'Packages' })).toBeVisible();
    const search = page.getByRole('textbox', { name: 'Find a screen, a tab or an artifact' });
    await userEvent.keyboard('{Control>}k{/Control}');
    await search.fill('orders-test');
    await page.getByRole('dialog', { name: 'Quick open' }).getByRole('button', { name: /^orders-test/ }).click();
    await expect.element(page.getByRole('heading', { level: 1, name: 'orders' })).toBeVisible();
    await expect.element(page.getByRole('tab', { name: /^Artifacts \(\d+\)$/ })).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('table', { name: 'Tests' }).getByText('orders-test', { exact: true })).toBeVisible();

    await userEvent.keyboard('{Control>}k{/Control}');
    await search.fill('order-id');
    await userEvent.keyboard('{Enter}');
    await expect.element(page.getByText('The mapping editor of mappings/order-id-copy.mapping.json')).toBeVisible();
    const crumbs = page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('listitem');
    await expect.poll(() => crumbs.elements().map((crumb) => crumb.textContent)).toEqual(['Design', 'Packages', 'mappings', 'mapping mappings/order-id-copy.mapping.json']);
  });
});
