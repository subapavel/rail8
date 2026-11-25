import { lstat, mkdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { etagOf, replaceFile, walk, writeNew, type WorkingFolderLock } from '../../../server/workingFolder.ts';
import type { FolderBody } from '../api.ts';
import { FOLDER_REFUSALS, folderBodyOf as folderBodyRules, folderFieldsOf, folderText, namespaceTaken } from '../rules.ts';
import { refusal } from './refused.ts';

/** `body` as a write of a folder's `folder.yaml` in the package `repository` takes it, refused as {@link folderBodyRules} refuses it. */
export function folderBodyOf(repository: string, body: unknown): FolderBody & { inside: string } {
  const found = folderBodyRules(repository, body);
  if ('error' in found) throw refusal(found.status, found);
  return found;
}

/** The work of a lock the caller holds already: it runs the work as it is. */
const held: WorkingFolderLock = (work) => work();

/**
 * Writes the `folder.yaml` of the folder at `inside`, a path within the package `repository` whose clone is at the
 * real path `clone`, as `body` states it, holding the working folder's `lock` from its checks to its end. It makes each
 * missing folder on the way, and refuses, writing nothing: with 409 when an entry on the way is a file or a link; with
 * 412 when `body.tag` is null and the folder has a `folder.yaml`, or when its tag is not that of the `folder.yaml` on
 * disk (or there is none); and with 422 when another folder of the package has the namespace. A new file holds the
 * schema's version, its kind and the two fields; an existing one keeps its comments and other keys.
 */
export async function writeFolder(lock: WorkingFolderLock, clone: string, repository: string, body: FolderBody & { inside: string }): Promise<void> {
  const names = body.inside.split('/');
  await lock(async () => {
    if (body.namespace !== null) {
      const { files } = await walk(clone, (name) => name === 'folder.yaml');
      const folders = files.map((f) => ({ inside: f.path.slice(0, -'/folder.yaml'.length), ...folderFieldsOf(f.text) }));
      const taken = namespaceTaken(body.namespace, body.inside, folders);
      if (taken !== null) throw refusal(422, taken);
    }
    let folder = clone;
    for (const [n, name] of names.entries()) {
      folder = join(folder, name);
      const entry = await lstat(folder).catch(() => null);
      if (entry === null) await mkdir(folder);
      else if (!entry.isDirectory()) throw refusal(409, FOLDER_REFUSALS.inTheWay({ path: `${repository}/${names.slice(0, n + 1).join('/')}` }));
    }
    const file = join(folder, 'folder.yaml');
    const entry = await lstat(file).catch(() => null);
    if (body.tag === null) {
      if (!(await writeNew(held, file, folderText(null, body.namespace, body.description)))) throw refusal(412, FOLDER_REFUSALS.exists);
      return;
    }
    if (entry === null || !entry.isFile()) throw refusal(412, FOLDER_REFUSALS.stale);
    const before = await readFile(file);
    if (etagOf(before) !== body.tag) throw refusal(412, FOLDER_REFUSALS.stale);
    await replaceFile(held, file, folderText(before.toString('utf8'), body.namespace, body.description));
  });
}
