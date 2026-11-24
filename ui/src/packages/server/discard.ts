import { lstat, readdir, realpath, rmdir, unlink } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { Refusal, type WorkingFolderLock } from '../../../server/workingFolder.ts';
import type { DiscardBody } from '../api.ts';
import { discardBodyOf as discardBodyRules, indexFrom, inPackage, staleChoice, statusCompared } from '../rules.ts';
import { BUSY, COMMIT_LIMITS, indexLocked } from './commit.ts';
import { formatted } from './formatter.ts';
import { catFile, parseStatus, parseTree, read } from './git.ts';
import { diskAt, inClone } from './package.ts';
import { busyOf, statusOf } from './packages.ts';
import type { Runner } from './runner.ts';

/** `body` as a discard of the package `repository` takes it, refused as {@link discardBodyRules} refuses it. */
export function discardBodyOf(repository: string, body: unknown): DiscardBody {
  const found = discardBodyRules(repository, body);
  if ('error' in found) throw new Refusal(found.status, found.error);
  return found;
}

/** A refused discard: its status, its error, and what git said. */
export class DiscardRefused extends Refusal {
  readonly output: string;

  constructor(status: number, message: string, output: string) {
    super(status, message);
    this.output = output;
  }
}

/** What a discard did, read back for the chosen paths alone, each list sorted, and why a path is left. */
export interface DiscardDone {
  discarded: string[];
  left: string[];
  problem?: string;
}

const NOTHING = 'nothing was discarded';
const ZEROS = '0'.repeat(40);
const literal = (inside: string) => `:(literal)${inside}`;
const textOf = (error: unknown) => (error instanceof Error ? error.message : String(error));
const saidBy = (ran: { stdout: Buffer; stderr: Buffer }) =>
  formatted([ran.stdout.toString('utf8'), ran.stderr.toString('utf8')].filter((t) => t.trim() !== '').join('\n'));
const byPath = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
const missing = (error: unknown) => (error as NodeJS.ErrnoException).code === 'ENOENT' || (error as NodeJS.ErrnoException).code === 'ENOTDIR';
const lstatOf = (path: string) => lstat(path).catch((error: unknown) => (missing(error) ? null : Promise.reject(error)));
/** The folders `inside` lies in, outermost first: `a` and `a/b` for `a/b/c`. */
const leadingOf = (inside: string) => inside.split('/').slice(0, -1).map((_, n, segments) => segments.slice(0, n + 1).join('/'));

/** One entry `ls-files -s -t -z` lists: its tag (`H`, or `S` for a skip-worktree entry), mode, blob, stage and path. */
interface IndexEntry {
  tag: string;
  mode: string;
  oid: string;
  stage: string;
  path: string;
}

function parseIndex(output: string): IndexEntry[] {
  return output
    .split('\0')
    .filter((record) => record !== '')
    .map((record) => {
      const tab = record.indexOf('\t');
      const [tag = '', mode = '', oid = '', stage = ''] = record.slice(0, tab).split(' ');
      return { tag, mode, oid, stage, path: record.slice(tab + 1) };
    });
}

/** Whether `inside`, or a folder on its way, has a real path that leaves the clone at `clone` or leads into its `.git`. */
async function escapes(clone: string, inside: string): Promise<boolean> {
  const segments = inside.split('/');
  for (let n = 1; n <= segments.length; n++) {
    const full = join(clone, ...segments.slice(0, n));
    if ((await lstatOf(full)) === null) return false;
    // a dangling link resolves nowhere: it is refused as a link, or as no folder on the way
    const real = await realpath(full).catch(() => null);
    if (real === null) return false;
    if (!inClone(clone, real)) return true;
  }
  return false;
}

/**
 * Puts the chosen paths of the package `repository`, whose clone is at the real path `clone`, back as they are in the
 * last commit, in the index and on disk, under the working folder's lock, which it holds from its first read to its
 * read-back. The paths the body names are exactly the paths it may change.
 *
 * 1. It is refused, with nothing done, in this order: 409 while the clone is busy (an operation left open, a path in
 *    conflict, or another git holding the index); 412 when `head` is not `HEAD` now; 412 for a path no longer among the
 *    changes, a renamed change named without its old path, that old path named as not on disk while it is on disk, and
 *    a path staged or unstaged since; 403 for a path, or a folder on its way, whose real path leaves the package or
 *    leads into `.git`; 412 for a path whose bytes on disk no longer have the tag the body names; 409 for a path whose
 *    index differs from both `HEAD` and the disk, one git holds as intent to add, a symbolic link on disk, a submodule, a
 *    skip-worktree entry, two paths the same but for letter case, and wherever a file and a folder of the same name would
 *    meet, in the last commit, the index or on disk. A detached `HEAD` is allowed: nothing moves.
 * 2. `update-index -z --index-info` sets the index entry of each chosen path to the last commit's, or removes it when
 *    the last commit lacks the path, in one atomic write. When it exits with an error, nothing has changed: 409 when
 *    another git holds `index.lock`, else 500, each with git's words.
 * 3. A chosen path the last commit holds that is an empty folder on disk is removed with `rmdir`; then
 *    `checkout-index -f -u -z --stdin` writes the chosen paths the last commit holds from the index, through the
 *    author's filters, with no hook. Only when both did their work, the half unlinks each chosen file the last commit
 *    lacks, once its folder and its tag are checked again. A failed step, or the runner stopping one, runs no later write.
 * 4. `git status` of the chosen paths alone reads what was discarded and what is left. When that read fails, it is
 *    500, never "nothing was discarded".
 */
export async function discardPaths(git: Runner, lock: WorkingFolderLock, clone: string, repository: string, body: DiscardBody): Promise<DiscardDone> {
  return lock(async () => {
    const status = await statusOf(git, clone);
    const busy = await busyOf(clone, status);
    if (busy !== null) throw new Refusal(409, `the clone is busy: ${BUSY[busy]}; finish it in a terminal, then discard`);
    if (await indexLocked(clone)) throw new Refusal(409, 'the clone is busy: another git holds its index (.git/index.lock); try again once it ends');
    if (status.oid !== body.head) throw new Refusal(412, `HEAD is now ${status.oid ?? '(no commit yet)'}, not the HEAD you saw; ${NOTHING}`);

    const named = body.paths.map((p) => (inPackage(repository, p.path) as { inside: string }).inside);
    const disk = await Promise.all(named.map((inside) => diskAt(clone, inside)));
    const compared = statusCompared(status.entries);
    const stale = staleChoice(repository, body.paths.map((p, n) => ({ path: p.path, inside: named[n]!, tag: p.tag, onDisk: disk[n] !== null })), compared, NOTHING);
    if (stale !== null) throw new Refusal(412, stale);
    // each path's index state as its row shows it now; null: the path a chosen renamed change was renamed from, with no row of its own
    const rows = new Set(compared.map((c) => c.inside));
    const fromChosen = new Set(compared.filter((c) => c.renamedFrom !== undefined && named.includes(c.inside)).map((c) => c.renamedFrom!));
    const indexOf = indexFrom(status.entries);
    const now = named.map((inside, n) => (rows.has(inside) ? indexOf(inside, disk[n] !== null) : null));
    body.paths.forEach((p, n) => {
      const index = now[n]!;
      const moved = index === null ? p.index !== null || !fromChosen.has(named[n]!) : p.index === null || (index !== 'differs' && index !== p.index);
      if (moved) throw new Refusal(412, `${p.path} was staged or unstaged since you saw it; ${NOTHING}`);
    });
    for (const [n, p] of body.paths.entries()) {
      if (await escapes(clone, named[n]!)) throw new Refusal(403, `${p.path} leads out of the package's folder, or into its .git folder; ${NOTHING}`);
    }
    body.paths.forEach((p, n) => {
      if ((disk[n]?.tag ?? null) !== p.tag) throw new Refusal(412, `${p.path} changed since you saw it; ${NOTHING}`);
    });

    // what the last commit and the index hold of the chosen paths; a listing cut at its cap fails the read, and nothing is written
    const inHead =
      body.head === null ? [] : parseTree(await read(git, clone, ['ls-tree', '-r', '-z', '--end-of-options', body.head, '--', ...named.map(literal)]));
    const chosen = new Set(named);
    const held = new Map(inHead.filter((e) => chosen.has(e.path)).map((e) => [e.path, e]));
    const listed = parseIndex(await read(git, clone, ['ls-files', '-z', '-s', '-t', '--', ...named.map((inside) => `:(literal,icase)${inside}`)]));
    const entry = new Map(listed.filter((e) => chosen.has(e.path) && e.stage === '0').map((e) => [e.path, e]));
    const onDisk = await Promise.all(named.map((inside) => lstatOf(join(clone, ...inside.split('/')))));

    const conflict = (message: string): never => {
      throw new Refusal(409, `${message}; ${NOTHING}`);
    };
    const known = [...listed.map((e) => e.path), ...status.entries.flatMap((e) => [e.path, ...(e.from === undefined ? [] : [e.from])])];
    body.paths.forEach((p, n) => {
      const inside = named[n]!;
      if (now[n] === 'differs') conflict(`${p.path} is staged in a terminal with other content: discard or unstage it there`);
      if (status.entries.some((e) => e.kind === 'ordinary' && e.path === inside && e.xy === '.A')) conflict(`git holds ${p.path} as intent to add (git add -N): discard it in a terminal`);
      if (onDisk[n]?.isSymbolicLink()) conflict(`${p.path} is a symbolic link on disk: discard it in a terminal`);
      if (held.get(inside)?.mode === '160000' || entry.get(inside)?.mode === '160000') conflict(`${p.path} is a submodule: discard it in a terminal`);
      if (entry.get(inside)?.tag === 'S') conflict(`${p.path} is outside the sparse checkout (skip-worktree): discard it in a terminal`);
      const lower = inside.toLowerCase();
      const alias = [...named.filter((other) => other !== inside), ...known].find((other) => other !== inside && other.toLowerCase() === lower);
      if (alias !== undefined) conflict(`${p.path} and ${repository}/${alias} are the same but for letter case: discard them in a terminal`);
    });

    // git deletes a file or a folder of the same name in its way without a word, so every such meeting is refused
    const meet = (p: string, other: string): never =>
      conflict(`${p} and ${repository}/${other} are a file and a folder of the same name: discard them one at a time, or in a terminal`);
    for (const [n, p] of body.paths.entries()) {
      const inside = named[n]!;
      for (const folder of leadingOf(inside)) {
        const info = await lstatOf(join(clone, ...folder.split('/')));
        if (info !== null && !info.isDirectory()) meet(p.path, folder);
      }
      if (onDisk[n]?.isDirectory()) {
        const contents = await readdir(join(clone, ...inside.split('/')));
        if (contents.length > 0) meet(p.path, `${inside}/${contents.sort(byPath)[0]!}`);
      }
    }
    const written = named.filter((inside) => held.has(inside));
    for (const inside of written) {
      const below = listed.find((e) => e.path.startsWith(`${inside}/`));
      if (below !== undefined) meet(`${repository}/${inside}`, below.path);
    }
    const folders = [...new Set(written.flatMap(leadingOf))];
    const asFiles = await catFile(git, clone, folders.map((folder) => `:0:${folder}`), true);
    for (const inside of written) {
      const folder = leadingOf(inside).find((f) => asFiles[folders.indexOf(f)] !== null);
      if (folder !== undefined) meet(`${repository}/${inside}`, folder);
    }

    // the index: each chosen path's entry set to the last commit's, or removed when the last commit lacks the path
    const lines = named.flatMap((inside) => {
      const commit = held.get(inside);
      const index = entry.get(inside);
      if (commit !== undefined) return index?.mode === commit.mode && index.oid === commit.oid ? [] : [`${commit.mode} ${commit.oid}\t${inside}\0`];
      return index === undefined ? [] : [`0 ${ZEROS}\t${inside}\0`];
    });
    const problems: string[] = [];
    let writing = true;
    if (lines.length > 0) {
      try {
        const ran = await git.run({ args: ['update-index', '-z', '--index-info'], clone, mode: 'write', stdin: lines.join(''), limits: COMMIT_LIMITS });
        if (ran.code !== 0) {
          if (await indexLocked(clone)) throw new DiscardRefused(409, `the clone is busy: another git holds its index (.git/index.lock); try again once it ends; ${NOTHING}`, saidBy(ran));
          throw new DiscardRefused(500, `git update-index failed, as git says; ${NOTHING}`, saidBy(ran));
        }
      } catch (error) {
        if (error instanceof DiscardRefused) throw error;
        problems.push(`git update-index was stopped, so nothing after it ran: ${textOf(error)}`);
        writing = false;
      }
    }

    if (writing) {
      // an empty folder at a chosen path is the half's own to remove: checkout-index -f would delete a folder that holds an ignored file
      const unremoved = new Set<string>();
      for (const [n, inside] of named.entries()) {
        if (!held.has(inside) || !onDisk[n]?.isDirectory()) continue;
        try {
          await rmdir(join(clone, ...inside.split('/')));
        } catch (error) {
          unremoved.add(inside);
          problems.push(`${repository}/${inside} could not be removed: ${textOf(error)}`);
        }
      }
      const checkout = written.filter((inside) => !unremoved.has(inside)).sort(byPath);
      let checkedOut = true;
      if (checkout.length > 0) {
        try {
          const ran = await git.run({ args: ['checkout-index', '-f', '-u', '-z', '--stdin'], clone, mode: 'write', stdin: checkout.map((inside) => `${inside}\0`).join(''), limits: COMMIT_LIMITS });
          if (ran.code !== 0) {
            checkedOut = false;
            problems.push(`git checkout-index failed, as git says: ${saidBy(ran)}`);
          }
        } catch (error) {
          checkedOut = false;
          problems.push(`git checkout-index was stopped: ${textOf(error)}`);
        }
      }
      if (unremoved.size === 0 && checkedOut) {
        for (const [n, inside] of named.entries()) {
          if (held.has(inside) || !onDisk[n]?.isFile()) continue;
          const path = join(clone, ...inside.split('/'));
          const folder = await realpath(dirname(path)).catch(() => null);
          const still = folder !== null && (folder === clone || inClone(clone, folder)) && (await diskAt(clone, inside))?.tag === body.paths[n]!.tag;
          if (!still || !(await lstatOf(path))?.isFile()) {
            problems.push(`${repository}/${inside} changed before it was deleted, and is left as it is`);
            continue;
          }
          await unlink(path).catch((error: unknown) => problems.push(`${repository}/${inside} could not be deleted: ${textOf(error)}`));
        }
      }
    }

    // the read-back of the chosen paths alone, so that the state's cap of changes does not apply
    let entries;
    try {
      entries = parseStatus(await read(git, clone, ['status', '--porcelain=v2', '-z', '--untracked-files=all', '--', ...named.map(literal)])).entries;
    } catch (error) {
      throw new Refusal(500, `the discard ran, but what it changed could not be read: ${textOf(error)}; the package's Changes show it`);
    }
    // a chosen path is left while git lists it, or an untracked file in the folder at its place
    const isLeft = (inside: string) => entries.some((e) => e.path === inside || e.from === inside || (e.kind === 'untracked' && e.path.startsWith(`${inside}/`)));
    const pathOf = (inside: string) => `${repository}/${inside}`;
    const done: DiscardDone = {
      discarded: named.filter((inside) => !isLeft(inside)).map(pathOf).sort(byPath),
      left: named.filter(isLeft).map(pathOf).sort(byPath),
    };
    return problems.length === 0 ? done : { ...done, problem: problems.join('; ') };
  });
}
