import { createHash } from 'node:crypto';
import { access, lstat, readlink } from 'node:fs/promises';
import { join } from 'node:path';
import { Refusal, type WorkingFolderLock } from '../../../server/workingFolder.ts';
import type { CommitBody, Committed } from '../api.ts';
import { COMMIT_REFUSALS, commitBodyOf as commitBodyRules, indexFrom, inPackage, staleChoice, statusCompared, unstageCommand, type Refused } from '../rules.ts';
import { formatted } from './formatter.ts';
import { catFile, GitSaid, LOG_FORMAT, parseDiffTree, parseLog, read } from './git.ts';
import { diskAt, identityOf } from './package.ts';
import { busyOf, statusOf } from './packages.ts';
import { refusal } from './refused.ts';
import type { Limits, Ran, Runner } from './runner.ts';

/** A commit with its hooks, and the `add` before it: 60 s, and 1 MiB on each stream. */
export const COMMIT_LIMITS: Limits = { ms: 60_000, bytes: 1024 * 1024 };

/** `body` as a commit of the package `repository` takes it, refused as {@link commitBodyRules} refuses it. */
export function commitBodyOf(repository: string, body: unknown): CommitBody {
  const found = commitBodyRules(repository, body);
  if ('error' in found) throw refusal(found.status, found);
  return found;
}

/** A refused commit: its status, its refusal, and what git and the hooks said. */
export class CommitRefused extends Refusal {
  readonly output: string;

  constructor(status: number, refused: Refused, output: string) {
    super(status, refused.code, refused.error, refused.values);
    this.output = output;
  }
}

/** What a commit did: the commit as it was read back (null: `problem` says why it could not be), and what git and the hooks said. */
export interface CommitDone {
  commit: Committed | null;
  output: string;
  problem?: string;
}

const literal = (inside: string) => `:(literal)${inside}`;
const saidBy = (ran: { stdout: Buffer; stderr: Buffer }) =>
  formatted([ran.stdout.toString('utf8'), ran.stderr.toString('utf8')].filter((t) => t.trim() !== '').join('\n'));
const textOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/** A step of the commit that git or a hook refused, `git add` or `git commit`, and what they said. */
class GitFailed extends Error {
  readonly step: 'add' | 'commit';
  readonly said: string;

  constructor(step: 'add' | 'commit', said: string) {
    super(step === 'add' ? 'git add failed' : 'git commit failed, as git and the hooks say');
    this.step = step;
    this.said = said;
  }
}

/**
 * Where `HEAD` was once the undo of a failed commit ran: where git commit started on it, not read again, or moved to
 * another commit (null: to no commit).
 */
type HeadAfter = { kind: 'unchanged' } | { kind: 'unread' } | { kind: 'moved'; head: string | null };

/**
 * The refusal of the failed commit: the refusal of the step git refused, as `HEAD` was after it, or `failure`'s own text
 * with what `HEAD` did and `staged`, the notes on the paths left staged. `said`, what git said during the undo, joins
 * what git and the hooks said.
 */
function noted(failure: unknown, head: HeadAfter, staged: readonly string[], said: string): Error {
  const status = failure instanceof GitFailed ? 422 : failure instanceof Refusal ? failure.status : 500;
  const output = [failure instanceof GitFailed ? failure.said : '', said].filter((t) => t !== '').join('\n');
  // git add runs before HEAD is read, so HEAD is where it was when it fails
  const step = failure instanceof GitFailed && staged.length === 0 ? failure.step : null;
  let refused: Refused;
  if (step === 'add') refused = COMMIT_REFUSALS.addFailed;
  else if (step === 'commit' && head.kind === 'unchanged') refused = COMMIT_REFUSALS.failed;
  else if (step === 'commit' && head.kind === 'unread') refused = COMMIT_REFUSALS.failedHeadUnread;
  else if (step === 'commit' && head.kind === 'moved' && head.head !== null) refused = COMMIT_REFUSALS.failedHeadMoved({ head: head.head });
  else {
    const headNote =
      head.kind === 'unchanged'
        ? 'nothing was committed'
        : head.kind === 'unread'
          ? "HEAD could not be read again, so git may have committed: the package's history shows it"
          : `HEAD moved to ${head.head ?? 'no commit'} meanwhile, so git may have committed: the package's history shows it`;
    refused = COMMIT_REFUSALS.failedNoted([failure instanceof Error ? failure.message : textOf(failure), headNote, ...staged].join('; '));
  }
  return failure instanceof GitFailed || output !== '' ? new CommitRefused(status, refused, output) : refusal(status, refused);
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
    if (busy !== null) throw refusal(409, COMMIT_REFUSALS.busy({ busy }));
    if (await indexLocked(clone)) throw refusal(409, COMMIT_REFUSALS.indexLocked);
    if (status.branch === null) throw refusal(409, COMMIT_REFUSALS.detached);
    if ((await identityOf(git, clone)) === null) throw refusal(422, COMMIT_REFUSALS.noIdentity);
    if (status.branch !== body.branch || status.oid !== body.head) {
      throw refusal(412, status.oid === null ? COMMIT_REFUSALS.movedUnborn({ branch: status.branch }) : COMMIT_REFUSALS.moved({ branch: status.branch, head: status.oid }));
    }
    const indexOf = indexFrom(status.entries);
    const named = body.paths.map((p) => (inPackage(repository, p.path) as { inside: string }).inside);
    const disk = await Promise.all(named.map((inside) => diskAt(clone, inside)));
    const stale = staleChoice(repository, body.paths.map((p, n) => ({ path: p.path, inside: named[n]!, tag: p.tag, onDisk: disk[n] !== null })), statusCompared(status.entries), COMMIT_REFUSALS);
    if (stale !== null) throw refusal(412, stale);
    body.paths.forEach((p, n) => {
      if ((disk[n]?.tag ?? null) !== p.tag) throw refusal(412, COMMIT_REFUSALS.changed({ path: p.path }));
    });
    body.paths.forEach((p, n) => {
      if (indexOf(named[n]!, disk[n] !== null) === 'differs') {
        throw refusal(409, COMMIT_REFUSALS.staged({ path: p.path }));
      }
    });

    // the blob each chosen path makes now, as git would store it, against which the commit is read back and the undo
    // knows the index entries the add made: none for a path not on disk; anything that is neither a file nor a symbolic
    // link is not read back
    const expected = new Map([...(await blobsOf(git, clone, named))].filter(([, blob]) => blob !== 'other'));

    const untracked = new Set(status.entries.filter((e) => e.kind === 'untracked').map((e) => e.path));
    const added = named.filter((inside) => untracked.has(inside));
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
            if (adding.code !== 0) throw new GitFailed('add', saidBy(adding));
          }
          before = (await catFile(git, clone, ['HEAD^{commit}'], true))[0]?.oid ?? null;
          const committing = await git.run({ args: ['commit', '--only', '-F', '-', '--', ...named.map(literal)], clone, mode: 'write', stdin: body.message, limits: COMMIT_LIMITS });
          if (committing.code !== 0) throw new GitFailed('commit', saidBy(committing));
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
      const head: HeadAfter =
        before === undefined || (after !== undefined && after === before)
          ? { kind: 'unchanged' }
          : after === undefined
            ? { kind: 'unread' }
            : { kind: 'moved', head: after === null ? null : after.slice(0, 7) };
      const staged = [
        ...(kept.length === 0 ? [] : [`${pathsOf(kept)} stay staged, as another git staged them meanwhile`]),
        ...(stuck.length === 0 ? [] : [`${pathsOf(stuck)} stay staged, since git would not take them out of the index: unstage them in a terminal with ${unstageCommand(stuck, (after === undefined ? status.oid : after) === null)}`]),
      ];
      throw noted(failure, head, staged, said);
    }

    try {
      return { commit: await readBack(git, clone, repository, status.branch, ran.stdout.toString('utf8'), named, expected), output: saidBy(ran) };
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
 * The blob each of `insides` makes on disk in the clone at `clone`, as git would store it: a file through git's own
 * filters, a symbolic link as the text of its target; null for a path not on disk, and `other` for anything else.
 */
async function blobsOf(git: Pick<Runner, 'run'>, clone: string, insides: readonly string[]): Promise<Map<string, string | null>> {
  // true: a file, which git hashes; otherwise the blob of a symbolic link, null when not on disk, or 'other'
  const found = await Promise.all(
    insides.map(async (inside): Promise<true | string | null> => {
      const path = join(clone, ...inside.split('/'));
      const info = await lstat(path).catch(() => null);
      if (info === null) return null;
      if (info.isFile()) return true;
      return info.isSymbolicLink() ? readlink(path, { encoding: 'buffer' }).then(linkBlob, () => 'other') : 'other';
    }),
  );
  const hashing = insides.filter((_, n) => found[n] === true);
  const hashed = hashing.length === 0 ? [] : (await read(git, clone, ['hash-object', '--', ...hashing])).split('\n');
  const blobs = new Map<string, string | null>(insides.flatMap((inside, n) => (found[n] === true ? [] : [[inside, found[n] as string | null] as const])));
  hashing.forEach((inside, n) => blobs.set(inside, hashed[n] ?? ''));
  return blobs;
}

/**
 * The blob git stores for a symbolic link to `target`: the target's bytes as a blob, in SHA-1, the object format whose
 * ids of 40 hex digits the git half reads. `hash-object` would hash the file the link leads to instead.
 */
function linkBlob(target: Buffer): string {
  return createHash('sha1').update(`blob ${target.length}\0`).update(target).digest('hex');
}

/** Whether another git holds the index of the clone at `clone`: its `index.lock` exists. */
export async function indexLocked(clone: string): Promise<boolean> {
  return access(join(clone, '.git', 'index.lock')).then(
    () => true,
    () => false,
  );
}
