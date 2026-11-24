import { useEffect, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import { ArtifactKinds, type ArtifactKind, type Side } from '../artifacts';
import { sessionClient, type Me } from '../session';
import { SessionGate } from '../SessionGate';
import { Shell } from '../Shell';
import { useWorkingFile } from '../workingFiles';
import { AUTHOR, fakeStateOf, type Seed } from './contract/seed';
import type { PackagesApi } from './api';
import { fakePackagesApi } from './fake';
import { packagesScreen } from './screen';

const NOW = new Date('2026-11-23T10:00:00Z');

const FLOW = 'schemaVersion: 1\nkind: flow\nid: orders\nfrom: { endpoint: in }\nsteps:\n  - type: xslt\n    stylesheet: flows/orders.xsl\n';
const ENDPOINT = 'schemaVersion: 1\nkind: endpoint\nid: in\ntype: http\n';

/** Gives the open flow editor unsaved changes, as an edit the editor makes on its own while it is hidden. */
let editFlow: (() => void) | undefined;

/** A flow editor as a module's tab holds one: Edit gives it unsaved changes, and it counts how often it read its file again. */
function FlowEditor({ path }: { path: string }) {
  const [dirty, setDirty] = useState(false);
  const [reads, setReads] = useState(0);
  useWorkingFile(path, () => dirty, () => setReads((n) => n + 1));
  useEffect(() => {
    editFlow = () => setDirty(true);
  });
  return (
    <>
      <p>The flow editor of {path}</p>
      <button onClick={() => setDirty(true)}>Edit the flow</button>
      <p>Read again {reads} times</p>
    </>
  );
}

/** A flow kind as a module registers one: its tab edits the file, its change names the two sides' first lines. */
const sideText = (side: Side | null) => (side === null ? 'none' : `${side.rev === 'work' ? 'work' : 'commit'}: ${side.text.split('\n')[3]}`);
const FLOW_KIND: ArtifactKind = {
  kind: 'flow',
  open: (path) => ({ id: `flow:${path}`, title: `Flow ${path}`, render: () => <FlowEditor path={path} /> }),
  Change: ({ path, before, after }) => (
    <p>
      The flow change of {path}, {sideText(before)} to {sideText(after)}
    </p>
  ),
};

let root: Root | undefined;

/** A session that may read packages but not what the node runs. */
const NO_DEPLOYMENTS: Me = {
  name: 'bob',
  kind: 'person',
  roles: [],
  actions: [],
  refused: { 'deployment.read': { code: 'not-allowed', needs: ['developer', 'operator'] } },
  via: 'session',
  firstPassword: false,
  expiresAt: '2026-11-23T20:00:00Z',
  emergencyKey: false,
  session: '0123456789abcdef',
};

/**
 * The Packages screen over the fake world of `seed`, with the flow kind registered, and the tab of `orders` opened from
 * its row. With `me`, the screen is behind the session gate of a session that `GET /session` answers with `me`.
 */
async function openOrders(seed: Seed, wrap: (api: PackagesApi) => PackagesApi = (api) => api, me?: Me) {
  const { state, ids } = fakeStateOf(seed);
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  const shell = (
    <ArtifactKinds.Provider value={new Map([['flow', FLOW_KIND]])}>
      <Shell screens={[packagesScreen(wrap(fakePackagesApi(state)), () => NOW)]} start="packages" />
    </ArtifactKinds.Provider>
  );
  if (me === undefined) root.render(shell);
  else {
    const client = sessionClient({
      send: (async () => new Response(JSON.stringify(me), { status: 200, headers: { 'content-type': 'application/json' } })) as typeof fetch,
      reload: () => undefined,
      timer: () => new Promise(() => undefined),
    });
    root.render(<SessionGate client={client}>{shell}</SessionGate>);
  }
  await page.getByRole('table', { name: 'Packages' }).getByRole('button', { name: 'orders', exact: true }).click();
  await expect.element(page.getByRole('heading', { level: 1, name: 'orders' })).toBeVisible();
  return { state, ids, folder: state.folders.find((f) => f.name === 'orders')! };
}

const changes = () => page.getByRole('table', { name: 'Changes' });
const changeRow = (file: string) => changes().getByRole('row').filter({ hasText: file });
const expand = (row: ReturnType<typeof changeRow>) => row.getByRole('button', { name: /expand row/i }).click();

const dialog = () => page.getByRole('dialog', { name: 'Commit to main' });
const box = (file: string) => dialog().getByRole('checkbox', { name: `Commit ${file}`, exact: true });
const dialogRow = (file: string) => dialog().getByRole('row').filter({ hasText: file });
const button = (name: string) => page.getByRole('button', { name, exact: true });
// the button's loading icon may still be leaving, and named, just after a commit
const commitButton = () => dialog().getByRole('button', { name: /Commit$/ });

/** Opens the flow of the package in its editor from the Artifacts tab, and gives it unsaved changes when `edit`, then shows the package's tab again. */
async function openFlow(edit: boolean) {
  await page.getByRole('tab', { name: /^Artifacts \(\d+\)$/ }).click();
  await page.getByRole('table', { name: 'Flows' }).getByRole('button', { name: 'orders' }).click();
  await expect.element(page.getByText('The flow editor of orders/flows/orders.yaml')).toBeVisible();
  if (edit) await button('Edit the flow').click();
  await page.getByRole('tab', { name: /orders$/ }).click();
  await page.getByRole('tab', { name: /^Changes/ }).click();
}

/** Opens the commit dialog and writes `message`. */
async function startCommit(message: string) {
  await button('Commit…').click();
  await expect.element(dialog()).toBeVisible();
  await dialog().getByLabelText('Message', { exact: true }).fill(message);
}

afterEach(() => {
  root?.unmount();
  root = undefined;
  editFlow = undefined;
  document.body.innerHTML = '';
});

describe('the tab of a package', () => {
  test('opens from its row on the Packages screen with its branch, last commit, upstream and what the node runs', async () => {
    await openOrders({ clones: [{ folder: 'orders', commits: ['first', 'second'] }], node: [{ repository: 'orders', commit: 'first' }] });

    await expect.element(page.getByRole('tab', { name: /orders$/, selected: true })).toBeVisible();
    const tab = page.getByLabelText('orders', { exact: true });
    await expect.element(tab.getByText('1.0.0', { exact: true })).toBeVisible();
    await expect.element(tab.getByText('Branch', { exact: true })).toBeVisible();
    await expect.element(tab.getByText(/^second[0-9a-f]{7} Seed Author/)).toBeVisible();
    await expect.element(tab.getByText('No upstream')).toBeVisible();
    await expect.element(tab.getByText('Deployed on 127.0.0.1:8081')).toBeVisible();
    await expect.element(tab.getByText('Behind 1')).toBeVisible();
    await expect.element(page.getByRole('tab', { name: 'Changes (0)' })).toBeVisible();
    await expect.element(page.getByText('No file differs from the last commit.')).toBeVisible();
    await expect.element(page.getByText('Uncommitted changes are never deployed.')).not.toBeInTheDocument();
  });

  test('shows why, in place of what the node runs, to a session that may not read deployments', async () => {
    await openOrders({ clones: [{ folder: 'orders', commits: ['first', 'second'] }], node: [{ repository: 'orders', commit: 'first' }] }, undefined, NO_DEPLOYMENTS);

    const why = 'Needs the role developer or operator';
    const tab = page.getByLabelText('orders', { exact: true });
    await expect.element(tab.getByText('Deployed on 127.0.0.1:8081')).toBeVisible();
    await expect.element(tab.getByText(why)).toBeVisible();
    await expect.element(tab.getByText('Behind 1')).not.toBeInTheDocument();
    await tab.getByText('-', { exact: true }).hover();
    await expect.element(page.getByRole('tooltip')).toHaveTextContent(why);

    await page.getByRole('tab', { name: 'Packages' }).click();
    const row = page.getByRole('table', { name: 'Packages' }).getByRole('row').filter({ hasText: 'orders' });
    await expect.element(row).toMatchTextContent(why);
    await expect.element(row.getByText('Behind 1')).not.toBeInTheDocument();
  });

  test('keeps each of its own tabs as it was left while another one is shown', async () => {
    const labels = Array.from({ length: 52 }, (_, n) => `c${String(n).padStart(2, '0')}`);
    await openOrders({ clones: [{ folder: 'orders', commits: labels, edits: { 'c00.txt': 'edited\n' } }] });
    await expand(changeRow('c00.txt'));
    await expect.element(changes().getByLabelText('Line diff')).toBeVisible();
    await page.getByRole('tab', { name: 'History' }).click();
    const rows = () => page.getByRole('table', { name: 'History' }).getByRole('row').filter({ hasText: /^c\d\d/ });
    await page.getByRole('button', { name: 'Show older commits' }).click();
    await expect.element(rows()).toHaveLength(52);

    await page.getByRole('tab', { name: /^Changes/ }).click();
    await expect.element(changes().getByLabelText('Line diff')).toBeVisible();
    await page.getByRole('tab', { name: 'History' }).click();
    await expect.element(rows()).toHaveLength(52);
  });

  test('says when the history could not be read, and reads it again on Try again', async () => {
    let refuse = true;
    await openOrders({ clones: [{ folder: 'orders', commits: ['first', 'second'] }] }, (api) => ({
      ...api,
      log: async (repository, at) => {
        if (refuse) throw new Error('git log failed');
        return api.log(repository, at);
      },
    }));
    await page.getByRole('tab', { name: 'History' }).click();
    await expect.element(page.getByText('The history could not be read')).toBeVisible();
    await expect.element(page.getByText('git log failed')).toBeVisible();

    refuse = false;
    await button('Try again').click();
    await expect.element(page.getByRole('table', { name: 'History' }).getByRole('row').filter({ hasText: /^second/ })).toBeVisible();
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
    await page.getByRole('tab', { name: /^Artifacts \(\d+\)$/ }).click();

    await expect.element(page.getByRole('heading', { level: 2, name: 'Flows' })).toBeVisible();
    const flows = page.getByRole('table', { name: 'Flows' });
    await expect.element(flows.getByRole('row').nth(1)).toMatchTextContent('ordersflows/orders.yamlModified');
    await expect.element(page.getByRole('table', { name: 'Endpoints' }).getByRole('row').nth(1)).toMatchTextContent('inendpoints/in.yaml-');
    await expect.element(page.getByRole('table', { name: 'Package files' }).getByRole('row').nth(1)).toMatchTextContent('package.yaml');
    await expect.element(page.getByRole('table', { name: 'Files they use' }).getByRole('row').nth(1)).toMatchTextContent('orders.xslflows/orders.xslflows/orders.yaml-');
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

  test('Commit… commits as git’s identity every change but one unsaved in an open tab, one staged in a terminal with other content and one too large to compare, which the author may tick', async () => {
    await openOrders({
      clones: [
        {
          folder: 'orders',
          files: { 'notes.txt': 'one\n', 'flows/orders.yaml': FLOW, 'big.txt': 'small\n', 'mixed.txt': 'mixed\n' },
          staged: { 'mixed.txt': 'staged\n' },
          edits: { 'notes.txt': 'two\n', 'flows/orders.yaml': FLOW.replace('in }', 'out }'), 'big.txt': 'x'.repeat(1024 * 1024 + 1), 'mixed.txt': 'disk\n', 'new.txt': 'new\n' },
        },
      ],
    });
    await openFlow(true);
    await button('Commit…').click();

    await expect.element(dialog().getByText(`Committing as ${AUTHOR.name} <${AUTHOR.email}>`)).toBeVisible();
    await expect.element(box('notes.txt')).toBeChecked();
    await expect.element(box('new.txt')).toBeChecked();
    await expect.element(box('flows/orders.yaml')).not.toBeChecked();
    await expect.element(dialogRow('flows/orders.yaml')).toMatchTextContent('unsaved in an open tab');
    await expect.element(box('mixed.txt')).not.toBeChecked();
    await expect.element(box('mixed.txt')).toBeDisabled();
    await expect.element(dialogRow('mixed.txt')).toMatchTextContent('staged in a terminal with other content: commit or unstage it there');
    await expect.element(box('big.txt')).not.toBeChecked();
    await expect.element(dialogRow('big.txt')).toMatchTextContent('too large to compare');
    await expect.element(commitButton()).toBeDisabled();

    await dialog().getByLabelText('Message', { exact: true }).fill('Edit the notes');
    await box('big.txt').click();
    await box('new.txt').click();
    await commitButton().click();

    await expect.element(dialog()).not.toBeInTheDocument();
    await expect.element(page.getByText(/^Committed [0-9a-f]{7} Edit the notes$/)).toBeVisible();
    await expect.element(page.getByRole('tab', { name: 'Changes (3)' })).toBeVisible();
    await expect.element(changeRow('new.txt')).toBeVisible();
    await expect.element(changeRow('notes.txt')).not.toBeInTheDocument();
    await expect.element(changeRow('big.txt')).not.toBeInTheDocument();
  });

  test('Show tab closes the dialog and shows the tab that holds the file unsaved, and an open tab without unsaved changes leaves its file ticked', async () => {
    const seed: Seed = { clones: [{ folder: 'orders', files: { 'flows/orders.yaml': FLOW }, edits: { 'flows/orders.yaml': FLOW.replace('in }', 'out }') } }] };
    await openOrders(seed);
    await openFlow(false);
    await button('Commit…').click();
    await expect.element(box('flows/orders.yaml')).toBeChecked();
    await dialog().getByRole('button', { name: 'Cancel' }).click();

    await page.getByRole('tab', { name: /^Flow / }).click();
    await button('Edit the flow').click();
    await page.getByRole('tab', { name: /orders$/ }).click();
    await button('Commit…').click();
    await expect.element(box('flows/orders.yaml')).not.toBeChecked();
    await dialogRow('flows/orders.yaml').getByRole('button', { name: 'Show tab' }).click();

    await expect.element(dialog()).not.toBeInTheDocument();
    await expect.element(page.getByRole('tab', { name: /^Flow /, selected: true })).toBeVisible();
  });

  test('a file that gets unsaved changes in an open tab while the dialog is open is unticked when Commit is pressed, and nothing is committed until it is pressed again', async () => {
    await openOrders({ clones: [{ folder: 'orders', files: { 'notes.txt': 'one\n', 'flows/orders.yaml': FLOW }, edits: { 'notes.txt': 'two\n', 'flows/orders.yaml': FLOW.replace('in }', 'out }') } }] });
    await openFlow(false);
    await startCommit('Edit the notes');
    await expect.element(box('flows/orders.yaml')).toBeChecked();

    editFlow!();
    await commitButton().click();

    await expect.element(dialog().getByText('flows/orders.yaml got unsaved changes in an open tab meanwhile, so it is unticked: tick it to commit the version on disk.')).toBeVisible();
    await expect.element(box('flows/orders.yaml')).not.toBeChecked();
    await expect.element(dialogRow('flows/orders.yaml')).toMatchTextContent('unsaved in an open tab');
    await expect.element(page.getByRole('tab', { name: 'Changes (2)' })).toBeInTheDocument();
    await commitButton().click();

    await expect.element(dialog()).not.toBeInTheDocument();
    await expect.element(page.getByRole('tab', { name: 'Changes (1)' })).toBeVisible();
    await expect.element(changeRow('flows/orders.yaml')).toBeVisible();
  });

  test('a hook that changes a chosen file on disk without staging it says so, and the open tab without unsaved changes reads it again', async () => {
    await openOrders({
      clones: [{ folder: 'orders', files: { 'flows/orders.yaml': FLOW }, edits: { 'flows/orders.yaml': FLOW.replace('in }', 'out }') }, hook: { unstaged: { 'flows/orders.yaml': FLOW.replace('in }', 'by the hook }') } } }],
    });
    await openFlow(false);
    await startCommit('Edit the flow');
    await commitButton().click();

    const result = page.getByRole('dialog', { name: 'Committed' });
    await expect.element(result.getByText('A hook changed files you chose on disk')).toBeVisible();
    await expect.element(result.getByText("The commit holds the version of flows/orders.yaml you reviewed; the file on disk holds the hook's, which is not committed.")).toBeVisible();
    await result.getByRole('button', { name: 'Close' }).last().click();
    await expect.element(page.getByRole('tab', { name: 'Changes (1)' })).toBeVisible();

    await page.getByRole('tab', { name: /^Flow / }).click();
    await expect.element(page.getByText('Read again 1 times')).toBeVisible();
  });

  test('a renamed file is committed with the path it was renamed from, which goes along with its own tag when it is on disk and ticked', async () => {
    await openOrders({ clones: [{ folder: 'orders', files: { 'old.txt': 'moved\n' }, moves: { 'old.txt': 'moved/new.txt' } }] });
    await startCommit('Move the notes');
    await expect.element(dialogRow('moved/new.txt')).toMatchTextContent('from old.txt');
    await commitButton().click();

    await expect.element(dialog()).not.toBeInTheDocument();
    await expect.element(page.getByRole('tab', { name: 'Changes (0)' })).toBeVisible();
    root?.unmount();
    document.body.innerHTML = '';

    await openOrders({ clones: [{ folder: 'orders', files: { 'old.txt': 'moved\n' }, moves: { 'old.txt': 'moved/new.txt' }, edits: { 'old.txt': 'old, written again\n' } }] });
    await startCommit('Move the notes, keep the old ones');
    await box('old.txt').click();
    await commitButton().click();
    await expect.element(dialog().getByText('orders/old.txt, which orders/moved/new.txt was renamed from, is on disk: choose it too; nothing was committed')).toBeVisible();

    await box('old.txt').click();
    await commitButton().click();
    await expect.element(dialog()).not.toBeInTheDocument();
    await expect.element(page.getByRole('tab', { name: 'Changes (0)' })).toBeVisible();
  });

  test('a file the author did not choose that a hook changed on disk has its open tab without unsaved changes read again', async () => {
    await openOrders({
      clones: [{ folder: 'orders', files: { 'notes.txt': 'one\n', 'flows/orders.yaml': FLOW }, edits: { 'notes.txt': 'two\n', 'flows/orders.yaml': FLOW.replace('in }', 'out }') }, hook: { unstaged: { 'flows/orders.yaml': FLOW.replace('in }', 'by the hook }') } } }],
    });
    await openFlow(false);
    await startCommit('Edit the notes');
    await box('flows/orders.yaml').click();
    await commitButton().click();

    await expect.element(dialog()).not.toBeInTheDocument();
    await page.getByRole('tab', { name: /^Flow / }).click();
    await expect.element(page.getByText('Read again 1 times')).toBeVisible();
  });

  test('a file that a hook changed on disk while it had no change has its open tab without unsaved changes read again', async () => {
    await openOrders({
      clones: [{ folder: 'orders', files: { 'notes.txt': 'one\n', 'flows/orders.yaml': FLOW }, edits: { 'notes.txt': 'two\n' }, hook: { unstaged: { 'flows/orders.yaml': FLOW.replace('in }', 'by the hook }') } } }],
    });
    await openFlow(false);
    await startCommit('Edit the notes');
    await commitButton().click();

    await expect.element(dialog()).not.toBeInTheDocument();
    await expect.element(page.getByRole('tab', { name: 'Changes (1)' })).toBeVisible();
    await page.getByRole('tab', { name: /^Flow / }).click();
    await expect.element(page.getByText('Read again 1 times')).toBeVisible();
  });

  test('a file the author did not choose that nothing changed keeps its open tab as it is', async () => {
    await openOrders({ clones: [{ folder: 'orders', files: { 'notes.txt': 'one\n', 'flows/orders.yaml': FLOW }, edits: { 'notes.txt': 'two\n', 'flows/orders.yaml': FLOW.replace('in }', 'out }') } }] });
    await openFlow(false);
    await startCommit('Edit the notes');
    await box('flows/orders.yaml').click();
    await commitButton().click();

    await expect.element(dialog()).not.toBeInTheDocument();
    await expect.element(page.getByRole('tab', { name: 'Changes (1)' })).toBeVisible();
    await page.getByRole('tab', { name: /^Flow / }).click();
    await expect.element(page.getByText('Read again 0 times')).toBeVisible();
  });

  test('a commit git was stopped in after it committed says that git may have committed, never that nothing was', async () => {
    await openOrders({ clones: [{ folder: 'orders', files: { 'notes.txt': 'one\n' }, edits: { 'notes.txt': 'two\n' }, hook: { stopsGit: true } }] });
    await startCommit('Edit the notes');
    await commitButton().click();

    await expect.element(dialog().getByText('The commit was refused')).toBeVisible();
    await expect.element(dialog().getByText(/HEAD moved to [0-9a-f]{7} meanwhile, so git may have committed/)).toBeVisible();
    expect(dialog().getByText(/nothing was committed/i).elements()).toHaveLength(0);
  });

  test('a refused commit says why with what the hooks said, keeps the dialog, and a commit refused for a stale file goes through once the package is read again', async () => {
    const { folder } = await openOrders({
      clones: [{ folder: 'orders', files: { 'notes.txt': 'one\n' }, edits: { 'notes.txt': 'two\n' }, hook: { refuses: true, says: 'lint: notes.txt ends without a full stop' } }],
    });
    await startCommit('Edit the notes');
    await commitButton().click();

    await expect.element(dialog().getByText('The commit was refused')).toBeVisible();
    await expect.element(dialog().getByText('git commit failed, as git and the hooks say; nothing was committed')).toBeVisible();
    await expect.element(dialog().getByLabelText('What git and the hooks said')).toHaveTextContent('lint: notes.txt ends without a full stop');

    delete folder.hook;
    folder.disk['notes.txt'] = 'three\n';
    await commitButton().click();
    await expect.element(dialog().getByText('orders/notes.txt changed since you reviewed it; nothing was committed')).toBeVisible();
    await expect.element(dialog().getByLabelText('Message', { exact: true })).toHaveValue('Edit the notes');

    await commitButton().click();
    await expect.element(dialog()).not.toBeInTheDocument();
    await expect.element(page.getByRole('tab', { name: 'Changes (0)' })).toBeVisible();
  });

  test('a commit a hook or a terminal changed says how, with what git and the hooks said, and the open tabs without unsaved changes of the files the hook touched read them again', async () => {
    const { folder } = await openOrders({
      clones: [
        {
          folder: 'orders',
          files: { 'notes.txt': 'one\n', 'flows/orders.yaml': FLOW },
          edits: { 'notes.txt': 'two\n' },
          hook: { says: 'formatted the flow', writes: { 'notes.txt': 'two, formatted\n', 'flows/orders.yaml': FLOW.replace('in }', 'formatted }') } },
          meanwhile: 'terminal',
        },
      ],
    });
    await openFlow(false);
    await startCommit('Edit the notes');
    await commitButton().click();

    const result = page.getByRole('dialog', { name: 'Committed' });
    await expect.element(result.getByText(/^Committed [0-9a-f]{7} Edit the notes$/)).toBeVisible();
    await expect.element(result.getByText('A commit was made in a terminal in between')).toBeVisible();
    const terminal = folder.commits.find((c) => c.subject === 'terminal')!.id;
    await expect.element(result.getByText(`The new commit's parent is ${terminal.slice(0, 7)}`, { exact: false })).toBeVisible();
    await expect.element(result.getByText('The commit also changes flows/orders.yaml, which you did not choose.')).toBeVisible();
    await expect.element(result.getByText("The commit holds the hook's version of notes.txt, not the one you reviewed.")).toBeVisible();
    await expect.element(result.getByLabelText('What git and the hooks said')).toMatchTextContent(/^formatted the flow\s*\[main [0-9a-f]{7}\] Edit the notes$/);
    await result.getByRole('button', { name: 'Close' }).last().click();

    await page.getByRole('tab', { name: /^Flow / }).click();
    await expect.element(page.getByText('Read again 1 times')).toBeVisible();
  });

  test('Commit… is disabled with the reason while the commit route would refuse it', async () => {
    const cases: [Seed, string][] = [
      [{ readOnly: true, clones: [{ folder: 'orders', edits: { 'a.txt': 'a\n' } }] }, 'The server shows the working folder read-only (RAIL8_READ_ONLY is true).'],
      [{ identity: null, clones: [{ folder: 'orders', edits: { 'a.txt': 'a\n' } }] }, 'git gives no author identity here: set git config user.name and git config user.email in a terminal.'],
      [{ clones: [{ folder: 'orders', detached: true, edits: { 'a.txt': 'a\n' } }] }, 'HEAD is detached: switch to a branch in a terminal.'],
      [{ clones: [{ folder: 'orders', conflict: true }] }, 'The clone is busy (Merge in progress): finish it in a terminal.'],
      [{ clones: [{ folder: 'orders' }] }, 'Nothing to commit: no file differs from the last commit.'],
    ];
    for (const [seed, reason] of cases) {
      await openOrders(seed);
      await expect.element(button('Commit…')).toBeDisabled();
      await expect.element(button('Commit…')).toHaveAccessibleDescription(reason);
      root?.unmount();
      document.body.innerHTML = '';
    }
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
