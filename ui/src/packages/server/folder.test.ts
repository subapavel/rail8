import { mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, test } from 'vitest';
import type { WorkingFolderLock } from '../../../server/workingFolder.ts';
import { FOLDER_REFUSALS } from '../rules.ts';
import { writeFolder } from './folder.ts';

let clone: string | undefined;
afterEach(async () => {
  if (clone !== undefined) await rm(clone, { recursive: true, force: true });
  clone = undefined;
});

const lock: WorkingFolderLock = (work) => work();
const body = (inside: string, tag: string | null) => ({ path: `orders/${inside}`, inside, tag, namespace: 'urn:acme:new', description: null });

// A refused write leaves the clone as it was: the folders on the way that are missing are made only once every check has passed.
test('a refused write of a folder.yaml makes none of the missing folders on its way', async () => {
  clone = await mkdtemp(join(tmpdir(), 'rail8-folder-'));
  await writeFile(join(clone, 'notes'), 'a file, no folder\n');

  await expect(writeFolder(lock, clone, 'orders', body('new/deeper', '"read-before"'))).rejects.toMatchObject({ status: 412, code: FOLDER_REFUSALS.stale.code });
  await expect(writeFolder(lock, clone, 'orders', body('notes/inner', null))).rejects.toMatchObject({ status: 409, code: FOLDER_REFUSALS.inTheWay.code });
  expect(await readdir(clone)).toEqual(['notes']);

  await writeFolder(lock, clone, 'orders', body('new/deeper', null));
  expect(await readdir(join(clone, 'new', 'deeper'))).toEqual(['folder.yaml']);
});
