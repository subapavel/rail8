import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import { Shell } from '../Shell';
import { fakeStateOf, type Seed } from './contract/seed';
import { fakePackagesApi, type FakeState } from './fake';
import { GIT_MISSING, REASONS } from './rules';
import { packagesScreen } from './screen';

/** The fake world's seeded commits are made from 09:01 on 1 November 2026; deploys at 10:00 on 20 November. */
const NOW = new Date('2026-11-23T10:00:00Z');

let root: Root | undefined;

function render(state: FakeState) {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={[packagesScreen(fakePackagesApi(state), () => NOW)]} start="packages" />);
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
    await expect.element(rowOf('billing').getByText('Other commit')).toBeVisible();
    await expect.element(rowOf('stock').getByText('Detached HEAD')).toBeVisible();
    await expect.element(rowOf('returns').getByText('Merge in progress')).toBeVisible();
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

  test('says when the node gives no answer, and why', async () => {
    open({ clones: [{ folder: 'orders' }], node: 'silent' });

    await expect.element(page.getByRole('alert').filter({ hasText: 'The node at 127.0.0.1:8081 does not answer' })).toBeVisible();
    await expect.element(rowOf('orders').getByText('Unknown')).toBeVisible();
  });

  test('says when git is missing, and lists the packages after Refresh once it is there', async () => {
    const state = open({ clones: [{ folder: 'orders' }], git: 'missing' });

    await expect.element(page.getByRole('alert').filter({ hasText: 'The packages could not be read' })).toMatchTextContent(GIT_MISSING);

    state.git = null;
    await page.getByRole('button', { name: 'Refresh' }).click();
    await expect.element(rowOf('orders')).toBeVisible();
  });

  test('without a working folder it says how to set one', async () => {
    open({ workspace: false });

    await expect.element(page.getByRole('heading', { level: 2, name: 'No working folder' })).toBeVisible();
  });

  test('says when the working folder holds no package', async () => {
    open({ plain: ['docs'] });

    await expect.element(page.getByRole('heading', { level: 2, name: 'No packages' })).toBeVisible();
    await expect.element(page.getByRole('table', { name: 'Folders that are no package' })).toBeVisible();
  });

  test('is marked read-only under RAIL8_READ_ONLY, and says when not every folder is listed', async () => {
    open({ readOnly: true, plain: Array.from({ length: 101 }, (_, n) => `f${String(n).padStart(3, '0')}`) });

    await expect.element(page.getByText('Read-only', { exact: true })).toBeVisible();
    await expect.element(page.getByRole('alert').filter({ hasText: 'Not every folder of the working folder is listed' })).toBeVisible();
  });
});
