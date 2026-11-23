import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import { ArtifactKinds, type ArtifactKind, type Side } from '../artifacts';
import { Shell } from '../Shell';
import { fakeStateOf, type Seed } from './contract/seed';
import type { PackagesApi } from './api';
import { fakePackagesApi } from './fake';
import { packagesScreen } from './screen';

const NOW = new Date('2026-11-23T10:00:00Z');

const FLOW = 'schemaVersion: 1\nkind: flow\nid: orders\nfrom: { endpoint: in }\nsteps:\n  - type: xslt\n    stylesheet: flows/orders.xsl\n';
const ENDPOINT = 'schemaVersion: 1\nkind: endpoint\nid: in\ntype: http\n';

/** A flow kind as a module registers one: its tab names the file, its change names the two sides' first lines. */
const sideText = (side: Side | null) => (side === null ? 'none' : `${side.rev === 'work' ? 'work' : 'commit'}: ${side.text.split('\n')[3]}`);
const FLOW_KIND: ArtifactKind = {
  kind: 'flow',
  open: (path) => ({ id: `flow:${path}`, title: `Flow ${path}`, render: () => <p>The flow editor of {path}</p> }),
  Change: ({ path, before, after }) => (
    <p>
      The flow change of {path}, {sideText(before)} to {sideText(after)}
    </p>
  ),
};

let root: Root | undefined;

/** The Packages screen over the fake world of `seed`, with the flow kind registered, and the tab of `orders` opened from its row. */
async function openOrders(seed: Seed, wrap: (api: PackagesApi) => PackagesApi = (api) => api) {
  const { state, ids } = fakeStateOf(seed);
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <ArtifactKinds.Provider value={new Map([['flow', FLOW_KIND]])}>
      <Shell screens={[packagesScreen(wrap(fakePackagesApi(state)), () => NOW)]} start="packages" />
    </ArtifactKinds.Provider>,
  );
  await page.getByRole('table', { name: 'Packages' }).getByRole('button', { name: 'orders', exact: true }).click();
  await expect.element(page.getByRole('heading', { level: 1, name: 'orders 1.0.0' })).toBeVisible();
  return { state, ids, folder: state.folders.find((f) => f.name === 'orders')! };
}

const changes = () => page.getByRole('table', { name: 'Changes' });
const changeRow = (file: string) => changes().getByRole('row').filter({ hasText: file });
const expand = (row: ReturnType<typeof changeRow>) => row.getByRole('button', { name: /expand row/i }).click();

afterEach(() => {
  root?.unmount();
  root = undefined;
  document.body.innerHTML = '';
});

describe('the tab of a package', () => {
  test('opens from its row on the Packages screen with its branch, last commit, upstream and what the node runs', async () => {
    await openOrders({ clones: [{ folder: 'orders', commits: ['first', 'second'] }], node: [{ repository: 'orders', commit: 'first' }] });

    await expect.element(page.getByRole('tab', { name: /orders$/, selected: true })).toBeVisible();
    const tab = page.getByLabelText('orders', { exact: true });
    await expect.element(tab.getByText('Branch', { exact: true })).toBeVisible();
    await expect.element(tab.getByText(/^second [0-9a-f]{7} Seed Author/)).toBeVisible();
    await expect.element(tab.getByText('No upstream')).toBeVisible();
    await expect.element(tab.getByText('Deployed on 127.0.0.1:8081')).toBeVisible();
    await expect.element(tab.getByText('Behind 1')).toBeVisible();
    await expect.element(page.getByRole('tab', { name: 'Changes (0)' })).toBeVisible();
    await expect.element(page.getByText('No file differs from the last commit.')).toBeVisible();
  });

  test('lists each changed file with its kind, its change and how it is staged', async () => {
    await openOrders({
      clones: [
        {
          folder: 'orders',
          files: { 'notes.txt': 'one\ntwo\n', 'flows/orders.yaml': FLOW, 'old.txt': 'old\n' },
          staged: { 'staged.txt': 'staged\n' },
          edits: { 'notes.txt': 'one\nthree\n', 'first.txt': null, 'new.txt': 'new\n', 'flows/orders.yaml': FLOW.replace('in }', 'out }') },
          moves: { 'old.txt': 'renamed.txt' },
        },
      ],
      node: [{ repository: 'orders', commit: 'first' }],
    });

    await expect.element(page.getByRole('tab', { name: 'Changes (6)' })).toBeVisible();
    await expect.element(page.getByText('Uncommitted changes are never deployed.')).toBeVisible();
    await expect.element(changeRow('notes.txt')).toMatchTextContent('notes.txtFileModified');
    await expect.element(changeRow('first.txt')).toMatchTextContent('Deleted');
    await expect.element(changeRow('new.txt')).toMatchTextContent('Added');
    await expect.element(changeRow('orders.yaml')).toMatchTextContent('flows/orders.yamlFlowModified');
    await expect.element(changeRow('renamed.txt')).toMatchTextContent('renamed.txtfrom old.txtFileRenamedstaged');
    await expect.element(changeRow('staged.txt')).toMatchTextContent('Addedstaged');
  });

  test('shows the line diff of a text file, and a registered kind draws its own change', async () => {
    await openOrders({
      clones: [{ folder: 'orders', files: { 'notes.txt': 'one\ntwo\n', 'flows/orders.yaml': FLOW }, edits: { 'notes.txt': 'one\nthree\n', 'flows/orders.yaml': FLOW.replace('in }', 'out }') } }],
    });

    await expand(changeRow('notes.txt'));
    const diff = page.getByLabelText('Line diff');
    await expect.element(diff).toBeVisible();
    expect(diff.element().textContent).toBe('  one- two+ three');

    await expand(changeRow('orders.yaml'));
    await expect.element(page.getByText('The flow change of orders/flows/orders.yaml, commit: from: { endpoint: in } to work: from: { endpoint: out }')).toBeVisible();
  });

  test('shows a package.lock by its dependencies, with each pin before and after', async () => {
    const lock = (commit: string) =>
      `schemaVersion: 1\nkind: lock\ndependencies:\n  - name: "common"\n    version: "1.2.0"\n    content: "sha256:${'1'.repeat(64)}"\n    repository: "common"\n    commit: "${commit}"\n`;
    await openOrders({ clones: [{ folder: 'orders', files: { 'package.lock': lock('a'.repeat(40)) }, edits: { 'package.lock': lock('b'.repeat(40)) } }] });

    await expand(changeRow('package.lock'));
    const row = page.getByRole('table', { name: 'Dependencies of orders/package.lock' }).getByRole('row').filter({ hasText: 'common' });
    await expect.element(row).toMatchTextContent(`common1.2.01.2.0common at ${'a'.repeat(40)}, sha256:${'1'.repeat(64)}common at ${'b'.repeat(40)}, sha256:${'1'.repeat(64)}Changed`);
  });

  test('shows the two sizes of a binary file, and reads "too large to compare" for a file over 1 MiB', async () => {
    await openOrders({
      clones: [{ folder: 'orders', files: { 'logo.png': 'PNG\0ab', 'big.txt': 'small\n' }, edits: { 'logo.png': 'PNG\0abcd', 'big.txt': 'x'.repeat(1024 * 1024 + 1) } }],
    });

    await expand(changeRow('logo.png'));
    await expect.element(page.getByText('Binary file: 6 bytes before, 8 bytes after.')).toBeVisible();

    const big = changeRow('big.txt');
    await expect.element(big).toMatchTextContent('Modifiedtoo large to compare');
    await expand(big);
    await expect.element(changes().getByText('too large to compare')).toHaveLength(2);
  });

  test('reads the package again when a file changed after its change was listed, and when the window gets the focus', async () => {
    const { folder } = await openOrders({ clones: [{ folder: 'orders', files: { 'notes.txt': 'one\n' }, edits: { 'notes.txt': 'two\n' } }] });
    await expect.element(page.getByRole('tab', { name: 'Changes (1)' })).toBeVisible();

    folder.disk['notes.txt'] = 'three\n';
    folder.disk['new.txt'] = 'new\n';
    await expand(changeRow('notes.txt'));
    await expect.element(page.getByRole('tab', { name: 'Changes (2)' })).toBeVisible();

    folder.disk['other.txt'] = 'other\n';
    window.dispatchEvent(new Event('focus'));
    await expect.element(page.getByRole('tab', { name: 'Changes (3)' })).toBeVisible();
  });

  test('lists the artifacts by kind with the files they use, and opens a flow in its editor', async () => {
    await openOrders({
      clones: [
        {
          folder: 'orders',
          files: { 'flows/orders.yaml': FLOW, 'flows/orders.xsl': '<xsl:stylesheet/>\n', 'endpoints/in.yaml': ENDPOINT },
          edits: { 'flows/orders.yaml': FLOW.replace('in }', 'out }') },
        },
      ],
    });
    await page.getByRole('tab', { name: 'Artifacts' }).click();

    await expect.element(page.getByRole('heading', { level: 3, name: 'Flows' })).toBeVisible();
    const flows = page.getByRole('table', { name: 'Flows' });
    await expect.element(flows.getByRole('row').nth(1)).toMatchTextContent('ordersflows/orders.yamlModified');
    await expect.element(page.getByRole('table', { name: 'Endpoints' }).getByRole('row').nth(1)).toMatchTextContent('inendpoints/in.yaml—');
    await expect.element(page.getByRole('table', { name: 'Package files' }).getByRole('row').nth(1)).toMatchTextContent('package.yaml');
    await expect.element(page.getByRole('table', { name: 'Files they use' }).getByRole('row').nth(1)).toMatchTextContent('orders.xslflows/orders.xsl—flows/orders.yaml');
    await expect.element(page.getByRole('table', { name: 'Endpoints' }).getByRole('button')).not.toBeInTheDocument();

    await flows.getByRole('button', { name: 'orders' }).click();
    await expect.element(page.getByText('The flow editor of orders/flows/orders.yaml')).toBeVisible();
  });

  test('lists the history with the commit the node runs, opens the files a commit changed, and pages to older commits', async () => {
    const labels = Array.from({ length: 52 }, (_, n) => `c${String(n).padStart(2, '0')}`);
    await openOrders({
      clones: [{ folder: 'orders', commits: labels, commitFiles: { c51: { 'c00.txt': 'changed\n' } } }],
      node: [{ repository: 'orders', commit: 'c50' }],
    });
    await page.getByRole('tab', { name: 'History' }).click();

    const history = page.getByRole('table', { name: 'History' });
    const rows = () => history.getByRole('row').filter({ hasText: /^c\d\d(?!\.txt)/ });
    await expect.element(rows()).toHaveLength(50);
    await expect.element(rows().nth(0)).toMatchTextContent(/^c51[0-9a-f]{7}Seed Author/);
    await expect.element(rows().nth(1).getByText('Runs on 127.0.0.1:8081')).toBeVisible();
    await expect.element(rows().nth(0).getByText('Runs on 127.0.0.1:8081')).not.toBeInTheDocument();

    await rows().nth(0).getByRole('button', { name: /expand row/i }).click();
    const changed = page.getByRole('table', { name: /^Files changed by [0-9a-f]{7}$/ });
    await expect.element(changed.getByRole('row').filter({ hasText: 'c51.txt' })).toMatchTextContent('Added');
    await expect.element(changed.getByRole('row').filter({ hasText: 'c00.txt' })).toMatchTextContent('Modified');
    await changed.getByRole('row').filter({ hasText: 'c00.txt' }).getByRole('button', { name: /expand row/i }).click();
    expect(changed.getByLabelText('Line diff').element().textContent).toBe('- c00+ changed');

    await page.getByRole('button', { name: 'Show older commits' }).click();
    await expect.element(rows()).toHaveLength(52);
    await expect.element(page.getByRole('button', { name: 'Show older commits' })).not.toBeInTheDocument();
    await rows().nth(51).getByRole('button', { name: /expand row/i }).click();
    await expect.element(page.getByText('The first commit of the branch: it adds every file of its tree.')).toBeVisible();
  });

  test('drops a page of older commits that arrives after the history was read again', async () => {
    const labels = Array.from({ length: 51 }, (_, n) => `c${String(n).padStart(2, '0')}`);
    let release = () => {};
    const { folder } = await openOrders({ clones: [{ folder: 'orders', commits: labels }] }, (api) => ({
      ...api,
      log: async (repository, at) => {
        const page = await api.log(repository, at);
        if (at?.before !== undefined) await new Promise<void>((resolve) => (release = resolve));
        return page;
      },
    }));
    await page.getByRole('tab', { name: 'History' }).click();
    const rows = () => page.getByRole('table', { name: 'History' }).getByRole('row').filter({ hasText: /^c\d\d/ });
    await expect.element(rows()).toHaveLength(50);

    await page.getByRole('button', { name: 'Show older commits' }).click();
    const head = folder.commits.at(-1)!;
    folder.commits.push({ ...head, id: 'f'.repeat(40), parents: [head.id], subject: 'c51' });
    folder.head.commit = 'f'.repeat(40);
    await page.getByRole('button', { name: 'Refresh' }).click();
    await expect.element(rows().nth(0)).toMatchTextContent(/^c51/);
    release();

    await expect.element(page.getByRole('button', { name: 'Show older commits' })).toBeEnabled();
    await expect.element(rows()).toHaveLength(50);
  });
});
