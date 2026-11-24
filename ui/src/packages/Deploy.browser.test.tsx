import { StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import { Shell } from '../Shell';
import { PackagesError, type PackagesApi } from './api';
import { fakeStateOf, type CloneSeed, type Seed } from './contract/seed';
import { fakePackagesApi } from './fake';
import { packagesScreen } from './screen';

const NOW = new Date('2026-11-23T10:00:00Z');

/** Two commits whose flows differ: `first` runs a and b, `second` changes a, drops b and adds c. */
const FLOWS: CloneSeed = {
  folder: 'orders',
  commits: ['first', 'second'],
  commitFiles: {
    first: { 'flows/a.yaml': 'a, first\n', 'flows/b.yaml': 'b\n' },
    second: { 'flows/a.yaml': 'a, second\n', 'flows/b.yaml': null, 'flows/c.yaml': 'c\n' },
  },
};

let root: Root | undefined;

/**
 * The Packages screen over the fake world of `seed`, its api changed by `wrap`, under StrictMode when `strict`, and the
 * tab of `orders` opened from its row.
 */
async function openOrders(seed: Seed, { strict = false, wrap = (api: PackagesApi) => api } = {}) {
  const { state, ids } = fakeStateOf(seed);
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  const shell = <Shell screens={[packagesScreen(wrap(fakePackagesApi(state)), () => NOW)]} start="packages" />;
  root.render(strict ? <StrictMode>{shell}</StrictMode> : shell);
  await page.getByRole('table', { name: 'Packages' }).getByRole('button', { name: 'orders', exact: true }).click();
  await expect.element(page.getByRole('heading', { level: 1, name: 'orders' })).toBeVisible();
  return { state, id: (label: string) => ids.get(`orders/${label}`)! };
}

const button = (name: string) => page.getByRole('button', { name, exact: true });
const dialog = () => page.getByRole('dialog', { name: /^Deploy [0-9a-f]{7} to 127\.0\.0\.1:8081$/ });
const deployed = () => page.getByLabelText('orders', { exact: true });

afterEach(() => {
  root?.unmount();
  root = undefined;
  document.body.innerHTML = '';
});

describe('the deploy dialog', () => {
  test('Deploy… checks HEAD first, shows what it adds, swaps and removes, then deploys it and shows the node’s answer', async () => {
    const { state, id } = await openOrders({ clones: [FLOWS], node: [{ repository: 'orders', commit: 'first' }] });
    await expect.element(deployed().getByText('Behind 1')).toBeVisible();

    await button('Deploy…').click();

    await expect.element(dialog()).toBeVisible();
    await expect.element(dialog().getByText(`The check passed. It replaces ${id('first').slice(0, 7)}, which 127.0.0.1:8081 runs now.`)).toBeVisible();
    await expect.element(dialog().getByRole('row', { name: /^Adds/ })).toMatchTextContent('orders/c');
    await expect.element(dialog().getByRole('row', { name: /^Swaps/ })).toMatchTextContent('orders/a');
    await expect.element(dialog().getByRole('row', { name: /^Removes/ })).toMatchTextContent('orders/b');
    expect(state.sent).toEqual([{ check: true, body: { repository: 'orders', commit: id('second') }, actor: 'Ji?? Nov?k <jiri@example.com>' }]);

    await dialog().getByRole('button', { name: 'Deploy', exact: true }).click();

    await expect.element(dialog().getByText(`127.0.0.1:8081 runs ${id('second').slice(0, 7)}, in place of ${id('first').slice(0, 7)}.`)).toBeVisible();
    await expect.element(dialog().getByRole('row', { name: /^Added/ })).toMatchTextContent('orders/c');
    expect(state.sent[1]).toEqual({ check: false, body: { repository: 'orders', commit: id('second'), expect: id('first') }, actor: 'Ji?? Nov?k <jiri@example.com>' });
    await dialog().getByText('Close', { exact: true }).click();
    await expect.element(deployed().getByText('Behind 1')).not.toBeInTheDocument();
    await expect.element(deployed().getByText('Deployed', { exact: true }).last()).toBeVisible();
  });

  test('under StrictMode, as the development server serves the UI, opening the dialog sends one check, never two at once', async () => {
    const { state } = await openOrders({ clones: [FLOWS] }, { strict: true });
    await button('Deploy…').click();
    await expect.element(dialog().getByText('The check passed. 127.0.0.1:8081 runs nothing of this repository yet.')).toBeVisible();
    expect(state.sent.map((s) => s.check)).toEqual([true]);
  });

  test('a check that finds problems lists every one, and offers no deploy', async () => {
    const problems = ['flows/a.yaml: the step 1 names no type', 'endpoints/in.yaml: no such component'];
    const { state } = await openOrders({ clones: [FLOWS], deploys: { status: 422, error: 'the package has problems', problems } });

    await button('Deploy…').click();

    await expect.element(dialog().getByText('The check found problems: nothing was deployed')).toBeVisible();
    await expect.element(dialog().getByRole('list', { name: 'Problems' })).toMatchTextContent(problems.join(''));
    await expect.element(dialog().getByRole('button', { name: 'Deploy', exact: true })).not.toBeInTheDocument();
    expect(state.sent.map((s) => s.check)).toEqual([true]);
  });

  test('a deploy after the node’s commit changed since the check says so, and Check again checks against what the node runs now', async () => {
    const { state, id } = await openOrders({ clones: [FLOWS] });
    await button('Deploy…').click();
    await expect.element(dialog().getByText('The check passed. 127.0.0.1:8081 runs nothing of this repository yet.')).toBeVisible();
    if (state.node.answers) state.node.running.push({ name: 'orders', version: '1.0.0', repository: 'orders', commit: id('first'), state: 'RUNNING', problems: [], deployedAt: '2026-11-23T09:00:00Z' });

    await dialog().getByRole('button', { name: 'Deploy', exact: true }).click();

    await expect.element(dialog().getByText('The node’s commit changed since the check: nothing was deployed')).toBeVisible();
    await dialog().getByRole('button', { name: 'Check again' }).click();
    await expect.element(dialog().getByText(`The check passed. It replaces ${id('first').slice(0, 7)}, which 127.0.0.1:8081 runs now.`)).toBeVisible();
    await expect.element(dialog().getByText('changed since the check')).not.toBeInTheDocument();
  });

  test('a deploy the node does not answer in time has an unknown outcome: the dialog reads what the node runs before it offers to check again', async () => {
    await openOrders({ clones: [FLOWS], deploys: 'silent' });
    await button('Deploy…').click();

    await dialog().getByRole('button', { name: 'Deploy', exact: true }).click();

    await expect.element(dialog().getByText('The outcome of the deploy is unknown')).toBeVisible();
    await expect.element(dialog().getByText('127.0.0.1:8081 now reads: Not running on 127.0.0.1:8081.')).toBeVisible();
    await expect.element(dialog().getByRole('button', { name: 'Check again' })).toBeEnabled();
  });

  test('a deploy the server never answered has an unknown outcome too, and the dialog reads what the node runs', async () => {
    const lostOnTheWay = (api: PackagesApi): PackagesApi => ({
      ...api,
      deploy: (repository, body) => (body.check ? api.deploy(repository, body) : Promise.reject(new PackagesError(0, 'the server does not answer'))),
    });
    await openOrders({ clones: [FLOWS] }, { wrap: lostOnTheWay });
    await button('Deploy…').click();

    await dialog().getByRole('button', { name: 'Deploy', exact: true }).click();

    await expect.element(dialog().getByText('The outcome of the deploy is unknown')).toBeVisible();
    await expect.element(dialog().getByText('127.0.0.1:8081 now reads: Not running on 127.0.0.1:8081.')).toBeVisible();
    await expect.element(dialog().getByText('nothing was deployed')).not.toBeInTheDocument();
  });

  test('Deploy this commit… in History goes back to an earlier commit, and the package then reads Behind 1', async () => {
    const { id } = await openOrders({ clones: [FLOWS], node: [{ repository: 'orders', commit: 'second' }] });
    await page.getByRole('tab', { name: 'History' }).click();

    await page.getByRole('table', { name: 'History' }).getByRole('row').filter({ hasText: /^first/ }).getByRole('button', { name: 'Deploy this commit…' }).click();
    await expect.element(dialog().getByText(`The check passed. It replaces ${id('second').slice(0, 7)}, which 127.0.0.1:8081 runs now.`)).toBeVisible();
    await dialog().getByRole('button', { name: 'Deploy', exact: true }).click();
    await expect.element(dialog().getByRole('row', { name: /^Removed/ })).toMatchTextContent('orders/c');
    await dialog().getByText('Close', { exact: true }).click();

    await expect.element(deployed().getByText('Behind 1')).toBeVisible();
  });

  test('Commit and deploy commits, then opens the deploy dialog of the new commit', async () => {
    const { state } = await openOrders({ clones: [{ ...FLOWS, edits: { 'flows/c.yaml': 'c, edited\n' } }], node: [{ repository: 'orders', commit: 'second' }] });
    await button('Commit…').click();
    const commit = page.getByRole('dialog', { name: 'Commit to main' });
    await commit.getByLabelText('Message', { exact: true }).fill('Edit c');

    await commit.getByRole('button', { name: 'Commit and deploy' }).click();

    await expect.element(commit).not.toBeInTheDocument();
    await expect.element(dialog().getByText('Edit c')).toBeVisible();
    await expect.element(dialog().getByRole('row', { name: /^Swaps/ })).toMatchTextContent('orders/c');
    const head = state.folders.find((f) => f.name === 'orders')!.head.commit!;
    expect(state.sent).toEqual([{ check: true, body: { repository: 'orders', commit: head }, actor: expect.any(String) }]);
  });

  test('under RAIL8_READ_ONLY=true the check runs, and Deploy is disabled with the reason', async () => {
    await openOrders({ clones: [FLOWS], readOnly: true });
    await button('Deploy…').click();

    await expect.element(dialog().getByText('The check passed. 127.0.0.1:8081 runs nothing of this repository yet.')).toBeVisible();
    await expect.element(dialog().getByRole('button', { name: 'Deploy', exact: true })).toBeDisabled();
    await expect.element(dialog().getByText(/read-only \(RAIL8_READ_ONLY is true\)/)).toBeVisible();
  });

  test('Deploy… is disabled while the branch has no commit', async () => {
    await openOrders({ clones: [{ folder: 'orders', commits: [] }] });
    await expect.element(button('Deploy…')).toBeDisabled();
  });
});
