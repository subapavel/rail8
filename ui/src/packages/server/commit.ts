import { access, lstat } from 'node:fs/promises';
import { join } from 'node:path';
import { Refusal, type WorkingFolderLock } from '../../../server/workingFolder.ts';
import type { CommitBody, Committed } from '../api.ts';
import { commitBodyOf as commitBodyRules, indexFrom, inPackage, statusCompared, unstageCommand } from '../rules.ts';
import { formatted } from './formatter.ts';
import { catFile, GitSaid, LOG_FORMAT, parseDiffTree, parseLog, read } from './git.ts';
import { diskAt, identityOf } from './package.ts';
import { busyOf, statusOf } from './packages.ts';
import type { Limits, Ran, Runner } from './runner.ts';

/** A commit with its hooks, and the `add` before it: 60 s, and 1 MiB on each stream. */
export const COMMIT_LIMITS: Limits = { ms: 60_000, bytes: 1024 * 1024 };

/** `body` as a commit of the package `repository` takes it, refused as {@link commitBodyRules} refuses it. */
export function commitBodyOf(repository: string, body: unknown): CommitBody {
  const found = commitBodyRules(repository, body);
  if ('error' in found) throw new Refusal(found.status, found.error);
  return found;
}

/** A refused commit: its status, its error, and what git and the hooks said. */
export class CommitRefused extends Refusal {
  readonly output: string;

  constructor(status: number, message: string, output: string) {
    super(status, message);
    this.output = output;
  }
}

/** What a commit did: the commit as it was read back (null: `problem` says why it could not be), and what git and the hooks said. */
export interface CommitDone {
  commit: Committed | null;
  output: string;
  problem?: string;
}

const BUSY = {
  merge: 'a merge is in progress',
  rebase: 'a rebase is in progress',
  'cherry-pick': 'a cherry-pick is in progress',
  revert: 'a revert is in progress',
  bisect: 'a bisect is in progress',
  conflict: 'a path is in conflict',
} as const;

const literal = (inside: string) => `:(literal)${inside}`;
const saidBy = (ran: { stdout: Buffer; stderr: Buffer }) =>
  formatted([ran.stdout.toString('utf8'), ran.stderr.toString('utf8')].filter((t) => t.trim() !== '').join('\n'));
const textOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/** `failure` with `note` added to its error text, and `said`, what git said meanwhile, to its output. */
function noted(failure: unknown, note: string, said: string): Error {
  const status = failure instanceof Refusal ? failure.status : 500;
  const message = `${failure instanceof Refusal ? failure.message : textOf(failure)}; ${note}`;
  const output = [failure instanceof CommitRefused ? failure.output : '', said].filter((t) => t !== '').join('\n');
  return failure instanceof CommitRefused || output !== '' ? new CommitRefused(status, message, output) : new Refusal(status, message);
}

/**
 * Commits the chosen paths of the package `repository`, whose clone is at the real path `clone`, under the working
 * folder's lock, which it holds from its check to its end:
 *
 * 1. It is refused, with nothing done: 409 while the clone is busy (an operation left open, a path in conflict, or
 *    another git holding the index) or its `HEAD` is detached; 422 when git gives no author identity; 412 when the
 *    branch or `HEAD` is no longer the one the request names, for a path that is no longer among the package's changes,
 *    and for a path whose bytes on disk no longer have the tag the request names (null: not on disk); 409 for a path
 *    whose index holds neither `HEAD`'s version nor the one on disk. A renamed change is named with the path it was
 *    renamed from, which is otherwise 412, as is that path on disk while the request names it as not on disk.
 * 2. `git add` takes the chosen paths that are untracked, then `git commit --only -F - -- <paths>` commits the chosen
 *    paths alone, with the message on stdin and the author's hooks and signing. When either fails, or the development
 *    server closes meanwhile, each added path that `HEAD` lacks and whose index entry is still the blob the request
 *    checked leaves the index again (`rm --cached`), its file staying on disk. git refuses that removal when the file's
 *    bytes changed since, as after a hook that rewrote it and refused; the paths whose bytes did not then leave in a
 *    second removal. The answer is 422 with what git and the hooks said, or the runner's own refusal. Its error says
 *    whether `HEAD` moved meanwhile, so that git may have committed, and names an added path that stays staged: as
 *    another git staged it meanwhile, or since git would not take it out of the index, with a command that does.
 * 3. The new commit is read back once it is known to be git's: `HEAD` is the commit git named on stdout. It reports
 *    the paths it changes against its parent that were not chosen, the chosen paths whose committed blob (none
 *    counts) is not the `hash-object` of their bytes as they were checked, and the chosen paths whose bytes on disk
 *    are not what it holds. None is undone. When it cannot be read back, or `HEAD` moved on to another commit, as when
 *    a terminal amends git's, `commit` is null and `problem` says why.
 */
export async function commitPaths(git: Runner, lock: WorkingFolderLock, clone: string, repository: string, body: CommitBody): Promise<CommitDone> {
  return lock(async () => {
    const status = await statusOf(git, clone);
    const busy = await busyOf(clone, status);
    if (busy !== null) throw new Refusal(409, `the clone is busy: ${BUSY[busy]}; finish it in a terminal, then commit`);
    if (await indexLocked(clone)) throw new Refusal(409, 'the clone is busy: another git holds its index (.git/index.lock); try again once it ends');
    if (status.branch === null) throw new Refusal(409, 'HEAD is detached: commit on a branch, which a terminal can switch to');
    if ((await identityOf(git, clone)) === null) {
      throw new Refusal(422, 'git gives no author identity in this clone: set it in a terminal with git config user.name and git config user.email');
    }
    if (status.branch !== body.branch || status.oid !== body.head) {
      throw new Refusal(412, `the clone is now at ${status.branch} ${status.oid ?? '(no commit yet)'}, not at the branch and HEAD you reviewed; nothing was committed`);
    }
    const compared = statusCompared(status.entries);
    const changed = new Set(compared.flatMap((c) => [c.inside, ...(c.renamedFrom === undefined ? [] : [c.renamedFrom])]));
    const indexOf = indexFrom(status.entries);
    const named = body.paths.map((p) => (inPackage(repository, p.path) as { inside: string }).inside);
    const disk = await Promise.all(named.map((inside) => diskAt(clone, inside)));
    body.paths.forEach((p, n) => {
      if (!changed.has(named[n]!)) throw new Refusal(412, `${p.path} is no longer among the package's changes; nothing was committed`);
    });
    // a renamed change is chosen with the path it was renamed from, as the author reviewed it: not on disk, unless the
    // request names that path with a tag of its own
    const renamedFrom = new Map(compared.flatMap((c) => (c.renamedFrom === undefined ? [] : [[c.inside, c.renamedFrom] as const])));
    body.paths.forEach((p, n) => {
      const from = renamedFrom.get(named[n]!);
      if (from === undefined) return;
      const source = named.indexOf(from);
      if (source < 0) throw new Refusal(412, `${p.path} is renamed from ${repository}/${from}, which is not among the paths chosen with it; nothing was committed`);
      if (body.paths[source]!.tag === null && disk[source] !== null) {
        throw new Refusal(412, `${repository}/${from}, which ${p.path} was renamed from, is on disk: choose it too; nothing was committed`);
      }
    });
    body.paths.forEach((p, n) => {
      if ((disk[n]?.tag ?? null) !== p.tag) throw new Refusal(412, `${p.path} changed since you reviewed it; nothing was committed`);
    });
    body.paths.forEach((p, n) => {
      if (indexOf(named[n]!, disk[n] !== null) === 'differs') {
        throw new Refusal(409, `${p.path} is staged in a terminal with other content: commit or unstage it there; nothing was committed`);
      }
    });

    const insides = named;
    // the blob each chosen file makes now, through git's own filters, against which the commit is read back and the
    // undo knows the index entries the add made: none for a path not on disk; a symbolic link, or anything else that is
    // no file, is not read back
    const expected = new Map([...(await blobsOf(git, clone, insides))].filter(([, blob]) => blob !== 'other'));

    const untracked = new Set(status.entries.filter((e) => e.kind === 'untracked').map((e) => e.path));
    const added = insides.filter((inside) => untracked.has(inside));
    // HEAD as git commit starts on it, and as the undo finds it
    let before: string | null | undefined;
    let after: string | null | undefined;
    // the added paths that stay staged: as another git staged them, or since git would not take them out of the index
    let kept: string[] = [];
    let stuck: string[] = [];
    let said = '';
    let ran: Ran;
    try {
      ran = await git.undoable(
        async () => {
          if (added.length > 0) {
            const adding = await git.run({ args: ['add', '--', ...added.map(literal)], clone, mode: 'write', limits: COMMIT_LIMITS });
            if (adding.code !== 0) throw new CommitRefused(422, 'git add failed', saidBy(adding));
          }
          before = (await catFile(git, clone, ['HEAD^{commit}'], true))[0]?.oid ?? null;
          const committing = await git.run({ args: ['commit', '--only', '-F', '-', '--', ...insides.map(literal)], clone, mode: 'write', stdin: body.message, limits: COMMIT_LIMITS });
          if (committing.code !== 0) throw new CommitRefused(422, 'git commit failed, as git and the hooks say', saidBy(committing));
          return committing;
        },
        // an added path leaves the index again while HEAD lacks it and its entry is the blob the request checked; the
        // file stays on disk. At most six calls, each within the undo's limits.
        async (run) => {
          try {
            const [head, ...inHead] = await catFile({ run }, clone, ['HEAD^{commit}', ...added.map((inside) => `HEAD:${inside}`)], true);
            after = head?.oid ?? null;
            const lacking = added.filter((_, n) => inHead[n] === null);
            if (lacking.length === 0) return;
            const now = await entriesOf(run, clone, lacking);
            const owned = lacking.filter((inside) => {
              const [, blob, stage] = (now.get(inside) ?? '').split(' ');
              return stage === '0' && blob === expected.get(inside);
            });
            kept = lacking.filter((inside) => now.has(inside) && !owned.includes(inside));
            if (owned.length === 0) return;
            const remove = (insides: readonly string[]) => run({ args: ['rm', '--cached', '-q', '--', ...insides.map(literal)], clone, mode: 'write' });
            const removing = await remove(owned);
            if (removing.code !== 0) {
              // git refuses the whole removal for a path whose bytes on disk changed since the add
              said = saidBy(removing);
              const onDisk = await blobsOf({ run }, clone, owned);
              const unchanged = owned.filter((inside) => onDisk.get(inside) === expected.get(inside));
              if (unchanged.length > 0) {
                const again = await remove(unchanged);
                if (again.code !== 0) said = saidBy(again);
              }
            }
            const left = await entriesOf(run, clone, owned);
            stuck = owned.filter((inside) => left.has(inside));
          } catch (error) {
            said = textOf(error);
            stuck = added.filter((inside) => !kept.includes(inside));
          }
        },
      );
    } catch (failure) {
      const pathsOf = (insides: readonly string[]) => insides.map((inside) => `${repository}/${inside}`).join(', ');
      const notes = [
        before === undefined || (after !== undefined && after === before)
          ? 'nothing was committed'
          : after === undefined
            ? "HEAD could not be read again, so git may have committed: the package's history shows it"
            : `HEAD moved to ${after === null ? 'no commit' : after.slice(0, 7)} meanwhile, so git may have committed: the package's history shows it`,
        ...(kept.length === 0 ? [] : [`${pathsOf(kept)} stay staged, as another git staged them meanwhile`]),
        ...(stuck.length === 0 ? [] : [`${pathsOf(stuck)} stay staged, since git would not take them out of the index: unstage them in a terminal with ${unstageCommand(stuck, after === null)}`]),
      ];
      throw noted(failure, notes.join('; '), said);
    }

    try {
      return { commit: await readBack(git, clone, repository, status.branch, ran.stdout.toString('utf8'), insides, expected), output: saidBy(ran) };
    } catch (error) {
      return { commit: null, output: saidBy(ran), problem: textOf(error) };
    }
  });
}

/** The index entries of `insides` in the clone at `clone`, each as `<mode> <blob> <stage>`, by path; a path the index lacks is absent. */
async function entriesOf(run: Runner['run'], clone: string, insides: readonly string[]): Promise<Map<string, string>> {
  const ran = await run({ args: ['ls-files', '-s', '-z', '--', ...insides.map(literal)], clone, mode: 'read' });
  if (ran.code !== 0) throw new GitSaid(formatted(ran.stderr.toString('utf8')) || `git ls-files exited with ${String(ran.code)}`);
  const entries = new Map<string, string>();
  for (const record of ran.stdout.toString('utf8').split('\0')) {
    const tab = record.indexOf('\t');
    if (tab > 0) entries.set(record.slice(tab + 1), record.slice(0, tab));
  }
  return entries;
}

/**
 * The abbreviated id of the commit `git commit` names on the branch `branch` in its summary line,
 * `[<branch> <id>] <subject>`, the first line of what it prints on stdout (the hooks print on stderr); undefined when it
 * names none.
 */
export function committedBy(stdout: string, branch: string): string | undefined {
  // a first commit has a marker between the branch and the id, in the author's language
  const line = new RegExp(`^\\[${branch.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} (?:.*? )??([0-9a-f]{4,64})\\] `);
  return line.exec(stdout.split('\n', 1)[0]!)?.[1];
}

/**
 * The commit git made on `branch` in the clone at `clone`, which it named in `stdout`, read back against the chosen
 * paths `insides` and the blob `expected` of each as it was checked; an error that says why when `HEAD` is not that
 * commit, or when it cannot be read back.
 */
async function readBack(
  git: Runner,
  clone: string,
  repository: string,
  branch: string,
  stdout: string,
  insides: readonly string[],
  expected: ReadonlyMap<string, string | null>,
): Promise<Committed> {
  const named = committedBy(stdout, branch);
  let id: string;
  try {
    id = (await read(git, clone, ['rev-parse', '--verify', '--end-of-options', 'HEAD^{commit}'])).trim();
  } catch (error) {
    throw new Error(`git committed${named === undefined ? '' : ` ${named}`}, but its commit could not be read back: ${textOf(error)}`, { cause: error });
  }
  if (named === undefined) throw new Error(`git committed, but named no commit, so the commit git made cannot be told apart from HEAD, ${id.slice(0, 7)}`);
  if (!id.startsWith(named)) {
    throw new Error(`git committed ${named}, but HEAD moved on at once to ${id.slice(0, 7)}, as when a terminal commits or amends right after, so the commit HEAD shows is not the one git made`);
  }
  try {
    const [made] = parseLog(await read(git, clone, ['log', '-1', '-z', LOG_FORMAT, '--end-of-options', id, '--']));
    if (made === undefined) throw new Error(`git log names no commit ${id}`);
    const changes = parseDiffTree(await read(git, clone, ['diff-tree', '-r', '-z', '--no-renames', '--no-commit-id', '--root', '--end-of-options', id]));
    const chosen = new Set(insides);
    const committed = await catFile(git, clone, insides.map((inside) => `${id}:${inside}`), true);
    const onDisk = await blobsOf(git, clone, insides);
    const pathOf = (inside: string) => `${repository}/${inside}`;
    return {
      ...made,
      staged: changes.filter((d) => !chosen.has(d.path)).map((d) => pathOf(d.path)).sort(),
      rewritten: insides.filter((inside, n) => expected.has(inside) && (committed[n]?.oid ?? null) !== expected.get(inside)).map(pathOf).sort(),
      changedOnDisk: insides.filter((inside, n) => expected.has(inside) && onDisk.get(inside) !== (committed[n]?.oid ?? null)).map(pathOf).sort(),
    };
  } catch (error) {
    throw new Error(`git committed ${id.slice(0, 7)}, but it could not be read back: ${textOf(error)}`, { cause: error });
  }
}

/**
 * The blob each of `insides` makes on disk in the clone at `clone`, through git's own filters: null for a path not on
 * disk, and `other` for a symbolic link or anything else that is no file.
 */
async function blobsOf(git: Pick<Runner, 'run'>, clone: string, insides: readonly string[]): Promise<Map<string, string | null>> {
  const files = await Promise.all(insides.map((inside) => lstat(join(clone, ...inside.split('/'))).then((info) => (info.isFile() ? true : 'other'), () => false)));
  const hashing = insides.filter((_, n) => files[n] === true);
  const hashed = hashing.length === 0 ? [] : (await read(git, clone, ['hash-object', '--', ...hashing])).split('\n');
  const blobs = new Map<string, string | null>(insides.flatMap((inside, n) => (files[n] === true ? [] : [[inside, files[n] === false ? null : 'other'] as const])));
  hashing.forEach((inside, n) => blobs.set(inside, hashed[n] ?? ''));
  return blobs;
}

/** Whether another git holds the index of the clone at `clone`: its `index.lock` exists. */
async function indexLocked(clone: string): Promise<boolean> {
  return access(join(clone, '.git', 'index.lock')).then(
    () => true,
    () => false,
  );
}
