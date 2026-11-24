import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { etagOf } from '../../../server/workingFolder.ts';
import { LOCK_REFUSALS } from '../rules.ts';
import { lockPackage } from './lock.ts';
import type { Ran, Runner } from './runner.ts';

let temp: string | undefined;
afterEach(async () => {
  if (temp !== undefined) await rm(temp, { recursive: true, force: true });
  temp = undefined;
});

/** A lock of a package that declares no dependency, whose rail8 lock ends as `ran` says. */
async function lockEndingWith(ran: Ran) {
  temp = await mkdtemp(join(tmpdir(), 'rail8-lock-'));
  const clone = join(temp, 'orders');
  await mkdir(clone);
  const manifest = 'name: orders\nversion: 1.0.0\n';
  await writeFile(join(clone, 'package.yaml'), manifest);
  const git = { run: async () => ran } as unknown as Runner;
  return lockPackage(git, (work) => work(), '/rail8/bin/rail8', clone, 'orders', { manifestTag: etagOf(Buffer.from(manifest)), lockTag: null, dependencies: [] });
}

describe('a lock', () => {
  test('says how to build the command line when bin/rail8 finds no jar, and not when the command line refuses its arguments', async () => {
    await expect(lockEndingWith({ code: 2, stdout: Buffer.alloc(0), stderr: Buffer.from('rail8: no cli jar in /rail8/platform/cli/target; build it first\n') })).rejects.toMatchObject({
      status: 503,
      message: LOCK_REFUSALS.missing('/rail8/bin/rail8'),
    });
    await expect(lockEndingWith({ code: 2, stdout: Buffer.alloc(0), stderr: Buffer.from('usage: rail8 lock <dir>\n') })).rejects.toMatchObject({
      status: 500,
      message: LOCK_REFUSALS.failed(2),
      output: 'usage: rail8 lock <dir>',
    });
  });
});
