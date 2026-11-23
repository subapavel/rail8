import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import type { Artifact, Side } from '../../artifacts';
import { Rail8Theme } from '../../theme';
import type { FlowsApi } from './api';
import { FlowChange } from './FlowChange';

const COMMIT = 'a'.repeat(40);
const PATH = 'orders/flows/orders.yaml';

const flow = (steps: string) => `schemaVersion: 1
kind: flow
id: orders
version: 1.0.0
from:
  endpoint: orders-in
delivery:
  mode: sync
steps:
${steps}`;

const endpoint = (id: string, direction: 'in' | 'out') => `kind: endpoint\nid: ${id}\nscheme: http\ndirection: ${direction}\nconfig: {}\n`;

/** A version of the flow at `rev`, its package holding the endpoint files `files` by their path. */
function side(rev: string, text: string, files: Record<string, string> = {}): Side {
  const artifacts: Artifact[] = [{ path: PATH, kind: 'flow' }, ...Object.keys(files).map((path) => ({ path, kind: 'endpoint' as const }))];
  return { rev, text, artifacts: async () => artifacts, read: async (path) => files[path] ?? null };
}

/** The flow screen's server with no other flow on disk, and a node that serves no step types of its own. */
const api: FlowsApi = {
  list: async () => ({ configured: true, editable: true, items: [], truncated: false }),
  read: async () => {
    throw new Error('no flow is read from disk');
  },
  save: async () => {
    throw new Error('read-only');
  },
  steps: async () => [],
  ingress: async () => undefined,
};

const ENDPOINTS = { 'orders/endpoints/orders-in.yaml': endpoint('orders-in', 'in'), 'orders/endpoints/partner.yaml': endpoint('partner', 'out') };

let root: Root | undefined;

function draw(before: Side | null, after: Side | null) {
  const host = document.createElement('div');
  host.style.width = '1100px';
  document.body.append(host);
  root = createRoot(host);
  root.render(<Rail8Theme>{() => <FlowChange api={api} path={PATH} before={before} after={after} />}</Rail8Theme>);
}

const tile = (name: string) => page.getByRole('button', { name: `Inspect ${name}`, exact: true });
const lines = () => page.getByRole('table', { name: `Changes of ${PATH}` });

afterEach(() => {
  root?.unmount();
  root = undefined;
  document.body.innerHTML = '';
});

describe('the readable change of a flow', () => {
  test('draws the newer version with its added and changed steps marked, and lists the removed steps beside it', async () => {
    const before = flow('  - type: validate\n    name: check\n    required: /a\n  - type: jq\n    name: normalize\n  - type: xslt\n    name: shape\n  - type: to\n    name: send\n    endpoint: partner\n');
    const after = flow('  - type: validate\n    name: check\n    required: /b\n  - type: jq\n    name: normalize\n  - type: filter\n    name: only-big\n    when: /big\n  - type: to\n    name: send\n    endpoint: partner\n');
    draw(side(COMMIT, before, ENDPOINTS), side('work', after, ENDPOINTS));

    await expect.element(tile('only-big')).toHaveAttribute('data-change', 'added');
    await expect.element(tile('check')).toHaveAttribute('data-change', 'changed');
    await expect.element(tile('normalize')).not.toHaveAttribute('data-change');
    await expect.element(tile('shape')).not.toBeInTheDocument();
    await expect.element(page.getByRole('list', { name: 'Removed steps' })).toHaveTextContent('shape in steps, after normalize');

    await expect.element(lines().getByRole('row').filter({ hasText: 'check' })).toHaveTextContent('Changedcheck in stepsrequired: "/a" → "/b"');
    await expect.element(lines().getByRole('row').filter({ hasText: 'only-big' })).toHaveTextContent('Addedonly-big in steps');
    await expect.element(lines().getByRole('row').filter({ hasText: 'shape' })).toHaveTextContent('Removedshape in steps, after normalize');
    await expect.element(lines().getByRole('row').filter({ hasText: 'shape' }).getByRole('button')).not.toBeInTheDocument();
  });

  test('selects a step in the picture when its line in the list of changes is chosen', async () => {
    const before = flow('  - type: jq\n    name: normalize\n  - type: validate\n    name: check\n    required: /a\n');
    const after = flow('  - type: validate\n    name: check\n    required: /b\n  - type: jq\n    name: normalize\n  - type: filter\n    name: only-big\n    when: /big\n');
    draw(side(COMMIT, before, ENDPOINTS), side('work', after, ENDPOINTS));

    await expect.element(tile('only-big')).toHaveAttribute('aria-pressed', 'false');
    await lines().getByRole('button', { name: 'only-big in steps' }).click();
    await expect.element(tile('only-big')).toHaveAttribute('aria-pressed', 'true');
    await lines().getByRole('button', { name: 'check in steps' }).click();
    await expect.element(tile('check')).toHaveAttribute('aria-pressed', 'true');
    await expect.element(tile('check')).toHaveAttribute('data-change', 'changed');
    await expect.element(tile('only-big')).toHaveAttribute('aria-pressed', 'false');
  });

  test('draws an added flow all as added', async () => {
    draw(null, side('work', flow('  - type: jq\n    name: normalize\n  - type: to\n    name: send\n    endpoint: partner\n'), ENDPOINTS));

    await expect.element(page.getByText('Added: every step is new.')).toBeVisible();
    await expect.element(tile('normalize')).toHaveAttribute('data-change', 'added');
    await expect.element(tile('send')).toHaveAttribute('data-change', 'added');
  });

  test('draws a deleted flow as the older version, in the context of its own revision', async () => {
    const before = flow('  - type: to\n    name: send\n    endpoint: partner\n');
    draw(side(COMMIT, before, ENDPOINTS), null);

    await expect.element(page.getByText('Deleted: the older version is drawn.')).toBeVisible();
    await expect.element(tile('send')).not.toHaveAttribute('data-change');
    // the endpoint the step names is a file of the package at that commit, though not on disk
    expect(document.querySelector('.flow-station-marks .error')).toBeNull();
    await lines().getByRole('button', { name: 'send in steps, after nothing: it was first' }).click();
    await expect.element(tile('send')).toHaveAttribute('aria-pressed', 'true');
  });

  test('draws an endpoint renamed since the older side under the name its own revision gives it', async () => {
    const renamed = { 'orders/endpoints/orders-in.yaml': endpoint('orders-in', 'in'), 'orders/endpoints/partner-api.yaml': endpoint('partner-api', 'out') };
    const before = flow('  - type: to\n    name: send\n    endpoint: partner\n');
    draw(side(COMMIT, before, ENDPOINTS), side('work', before.replace('endpoint: partner', 'endpoint: partner-api'), renamed));

    await expect.element(page.getByRole('button', { name: 'Inspect partner-api', exact: true })).toBeVisible();
    expect(document.querySelector('.flow-station-marks .error')).toBeNull();
    await expect.element(lines().getByRole('row').filter({ hasText: 'send' })).toHaveTextContent('Changedsend in stepsendpoint: "partner" → "partner-api"');
  });

  test('marks a step whose endpoint is missing at its revision', async () => {
    const after = flow('  - type: to\n    name: send\n    endpoint: partner\n');
    draw(side(COMMIT, after.replace('partner', 'old'), ENDPOINTS), side('work', after, { 'orders/endpoints/orders-in.yaml': endpoint('orders-in', 'in') }));

    await expect.element(tile('send')).toBeVisible();
    await expect.poll(() => document.querySelector('.flow-station-marks .error')?.getAttribute('title')).toBe("'partner' names no endpoint of the package");
  });

  test('says when only comments or formatting changed', async () => {
    const text = flow('  - type: jq\n    name: normalize\n');
    draw(side(COMMIT, text), side('work', `# the orders flow\n${text.replace('mode: sync', 'mode: "sync"')}`));

    await expect.element(page.getByText('Only comments or formatting changed.')).toBeVisible();
  });

  test('shows the diagnostics of a version that cannot be drawn', async () => {
    draw(side(COMMIT, flow('  - type: jq\n')), side('work', 'kind: flow\nsteps: []\n'));

    await expect.element(page.getByText('The newer version cannot be drawn')).toBeVisible();
    await expect.element(page.getByText('a flow file names its id')).toBeVisible();
  });
});
