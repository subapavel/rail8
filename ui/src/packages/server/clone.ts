import { lstat, mkdtemp, readdir, rm, rmdir } from 'node:fs/promises';
import { join } from 'node:path';
import { moveIntoPlace, Refusal, type WorkingFolderLock } from '../../../server/workingFolder.ts';
import { CLONE_MAX_BYTES, CLONE_MAX_ENTRIES, CLONE_REFUSALS } from '../rules.ts';
import { formatted } from './formatter.ts';
import type { Call, Limits, Runner } from './runner.ts';
import type { Timer } from './timer.ts';

/** A clone of Get a package: 300 s from git's start, and 1 MiB on each stream. */
export const CLONE_LIMITS: Limits = { ms: 300_000, bytes: 1024 * 1024 };

/** How often the size of a clone under way is looked at. */
export const WATCH_MS = 1_000;

/** The name of a hidden folder a clone is made in, before a number of mkdtemp's own. */
export const HIDDEN_PREFIX = '.rail8-clone-';

/** A refused clone: its status, its error, and what git said. */
export class CloneRefused extends Refusal {
  readonly output: string;

  constructor(status: number, message: string, output: string) {
    super(status, message);
    this.output = output;
  }
}

/**
 * The git call that clones `address` into the folder `repository` of the hidden folder `temp`: no hook, no askpass, no
 * bundle from a URI, no git-lfs, no submodule, and no tree path that aliases `.git` on HFS+ or NTFS. Every `-c` comes
 * before `clone`, so none is written into the new clone's config, and the address comes after `--`.
 */
export function cloneCall(temp: string, address: string, repository: string, signal?: AbortSignal): Call {
  return {
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
      address,
      repository,
    ],
    clone: temp,
    mode: 'clone',
    limits: CLONE_LIMITS,
    ...(signal === undefined ? {} : { signal }),
  };
}

/**
 * Whether the files and folders under `folder` hold more than `maxBytes` bytes of files or more than `maxEntries`
 * entries: by `lstat` only, following no link, hidden ones counted, an entry gone meanwhile passed over. It stops as soon
 * as it is over.
 */
export async function isOver(folder: string, maxBytes: number, maxEntries: number): Promise<boolean> {
  let bytes = 0;
  let entries = 0;
  const gone = (error: unknown) => {
    const code = (error as NodeJS.ErrnoException).code;
    if (code !== 'ENOENT' && code !== 'ENOTDIR') throw error;
    return null;
  };
  const visit = async (dir: string): Promise<boolean> => {
    const names = await readdir(dir).catch(gone);
    for (const name of names ?? []) {
      entries += 1;
      if (entries > maxEntries) return true;
      const full = join(dir, name);
      const info = await lstat(full).catch(gone);
      if (info === null) continue;
      if (info.isFile()) {
        bytes += info.size;
        if (bytes > maxBytes) return true;
      } else if (info.isDirectory() && (await visit(full))) return true;
    }
    return false;
  };
  return visit(folder);
}

/**
 * A watch of the size of `folder` while git writes it: every {@link WATCH_MS} on `timer`, one look at a time, and
 * `over` once it is over the limits. `stop` ends it, and resolves once a look under way has ended.
 */
export function watchSize(folder: string, timer: Timer, over: () => void, maxBytes = CLONE_MAX_BYTES, maxEntries = CLONE_MAX_ENTRIES): { stop: () => Promise<void> } {
  let stopped = false;
  let looking: Promise<void> = Promise.resolve();
  let cancel = () => {};
  const next = () => {
    cancel = timer.after(WATCH_MS, () => {
      looking = isOver(folder, maxBytes, maxEntries).then(
        (isIt) => {
          if (stopped) return;
          if (isIt) over();
          else next();
        },
        () => {
          if (!stopped) next();
        },
      );
    });
  };
  next();
  return {
    stop: () => {
      stopped = true;
      cancel();
      return looking;
    },
  };
}

/** What a clone into place did: the new clone's folder, and why something after the move failed. */
export interface Cloned {
  clone: string;
  problem?: string;
}

/**
 * Clones `address` into the new folder `repository` of the real working folder `root`, as Get a package does:
 *
 * 1. Refused with 409, with nothing written, when anything is at `<root>/<repository>`.
 * 2. As one undoable of the runner (503 when it closes, with nothing made): a hidden folder `.rail8-clone-*` of the
 *    working folder, mode 0700, gets the clone, which git makes as {@link cloneCall} says, while its size is watched.
 *    git's error is 422 with what it said; over the size limits, during the clone or once git has ended, 422.
 * 3. Under the working folder's lock, {@link moveIntoPlace} puts it in place, 409 when an entry of that name appeared
 *    meanwhile, and the emptied hidden folder is removed.
 *
 * A failure up to the move removes the hidden folder whole, before it rejects. The move is the commit point: a failure
 * after it is no refusal, and `problem` says what it was.
 */
export async function cloneInto(git: Runner, timer: Timer, lock: WorkingFolderLock, root: string, address: string, repository: string): Promise<Cloned> {
  const target = join(root, repository);
  const free = await lstat(target).then(
    () => false,
    (error: unknown) => ((error as NodeJS.ErrnoException).code === 'ENOENT' ? true : Promise.reject(error)),
  );
  if (!free) throw new Refusal(409, CLONE_REFUSALS.exists);

  let temp: string | undefined;
  return git.undoable(
    async () => {
      temp = await mkdtemp(join(root, HIDDEN_PREFIX));
      const tooLarge = () => new Refusal(422, CLONE_REFUSALS.tooLarge);
      const stop = new AbortController();
      const watch = watchSize(temp, timer, () => stop.abort(tooLarge()));
      let ran;
      try {
        ran = await git.run(cloneCall(temp, address, repository, stop.signal));
      } finally {
        await watch.stop();
      }
      if (ran.code !== 0) throw new CloneRefused(422, CLONE_REFUSALS.gitFailed, formatted(ran.stderr.toString('utf8')));
      if (await isOver(temp, CLONE_MAX_BYTES, CLONE_MAX_ENTRIES)) throw tooLarge();
      const made = join(temp, repository);
      if (!(await lock(() => moveIntoPlace(made, target)))) throw new Refusal(409, CLONE_REFUSALS.exists);
      try {
        await rmdir(temp);
        return { clone: target };
      } catch (error) {
        return { clone: target, problem: `the hidden folder ${temp} the clone was made in could not be removed: ${error instanceof Error ? error.message : String(error)}; delete it` };
      }
    },
    async () => {
      if (temp === undefined) return;
      try {
        await rm(temp, { recursive: true, force: true, maxRetries: 3 });
      } catch (error) {
        throw new Refusal(500, `the clone failed, and the hidden folder ${temp} it was made in could not be removed: ${error instanceof Error ? error.message : String(error)}; delete it`);
      }
    },
  );
}
