import { Refusal, type WorkingFolderLock } from '../../../server/workingFolder.ts';
import type { RemoteBody } from '../api.ts';
import { remoteBodyOf as remoteBodyRules, REMOTE_REFUSALS } from '../rules.ts';
import { BUSY, indexLocked } from './commit.ts';
import { formatted } from './formatter.ts';
import { holds, parseDiffTree, read, type Status } from './git.ts';
import { busyOf, statusOf } from './packages.ts';
import type { Call, Limits, Ran, Runner } from './runner.ts';

/** A fetch, a pull's merge and a push: 120 s each, and 1 MiB on each stream. */
export const REMOTE_LIMITS: Limits = { ms: 120_000, bytes: 1024 * 1024 };

/** `body` as a pull or a push takes it, refused as {@link remoteBodyRules} refuses it. */
export function remoteBodyOf(body: unknown): RemoteBody {
  const found = remoteBodyRules(body);
  if ('error' in found) throw new Refusal(found.status, found.error);
  return found;
}

/** A refused fetch, pull or push: its status, its error, and what git said. */
export class RemoteRefused extends Refusal {
  readonly output: string;

  constructor(status: number, message: string, output: string) {
    super(status, message);
    this.output = output;
  }
}

/** The upstream of a branch as git's config sets it: its remote-tracking branch, the remote, and the remote's branch, as `refs/heads/<name>`. */
interface Upstream {
  tracking: string;
  remote: string;
  merge: string;
}

const saidBy = (ran: Ran) => formatted([ran.stdout.toString('utf8'), ran.stderr.toString('utf8')].filter((t) => t.trim() !== '').join('\n'));

/**
 * A git call that may reach the remote: `-c core.askPass=` before the command makes git ask neither `core.askPass` nor
 * `SSH_ASKPASS` for a password, so a sign-in that no credential helper answers fails at once, at the terminal prompt
 * the runner refuses.
 */
const remoteCall = (clone: string, args: readonly string[]): Call => ({ args: ['-c', 'core.askPass=', ...args], clone, mode: 'write', limits: REMOTE_LIMITS });

/** The upstream `git for-each-ref` names for `branch` of the clone at `clone`; null when it names none. */
async function upstreamOf(git: Runner, clone: string, branch: string): Promise<Upstream | null> {
  const ref = `refs/heads/${branch}`;
  const listed = await read(git, clone, ['for-each-ref', '--format=%(refname)%00%(upstream)%00%(upstream:remotename)%00%(upstream:remoteref)', '--end-of-options', ref]);
  for (const line of listed.split('\n')) {
    const [name, tracking = '', remote = '', merge = ''] = line.split('\0');
    if (name === ref) return tracking === '' || remote === '' || !merge.startsWith('refs/heads/') ? null : { tracking, remote, merge };
  }
  return null;
}

/**
 * The clone's status and its branch's upstream, once it is checked: 409 while the clone is busy, another git holds its
 * index or `HEAD` is detached; with `body`, 412 when the branch, `HEAD` or the upstream's name is no longer the one it
 * names; and 409 when the branch has no upstream, or its remote-tracking branch is gone.
 */
async function checked(git: Runner, clone: string, body: RemoteBody | null): Promise<{ status: Status; upstream: Upstream }> {
  const status = await statusOf(git, clone);
  const busy = await busyOf(clone, status);
  if (busy !== null) throw new Refusal(409, `the clone is busy: ${BUSY[busy]}; finish it in a terminal first`);
  if (await indexLocked(clone)) throw new Refusal(409, 'the clone is busy: another git holds its index (.git/index.lock); try again once it ends');
  if (status.branch === null) throw new Refusal(409, REMOTE_REFUSALS.detached);
  if (body !== null && (status.branch !== body.branch || status.oid !== body.head || (status.upstream?.name ?? null) !== body.upstream)) {
    throw new Refusal(412, REMOTE_REFUSALS.stale);
  }
  const upstream = status.upstream === null ? null : await upstreamOf(git, clone, status.branch);
  if (upstream === null) throw new Refusal(409, REMOTE_REFUSALS.noUpstream);
  return { status, upstream };
}

/** `git fetch -- <remote>`; 422 with what git said when it fails. */
async function fetched(git: Runner, clone: string, remote: string): Promise<void> {
  const ran = await git.run(remoteCall(clone, ['fetch', '--', remote]));
  if (ran.code !== 0) throw new RemoteRefused(422, REMOTE_REFUSALS.failed('fetch'), saidBy(ran));
}

/**
 * Fetches the remote of the branch of the clone at `clone`, refused as {@link checked} refuses it without a body. It
 * changes no file, and takes no lock of the working folder.
 */
export async function fetchRemote(git: Runner, clone: string): Promise<void> {
  const { upstream } = await checked(git, clone, null);
  await fetched(git, clone, upstream.remote);
}

/**
 * Pulls into the package `repository`, whose clone is at `clone`: checked as {@link checked} checks it, it fetches the
 * branch's remote, then under the working folder's lock, which it holds to its end, it is checked again, resolves the
 * upstream once to a commit and fast-forwards to it with `git merge --ff-only --no-overwrite-ignore`. Refused with 409
 * when the branch has diverged from its upstream, and with 409 and what git said when git refuses the merge, as when it
 * would overwrite a change on disk or an ignored file. It answers `HEAD` after it, and the paths that differ between
 * the two commits, relative to the working folder, none when the upstream is `HEAD` or behind it; `changed` is null, and `problem` says why, when they cannot be read.
 */
export async function pullRemote(
  git: Runner,
  lock: WorkingFolderLock,
  clone: string,
  repository: string,
  body: RemoteBody,
): Promise<{ head: string; changed: string[] | null; problem?: string }> {
  const first = await checked(git, clone, body);
  await fetched(git, clone, first.upstream.remote);
  return lock(async () => {
    const { upstream } = await checked(git, clone, body);
    const target = (await read(git, clone, ['rev-parse', '--verify', '--end-of-options', `${upstream.tracking}^{commit}`])).trim();
    const isAncestor = (older: string, newer: string) => holds(git, clone, ['merge-base', '--is-ancestor', '--end-of-options', older, newer], [1]);
    // an upstream that HEAD holds already, or HEAD itself, leaves nothing to pull
    if (await isAncestor(target, body.head)) return { head: body.head, changed: [] };
    if (!(await isAncestor(body.head, target))) throw new Refusal(409, REMOTE_REFUSALS.diverged);
    const ran = await git.run({ args: ['merge', '--ff-only', '--no-overwrite-ignore', '--end-of-options', target], clone, mode: 'write', limits: REMOTE_LIMITS });
    if (ran.code !== 0) throw new RemoteRefused(409, REMOTE_REFUSALS.overwrites, saidBy(ran));
    try {
      const diffed = parseDiffTree(await read(git, clone, ['diff-tree', '-r', '-z', '--no-renames', '--no-commit-id', '--end-of-options', body.head, target, '--', ':(literal).']));
      return { head: target, changed: diffed.map((d) => `${repository}/${d.path}`) };
    } catch (error) {
      return { head: target, changed: null, problem: `the files the pull changed could not be read: ${error instanceof Error ? error.message : String(error)}` };
    }
  });
}

/**
 * Pushes the commit `body.head` of the clone at `clone` to the upstream's branch of its remote, checked as
 * {@link checked} checks it: `git push --porcelain -- <remote> <head>:refs/heads/<branch>`, never forced. Refused with
 * 409 and what git said when the remote rejects it, and with 422 when git fails otherwise, as when it cannot sign in.
 * It answers the remote's branch, the commit its remote-tracking branch named before, and the commit pushed.
 */
export async function pushRemote(git: Runner, clone: string, body: RemoteBody): Promise<{ ref: string; from: string; to: string }> {
  const { upstream } = await checked(git, clone, body);
  const from = (await read(git, clone, ['rev-parse', '--verify', '--end-of-options', `${upstream.tracking}^{commit}`])).trim();
  const ran = await git.run(remoteCall(clone, ['push', '--porcelain', '--', upstream.remote, `${body.head}:${upstream.merge}`]));
  if (ran.code !== 0) {
    // --porcelain marks each ref the remote rejected with ! and a tab
    const rejected = ran.stdout.toString('utf8').split('\n').some((line) => line.startsWith('!\t'));
    throw new RemoteRefused(rejected ? 409 : 422, rejected ? REMOTE_REFUSALS.rejected : REMOTE_REFUSALS.failed('push'), saidBy(ran));
  }
  return { ref: upstream.merge, from, to: body.head };
}
