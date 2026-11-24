import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { etagOf } from '../../../server/workingFolder.ts';
import type { DiscardBody } from '../api.ts';
import { discardPaths } from './discard.ts';
import { runner, type Call, type Ran, type Runner } from './runner.ts';
import { realTimer } from './timer.ts';

let temp: string | undefined;
let real: Runner | undefined;
afterEach(async () => {
  await real?.close();
  if (temp !== undefined) await rm(temp, { recursive: true, force: true });
  temp = undefined;
  real = undefined;
});

const git = (clone: string, ...args: string[]) =>
  execFileSync('git', ['-c', 'user.name=Seed', '-c', 'user.email=seed@example.com', '-c', 'commit.gpgsign=false', ...args], { cwd: clone, stdio: ['ignore', 'pipe', 'pipe'] })
    .toString('utf8')
    .trim();
const said = (stderr: string): Ran => ({ code: 128, stdout: Buffer.alloc(0), stderr: Buffer.from(stderr) });

/**
 * A clone `orders` with `a.txt` edited on disk, `s.txt` staged with other content, and the untracked `draft.txt`, and a
 * runner over real git whose calls `script` answers instead when it gives an answer.
 */
async function cloneWith(script: (call: Call, clone: string) => Promise<Ran | undefined>) {
  temp = await realpath(await mkdtemp(join(tmpdir(), 'rail8-discard-')));
  const clone = join(temp, 'orders');
  execFileSync('git', ['init', '-q', '-b', 'main', clone]);
  for (const name of ['package.yaml', 'a.txt', 's.txt']) await writeFile(join(clone, name), `${name}\n`);
  git(clone, 'add', '-A');
  git(clone, 'commit', '-q', '-m', 'first');
  await writeFile(join(clone, 'a.txt'), 'a, edited\n');
  await writeFile(join(clone, 's.txt'), 's, staged\n');
  git(clone, 'add', '--', 's.txt');
  await writeFile(join(clone, 'draft.txt'), 'a draft\n');
  const runs = runner({ env: { PATH: process.env.PATH, HOME: temp }, timer: realTimer });
  real = runs;
  const scripted: Runner = { ...runs, run: async (call) => (await script(call, clone)) ?? runs.run(call) };
  const tag = async (name: string) => etagOf(await readFile(join(clone, name)));
  const body: DiscardBody = {
    head: git(clone, 'rev-parse', 'HEAD'),
    paths: [
      { path: 'orders/a.txt', tag: await tag('a.txt'), index: 'clean' },
      { path: 'orders/draft.txt', tag: await tag('draft.txt'), index: 'clean' },
      { path: 'orders/s.txt', tag: await tag('s.txt'), index: 'staged' },
    ],
  };
  const status = () => git(clone, 'status', '--porcelain', '--untracked-files=all');
  return { clone, discard: () => discardPaths(scripted, (work) => work(), clone, 'orders', body), status };
}

describe('a discard whose git fails', () => {
  test('update-index failing while another git holds the index is 409, and nothing changed: the untracked draft is still there', async () => {
    const { clone, discard, status } = await cloneWith(async (call, at) => {
      if (call.args[0] !== 'update-index') return undefined;
      await writeFile(join(at, '.git', 'index.lock'), '');
      return said("fatal: Unable to create '.git/index.lock': File exists.");
    });
    const before = status();

    await expect(discard()).rejects.toMatchObject({ status: 409, output: "fatal: Unable to create '.git/index.lock': File exists." });
    await rm(join(clone, '.git', 'index.lock'));
    expect(status()).toBe(before);
    expect(await readFile(join(clone, 'draft.txt'), 'utf8')).toBe('a draft\n');
  });

  test('update-index failing with no other git holding the index is 500, whatever git says, and nothing changed', async () => {
    const { discard, status } = await cloneWith(async (call) => (call.args[0] === 'update-index' ? said("fatal: Unable to create '.git/index.lock': File exists.") : undefined));
    const before = status();

    await expect(discard()).rejects.toMatchObject({ status: 500, message: 'git update-index failed, as git says; nothing was discarded' });
    expect(status()).toBe(before);
  });

  test('checkout-index failing runs no unlink: every chosen path it did not write is left, the draft too', async () => {
    const { clone, discard } = await cloneWith(async (call) => (call.args[0] === 'checkout-index' ? said('error: unable to write file a.txt') : undefined));

    const done = await discard();

    // the index already holds the last commit's s.txt, so only its bytes on disk still differ
    expect(done).toMatchObject({ discarded: [], left: ['orders/a.txt', 'orders/draft.txt', 'orders/s.txt'] });
    expect(done.problem).toContain('error: unable to write file a.txt');
    expect(await readFile(join(clone, 'draft.txt'), 'utf8')).toBe('a draft\n');
    expect(await readFile(join(clone, 'a.txt'), 'utf8')).toBe('a, edited\n');
  });

  test('a read-back that fails after the writes is 500, and never says that nothing was discarded', async () => {
    const { clone, discard } = await cloneWith(async (call) => (call.args[0] === 'status' && !call.args.includes('--branch') ? said('fatal: index file corrupt') : undefined));

    const refused = discard();

    await expect(refused).rejects.toMatchObject({ status: 500 });
    await expect(refused).rejects.toThrow(/^the discard ran, but what it changed could not be read: fatal: index file corrupt; the package's Changes show it$/);
    await expect(readFile(join(clone, 'draft.txt'))).rejects.toMatchObject({ code: 'ENOENT' });
  });
});
