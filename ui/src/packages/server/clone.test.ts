import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { cloneCall, CLONE_LIMITS, isOver, watchSize, WATCH_MS } from './clone.ts';
import { FakeTimer } from './fakeTimer.ts';

describe('the clone of Get a package', () => {
  let dir: string | undefined;
  afterEach(async () => {
    if (dir !== undefined) await rm(dir, { recursive: true, force: true });
    dir = undefined;
  });

  test('runs git in the hidden folder with every -c before clone, no hook, askpass, bundle URI, LFS or submodule, and the address after --', () => {
    const stop = new AbortController();

    expect(cloneCall('/work/.rail8-clone-x1', 'git@github.com:acme/orders.git', 'orders', stop.signal)).toEqual({
      args: [
        '-c',
        'core.hooksPath=/dev/null',
        '-c',
        'core.askPass=',
        '-c',
        'transfer.bundleURI=false',
        '-c',
        'filter.lfs.process=',
        '-c',
        'filter.lfs.smudge=',
        '-c',
        'filter.lfs.required=false',
        '-c',
        'core.protectHFS=true',
        '-c',
        'core.protectNTFS=true',
        'clone',
        '--quiet',
        '--no-recurse-submodules',
        '--',
        'git@github.com:acme/orders.git',
        'orders',
      ],
      clone: '/work/.rail8-clone-x1',
      mode: 'clone',
      limits: { ms: 300_000, bytes: 1024 * 1024 },
      signal: stop.signal,
    });
    expect(CLONE_LIMITS).toEqual({ ms: 300_000, bytes: 1024 * 1024 });
  });

  test('counts the files and folders under a folder, hidden ones too, and the bytes of its files', async () => {
    dir = await mkdtemp(join(tmpdir(), 'rail8-size-'));
    await mkdir(join(dir, '.git', 'objects'), { recursive: true });
    await writeFile(join(dir, '.git', 'objects', 'pack'), 'x'.repeat(10));
    await writeFile(join(dir, 'a.txt'), 'y'.repeat(5));

    expect(await isOver(dir, 15, 4)).toBe(false);
    expect(await isOver(dir, 14, 4)).toBe(true);
    expect(await isOver(dir, 15, 3)).toBe(true);
  });

  test('looks at the size once a second on the timer, and says once it is over', async () => {
    dir = await mkdtemp(join(tmpdir(), 'rail8-size-'));
    const timer = new FakeTimer();
    let over = 0;
    let said = () => {};
    const saidOver = new Promise<void>((done) => (said = done));
    const watch = watchSize(
      dir,
      timer,
      () => {
        over += 1;
        said();
      },
      100,
      3,
    );

    await writeFile(join(dir, 'a'), '');
    timer.advance(WATCH_MS - 1);
    expect(timer.pending).toBe(1);
    timer.advance(1);
    await timer.waitFor(1);
    expect(over).toBe(0);
    await writeFile(join(dir, 'b'), '');
    await writeFile(join(dir, 'c'), '');
    await writeFile(join(dir, 'd'), '');
    timer.advance(WATCH_MS);
    await saidOver;
    await watch.stop();

    expect(over).toBe(1);
    expect(timer.pending).toBe(0);
  });

  test('stops looking when it is stopped', async () => {
    dir = await mkdtemp(join(tmpdir(), 'rail8-size-'));
    const timer = new FakeTimer();
    const watch = watchSize(dir, timer, () => expect.unreachable('it looked after its stop'), 0, 0);
    await writeFile(join(dir, 'a'), 'x');

    await watch.stop();
    timer.advance(WATCH_MS * 3);

    expect(timer.pending).toBe(0);
  });
});
