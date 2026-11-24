import { execFileSync } from 'node:child_process';
import { mkdir, mkdtemp, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { afterEach, describe, expect, test } from 'vitest';
import type { Running } from './node.ts';
import { listPackages } from './packages.ts';
import { runner, type Call, type Runner } from './runner.ts';
import { realTimer } from './timer.ts';

let temp: string | undefined;
let git: Runner | undefined;

afterEach(async () => {
  await git?.close();
  git = undefined;
  if (temp !== undefined) await rm(temp, { recursive: true, force: true });
  temp = undefined;
});

/**
 * A working folder of real clones, made with a git of its own home so the machine's git config never reaches it:
 * `orders` with the commits `first` and `second`; `stock` with `first`, its upstream one commit `next` further on;
 * `billing` with `first`, its upstream fetched and then its remote-tracking branch deleted. Beside them a hidden
 * folder and a plain file.
 */
async function workspace() {
  temp = await realpath(await mkdtemp(join(tmpdir(), 'rail8-list-')));
  const root = join(temp, 'work');
  const home = join(temp, 'home');
  await mkdir(home);
  const env = { PATH: process.env.PATH ?? '', HOME: home, GIT_CONFIG_NOSYSTEM: '1' };
  const run = (cwd: string, ...args: string[]) =>
    execFileSync('git', ['-c', 'user.name=Seed Author', '-c', 'user.email=seed@example.com', '-c', 'commit.gpgsign=false', ...args], { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] })
      .toString('utf8')
      .trim();
  const commit = async (clone: string, label: string) => {
    await writeFile(join(clone, `${label}.txt`), `${label}\n`);
    run(clone, 'add', '-A');
    run(clone, 'commit', '-q', '-m', label);
    return run(clone, 'rev-parse', 'HEAD');
  };
  const clone = async (name: string) => {
    const folder = join(root, name);
    await mkdir(folder, { recursive: true });
    run(folder, 'init', '-q', '-b', 'main');
    await writeFile(join(folder, 'package.yaml'), `name: ${name}\nversion: 1.0.0\n`);
    return folder;
  };
  const upstream = (folder: string, name: string) => {
    const bare = join(temp!, 'remotes', `${name}.git`);
    run(temp!, 'init', '-q', '--bare', '-b', 'main', bare);
    run(folder, 'remote', 'add', 'origin', pathToFileURL(bare).href);
    run(folder, 'push', '-q', '-u', 'origin', 'main');
  };

  const orders = await clone('orders');
  const ids = { first: await commit(orders, 'first'), second: await commit(orders, 'second'), stockFirst: '', next: '' };

  const stock = await clone('stock');
  ids.stockFirst = await commit(stock, 'first');
  upstream(stock, 'stock');
  ids.next = await commit(stock, 'next');
  run(stock, 'push', '-q', 'origin', 'main');
  run(stock, 'reset', '-q', '--hard', 'HEAD~1');

  const billing = await clone('billing');
  await commit(billing, 'first');
  upstream(billing, 'billing');
  run(billing, 'fetch', '-q', 'origin');
  run(billing, 'update-ref', '-d', 'refs/remotes/origin/main');

  await mkdir(join(root, '.cache'));
  await writeFile(join(root, 'notes.txt'), 'not a package\n');
  return { root, ids, env: { PATH: env.PATH, HOME: home } };
}

describe('the list of packages over real git', () => {
  test('runs exactly these reads: each path as :(literal), each revision after --end-of-options', async () => {
    const { root, ids, env } = await workspace();
    const real = runner({ env, timer: realTimer });
    git = real;
    const calls: Call[] = [];
    const recording: Runner = {
      run: (call) => {
        calls.push(call);
        return real.run(call);
      },
      undoable: (work, undo) => real.undoable(work, undo),
      close: () => real.close(),
    };
    const running: Running[] = [
      { name: 'orders', version: '1.0.0', repository: 'orders', commit: ids.first, state: 'RUNNING', problems: [], deployedAt: '2026-11-20T10:00:00Z', dependencies: [] },
      { name: 'stock', version: '1.0.0', repository: 'stock', commit: ids.next, state: 'RUNNING', problems: [], deployedAt: '2026-11-20T10:00:00Z', dependencies: [] },
    ];

    const listed = await listPackages(recording, root, { host: '127.0.0.1:8081', read: async () => ({ answered: true, running }), pool: async () => null });

    const argsOf = (name: string) => calls.filter((c) => c.clone === join(root, name)).map((c) => c.args);
    const status = ['status', '--porcelain=v2', '--branch', '-z', '--untracked-files=all', '--', ':(literal).'];
    const log = (id: string) => ['log', '-1', '-z', '--format=%H%x00%P%x00%an%x00%aI%x00%s', '--end-of-options', id, '--'];
    expect(argsOf('orders')).toEqual([
      ['rev-parse', '--show-toplevel'],
      status,
      log(ids.second),
      ['cat-file', '-e', '--end-of-options', `${ids.first}^{commit}`],
      ['merge-base', '--is-ancestor', '--end-of-options', ids.first, ids.second],
      ['rev-list', '--count', '--end-of-options', `${ids.first}..${ids.second}`, '--'],
    ]);
    expect(argsOf('stock')).toEqual([
      ['rev-parse', '--show-toplevel'],
      status,
      log(ids.stockFirst),
      ['cat-file', '-e', '--end-of-options', `${ids.next}^{commit}`],
      ['merge-base', '--is-ancestor', '--end-of-options', ids.next, ids.stockFirst],
      ['merge-base', '--is-ancestor', '--end-of-options', ids.stockFirst, ids.next],
      ['rev-list', '--count', '--end-of-options', `${ids.stockFirst}..${ids.next}`, '--'],
    ]);
    expect(calls.every((c) => c.mode === 'read' && c.stdin === undefined)).toBe(true);
    expect(calls).toHaveLength(argsOf('orders').length + argsOf('stock').length + argsOf('billing').length);

    expect(listed.others).toEqual([]);
    expect(listed.packages.map((p) => [p.repository, p.deployed.node === 'answered' ? p.deployed.relation : null])).toEqual([
      ['billing', 'none'],
      ['orders', 'behind'],
      ['stock', 'ahead'],
    ]);
  });

  test('a commit that the node and several nodes of its pool run is related to HEAD once', async () => {
    const { root, ids, env } = await workspace();
    const real = runner({ env, timer: realTimer });
    git = real;
    const calls: Call[] = [];
    const recording: Runner = { run: (call) => (calls.push(call), real.run(call)), undoable: (work, undo) => real.undoable(work, undo), close: () => real.close() };
    const runs = (commit: string) => ({ repository: 'orders', commit, state: 'RUNNING' as const, deployedAt: '2026-11-20T10:00:00Z', behind: [] });
    const running: Running[] = [{ name: 'orders', version: '1.0.0', repository: 'orders', commit: ids.first, state: 'RUNNING', problems: [], deployedAt: '2026-11-20T10:00:00Z', dependencies: [] }];

    const listed = await listPackages(recording, root, {
      host: '127.0.0.1:8081',
      read: async () => ({ answered: true, running }),
      pool: async () => ({
        records: [{ repository: 'orders', commit: ids.second }],
        nodes: [
          { node: 'node-a', packages: [runs(ids.first)] },
          { node: 'node-b', packages: [runs(ids.first)] },
          { node: 'node-c', packages: [runs(ids.second)] },
        ],
      }),
    });

    const relating = calls.filter((c) => c.clone === join(root, 'orders') && ['cat-file', 'merge-base', 'rev-list'].includes(c.args[0]!)).map((c) => c.args);
    expect(relating).toEqual([
      ['cat-file', '-e', '--end-of-options', `${ids.first}^{commit}`],
      ['merge-base', '--is-ancestor', '--end-of-options', ids.first, ids.second],
      ['rev-list', '--count', '--end-of-options', `${ids.first}..${ids.second}`, '--'],
    ]);
    const deployed = listed.packages.find((p) => p.repository === 'orders')!.deployed;
    expect(deployed.node === 'answered' && deployed.pool).toMatchObject({
      record: ids.second,
      nodes: [
        { node: 'node-a', relation: 'behind', commits: 1, follows: false },
        { node: 'node-b', relation: 'behind', commits: 1, follows: false },
        { node: 'node-c', relation: 'deployed', follows: true },
      ],
    });
  });

  test('a pin of the node is read only as a commit of 40 hex digits, after --end-of-options', async () => {
    const { root, ids, env } = await workspace();
    const real = runner({ env, timer: realTimer });
    git = real;
    const calls: Call[] = [];
    const recording: Runner = { run: (call) => (calls.push(call), real.run(call)), undoable: (work, undo) => real.undoable(work, undo), close: () => real.close() };
    const pin = (consumer: string, commit: string): Running => ({
      name: consumer,
      version: '1.0.0',
      repository: consumer,
      commit: ids.first,
      state: 'RUNNING',
      problems: [],
      deployedAt: '2026-11-20T10:00:00Z',
      dependencies: [{ name: 'orders', version: '1.0.0', repository: 'orders', commit, content: `sha256:${'0'.repeat(64)}` }],
    });
    const running = [pin('billing', '--output=/tmp/x'), pin('stock', ids.first)];

    const listed = await listPackages(recording, root, { host: '127.0.0.1:8081', read: async () => ({ answered: true, running }), pool: async () => null });

    const orders = calls.filter((c) => c.clone === join(root, 'orders')).map((c) => c.args);
    expect(orders.flat()).not.toContain('--output=/tmp/x');
    expect(orders.slice(3)).toEqual([
      ['cat-file', '-e', '--end-of-options', `${ids.first}^{commit}`],
      ['log', '-1', '-z', '--format=%H%x00%P%x00%an%x00%aI%x00%s', '--end-of-options', ids.first, '--'],
      ['merge-base', '--is-ancestor', '--end-of-options', ids.first, ids.second],
      ['rev-list', '--count', '--end-of-options', `${ids.first}..${ids.second}`, '--'],
    ]);
    expect(listed.packages.find((p) => p.repository === 'orders')).not.toHaveProperty('pinnedBehind');
  });

  test('a hidden folder and a plain file are passed over, and an upstream whose remote-tracking branch is gone reads as none', async () => {
    const { root, env } = await workspace();
    git = runner({ env, timer: realTimer });

    const listed = await listPackages(git, root, { host: '127.0.0.1:8081', read: async () => ({ answered: true, running: [] }), pool: async () => null });

    expect(listed.packages.map((p) => p.repository)).toEqual(['billing', 'orders', 'stock']);
    expect(listed.others).toEqual([]);
    expect(listed.packages.find((p) => p.repository === 'billing')).toMatchObject({ branch: 'main', upstream: null });
    expect(listed.packages.find((p) => p.repository === 'stock')?.upstream).toMatchObject({ name: 'origin/main', ahead: 0, behind: 1 });
  });
});
