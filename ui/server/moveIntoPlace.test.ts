import { lstat, mkdir, mkdtemp, readdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test, vi } from 'vitest';

// what happens between the claim and the rename, as another program does it
const between = vi.hoisted(() => ({ run: undefined as undefined | (() => Promise<void>) }));

vi.mock('node:fs/promises', async (original) => {
  const real = await original<typeof import('node:fs/promises')>();
  let claimed = false;
  return {
    ...real,
    mkdir: async (...args: Parameters<typeof real.mkdir>) => {
      const made = await real.mkdir(...args);
      claimed = true;
      return made;
    },
    lstat: async (...args: Parameters<typeof real.lstat>) => {
      // the lstat after the one that read the claim: the check just before the rename
      if (claimed && between.run !== undefined && args[0] !== undefined) {
        const run = between.run;
        between.run = undefined;
        await run();
      }
      return real.lstat(...(args as [string]));
    },
  };
});

const { moveIntoPlace } = await import('./workingFolder.ts');

describe('moveIntoPlace', () => {
  let dir: string | undefined;
  afterEach(async () => {
    between.run = undefined;
    if (dir !== undefined) await rm(dir, { recursive: true, force: true });
    dir = undefined;
  });

  const folders = async () => {
    dir = await mkdtemp(join(tmpdir(), 'rail8-move-'));
    await mkdir(join(dir, '.hidden', 'orders'), { recursive: true });
    await writeFile(join(dir, '.hidden', 'orders', 'package.yaml'), 'name: orders\n');
    return { from: join(dir, '.hidden', 'orders'), to: join(dir, 'orders') };
  };

  test('puts the folder in place by one rename', async () => {
    const { from, to } = await folders();

    expect(await moveIntoPlace(from, to)).toBe(true);
    expect(await readFile(join(to, 'package.yaml'), 'utf8')).toBe('name: orders\n');
    expect(await readdir(join(dir!, '.hidden'))).toEqual([]);
  });

  test.each([
    ['an empty folder', (to: string) => mkdir(to)],
    ['a file', (to: string) => writeFile(to, 'mine\n')],
  ])('moves nothing over %s that is there already', async (_, make) => {
    const { from, to } = await folders();
    await make(to);
    const before = await lstat(to);

    expect(await moveIntoPlace(from, to)).toBe(false);
    const after = await lstat(to);
    expect([after.dev, after.ino]).toEqual([before.dev, before.ino]);
    expect(await readdir(join(dir!, '.hidden'))).toEqual(['orders']);
  });

  test('moves nothing when its claim was replaced by another empty folder before the rename, and keeps that folder', async () => {
    const { from, to } = await folders();
    let other: { dev: number; ino: number } | undefined;
    // one lstat reads the claim; the next checks it, and the claim is replaced just before
    let reads = 0;
    between.run = async () => {
      reads += 1;
      if (reads === 1) {
        between.run = async () => {
          // the claim moves away, so that the new folder cannot reuse its inode
          await rename(to, `${to}.claim`);
          await mkdir(to);
          const made = await lstat(to);
          other = { dev: made.dev, ino: made.ino };
        };
      }
    };

    expect(await moveIntoPlace(from, to)).toBe(false);
    const after = await lstat(to);
    expect({ dev: after.dev, ino: after.ino }).toEqual(other);
    expect(await readdir(to)).toEqual([]);
    expect(await readdir(join(dir!, '.hidden'))).toEqual(['orders']);
  });
});
