import { lstat, mkdir, mkdtemp, rm, rmdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { moveIntoPlace, type WorkingFolderLock } from '../../../server/workingFolder.ts';
import { NEW_PACKAGE_REFUSALS, newPackageFiles, newPackageMessage } from '../rules.ts';
import { COMMIT_LIMITS, CommitRefused } from './commit.ts';
import { formatted } from './formatter.ts';
import { identityOf } from './package.ts';
import { refusal } from './refused.ts';
import type { Ran, Runner } from './runner.ts';

/** The name of a hidden folder a new package is made in, before the six random characters mkdtemp adds. */
export const NEW_PREFIX = '.rail8-new-';

/** What git and the hooks said, both streams. */
const saidBy = (ran: Ran) => formatted([ran.stdout.toString('utf8'), ran.stderr.toString('utf8')].filter((t) => t.trim() !== '').join('\n'));

/** What New package did: the new package's folder, and why something after the move failed. */
export interface Made {
  clone: string;
  problem?: string;
}

/**
 * Makes the new package `name` as the new folder `repository` of the real working folder `root`, under the working
 * folder's lock, which it holds throughout:
 *
 * 1. Refused with 409, with nothing written, when anything is at `<root>/<repository>`.
 * 2. As one undoable of the runner (503 when it closes, with nothing made): in a hidden folder `.rail8-new-*` of the
 *    working folder, the folder `repository` gets the files {@link newPackageFiles} names; then `git init -b main`,
 *    `git add -A` and `git commit -F -` with the message "Create package <name>" on stdin, with the author's own
 *    identity, hooks and signing. Without an author identity it is refused with 422 before the commit; when git fails,
 *    with 422 and what git and the hooks said.
 * 3. {@link moveIntoPlace} puts the folder in place, 409 when an entry of that name appeared meanwhile, and the emptied
 *    hidden folder is removed.
 *
 * A failure up to the move removes the hidden folder whole, and nothing else, before it rejects. The move is the
 * commit point: a failure after it is no refusal, and `problem` says what it was.
 */
export function createPackage(git: Runner, lock: WorkingFolderLock, root: string, repository: string, name: string): Promise<Made> {
  return lock(async () => {
    const target = join(root, repository);
    const free = await lstat(target).then(
      () => false,
      (error: unknown) => ((error as NodeJS.ErrnoException).code === 'ENOENT' ? true : Promise.reject(error)),
    );
    if (!free) throw refusal(409, NEW_PACKAGE_REFUSALS.exists);

    let temp: string | undefined;
    return git.undoable(
      async () => {
        temp = await mkdtemp(join(root, NEW_PREFIX));
        const made = join(temp, repository);
        await mkdir(made);
        for (const [path, text] of Object.entries(newPackageFiles(name))) await writeFile(join(made, path), text, { flag: 'wx' });
        const write = async (args: string[], stdin?: string) => {
          const ran = await git.run({ args, clone: made, mode: 'write', limits: COMMIT_LIMITS, ...(stdin === undefined ? {} : { stdin }) });
          if (ran.code !== 0) throw new CommitRefused(422, NEW_PACKAGE_REFUSALS.gitFailed, saidBy(ran));
        };
        await write(['init', '--quiet', '-b', 'main']);
        if ((await identityOf(git, made)) === null) throw refusal(422, NEW_PACKAGE_REFUSALS.noIdentity);
        await write(['add', '-A']);
        await write(['commit', '--quiet', '-F', '-'], newPackageMessage(name));
        if (!(await moveIntoPlace(made, target))) throw refusal(409, NEW_PACKAGE_REFUSALS.exists);
        try {
          await rmdir(temp);
          return { clone: target };
        } catch (error) {
          return { clone: target, problem: `the hidden folder ${temp} the package was made in could not be removed: ${error instanceof Error ? error.message : String(error)}; delete it` };
        }
      },
      async () => {
        if (temp === undefined) return;
        try {
          await rm(temp, { recursive: true, force: true, maxRetries: 3 });
        } catch (error) {
          throw refusal(500, NEW_PACKAGE_REFUSALS.cleanupFailed(error instanceof Error ? error.message : String(error), { folder: temp }));
        }
      },
    );
  });
}
