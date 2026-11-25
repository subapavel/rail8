import { mkdir, mkdtemp, open, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { CLONE_MAX_BYTES, CLONE_REFUSALS } from '../rules.ts';
import { cloneCall, cloneInto, CLONE_LIMITS, isOver, watchSize, WATCH_MS } from './clone.ts';
import { FakeTimer } from './fakeTimer.ts';
import type { Call, Ran, Runner } from './runner.ts';

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

  test('stops git while it runs once the hidden folder grows past the limit, and leaves nothing', async () => {
    dir = await mkdtemp(join(tmpdir(), 'rail8-clone-watch-'));
    const timer = new FakeTimer();
    let asked: Call | undefined;
    let started = () => {};
    const running = new Promise<void>((done) => (started = done));
    // a git that writes a pack over the limit, then runs until its call is stopped
    const git: Runner = {
      run: async (call) => {
        asked = call;
        const pack = join(call.clone!, 'orders', '.git', 'objects', 'pack');
        await mkdir(pack, { recursive: true });
        const file = await open(join(pack, 'incoming.pack'), 'w');
        await file.truncate(CLONE_MAX_BYTES + 1);
        await file.close();
        started();
        return new Promise<Ran>((_, fail) => call.signal!.addEventListener('abort', () => fail(call.signal!.reason as Error)));
      },
      undoable: async (work, undo) => {
        try {
          return await work();
        } catch (failure) {
          await undo(() => Promise.reject(new Error('no call expected')), failure);
          throw failure;
        }
      },
      close: () => Promise.resolve(),
    };

    const cloned = cloneInto(git, timer, (work) => work(), dir, 'https://github.com/acme/orders.git', 'orders');
    await running;
    timer.advance(WATCH_MS);

    await expect(cloned).rejects.toMatchObject({ status: 422, message: CLONE_REFUSALS.tooLarge.error });
    expect(asked?.signal?.aborted).toBe(true);
    expect(await readdir(dir)).toEqual([]);
  });
});
