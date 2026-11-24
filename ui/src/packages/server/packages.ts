import type { Dirent } from 'node:fs';
import { lstat, readdir, readFile, realpath, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { isSessionRefused } from '../../../server/session.ts';
import { MAX_FILE_BYTES, Refusal } from '../../../server/workingFolder.ts';
import type { Busy, Commit, Deployed, PackageList, PackageRow, Pool, PoolNode } from '../api.ts';
import { isRepositoryName, isSaveTemporary, manifestOf, MAX_PACKAGES, REASONS } from '../rules.ts';
import { holds, LOG_FORMAT, parseLog, parseStatus, read, type Status } from './git.ts';
import type { NodeRead, PoolRead } from './node.ts';
import type { Runner } from './runner.ts';

/** How many folders are read at once: each runs its git reads one after the other. */
const AT_ONCE = 4;

/**
 * What the rows need of the node: its host, what it runs and what its pool runs (null: it answers no pool route), each
 * read once and only when a row needs it.
 */
export interface NodeState {
  host: string;
  read: () => Promise<NodeRead>;
  pool: () => Promise<PoolRead | { error: string } | null>;
}

const missing = (error: unknown) => (error as NodeJS.ErrnoException).code === 'ENOENT';
const exists = (path: string) => lstat(path).then(() => true, (error: unknown) => (missing(error) ? false : Promise.reject(error)));

/**
 * Whether the folder `name` directly in the real working folder `root` is a package, and why not when it is no
 * package. A package's name is a repository name, its `.git` is a folder, `git rev-parse --show-toplevel` answers the
 * folder itself, and it holds `package.yaml` at its root. A symbolic link is no package: a package is a folder itself.
 * Only a `package.yaml` that is not there makes it no package; any other failure to look at it rejects.
 */
export async function folderOf(git: Runner, root: string, entry: Pick<Dirent, 'name' | 'isSymbolicLink'>): Promise<{ clone: string } | { reason: string }> {
  if (!isRepositoryName(entry.name)) return { reason: REASONS.name };
  const folder = join(root, entry.name);
  if (entry.isSymbolicLink()) return { reason: REASONS.link };
  const dotGit = await lstat(join(folder, '.git')).catch((error: unknown) => (missing(error) ? null : Promise.reject(error)));
  if (dotGit === null) return { reason: REASONS.noClone };
  if (!dotGit.isDirectory()) return { reason: REASONS.worktree };
  const top = (await read(git, folder, ['rev-parse', '--show-toplevel'])).replace(/\r?\n$/, '');
  if ((await realpath(top).catch(() => top)) !== folder) return { reason: REASONS.nested };
  const manifest = await lstat(join(folder, 'package.yaml')).catch((error: unknown) => (missing(error) ? null : Promise.reject(error)));
  if (manifest === null || !manifest.isFile()) return { reason: REASONS.noManifest };
  return { clone: folder };
}

/** The commit `oid` of the clone at `clone`, from `git log -1 -z`. */
async function commitOf(git: Runner, clone: string, oid: string): Promise<Commit> {
  const [commit] = parseLog(await read(git, clone, ['log', '-1', '-z', LOG_FORMAT, '--end-of-options', oid, '--']));
  return commit ?? { id: oid, parents: [], subject: '', author: '', at: '' };
}

/** The operation git left open in the clone, from the state files in its `.git`; else a path in conflict; else none. */
export async function busyOf(clone: string, status: Status): Promise<Busy> {
  const files: [string, Exclude<Busy, null | 'conflict'>][] = [
    ['MERGE_HEAD', 'merge'],
    ['rebase-merge', 'rebase'],
    ['rebase-apply', 'rebase'],
    ['CHERRY_PICK_HEAD', 'cherry-pick'],
    ['REVERT_HEAD', 'revert'],
    ['BISECT_LOG', 'bisect'],
  ];
  for (const [file, busy] of files) if (await exists(join(clone, '.git', file))) return busy;
  return status.entries.some((e) => e.kind === 'unmerged') ? 'conflict' : null;
}

/** The time the last fetch wrote `FETCH_HEAD`, or null when the clone was never fetched. */
async function fetchedAtOf(clone: string): Promise<string | null> {
  return stat(join(clone, '.git', 'FETCH_HEAD')).then(
    (info) => info.mtime.toISOString(),
    (error: unknown) => (missing(error) ? null : Promise.reject(error)),
  );
}

/** The name and version `package.yaml` at the clone's root gives, or why it cannot be read. */
async function packageOf(clone: string): Promise<Pick<PackageRow, 'package' | 'problem'>> {
  const file = join(clone, 'package.yaml');
  if ((await stat(file)).size > MAX_FILE_BYTES) return { package: null, problem: 'package.yaml is over 1 MiB' };
  return manifestOf(await readFile(file, 'utf8'));
}

/** How a commit a node runs relates to the clone's `head`: `deployed`, `behind`, `ahead` or `other`, with how many commits apart. */
type Relation = { relation: 'deployed' | 'behind' | 'ahead' | 'other'; commits?: number };

/**
 * How `commit` relates to the clone's `head`, by `cat-file -e`, `merge-base --is-ancestor` in both directions and
 * `rev-list --count`.
 */
async function relationOf(git: Runner, clone: string, head: Commit | null, commit: string): Promise<Relation> {
  if (head !== null && commit === head.id) return { relation: 'deployed' };
  if (head === null || !/^[0-9a-f]{40}$/.test(commit)) return { relation: 'other' };
  // cat-file -e exits with 1, or 128 for an object it cannot name, when the clone lacks the commit
  if (!(await holds(git, clone, ['cat-file', '-e', '--end-of-options', `${commit}^{commit}`], [1, 128]))) return { relation: 'other' };
  const isAncestor = (older: string, newer: string) => holds(git, clone, ['merge-base', '--is-ancestor', '--end-of-options', older, newer], [1]);
  const count = async (range: string) => Number((await read(git, clone, ['rev-list', '--count', '--end-of-options', range, '--'])).trim());
  if (await isAncestor(commit, head.id)) return { relation: 'behind', commits: await count(`${commit}..${head.id}`) };
  if (await isAncestor(head.id, commit)) return { relation: 'ahead', commits: await count(`${head.id}..${commit}`) };
  return { relation: 'other' };
}

/**
 * What every node of the pool runs of `repository`, each commit related to the clone's `head` once: the pool's record of
 * it, and each node with whether what it runs is that record.
 */
async function poolOf(read: PoolRead, repository: string, relate: (commit: string) => Promise<Relation>): Promise<Pool> {
  const record = read.records.find((r) => r.repository === repository)?.commit ?? null;
  const nodes = await Promise.all(
    read.nodes.map(async ({ node, packages }): Promise<PoolNode> => {
      const runs = packages.find((p) => p.repository === repository);
      if (runs === undefined) return { node, follows: record === null, behind: [], relation: 'none' };
      const { commit, state, deployedAt, behind } = runs;
      return { node, follows: commit === record, behind, commit, state, deployedAt, ...(await relate(commit)) };
    }),
  );
  return { record, nodes };
}

/** What the node runs of `repository`, related to the clone's `head`, named by the node's host, with what its pool runs when it answers it. */
async function deployedOf(git: Runner, clone: string, repository: string, head: Commit | null, node: NodeState): Promise<Deployed> {
  const [answer, pooled] = await Promise.all([node.read(), node.pool()]);
  if (!answer.answered) return { node: 'unreachable', host: node.host, error: answer.error };
  const related = new Map<string, Promise<Relation>>();
  const relate = (commit: string) => {
    if (!related.has(commit)) related.set(commit, relationOf(git, clone, head, commit));
    return related.get(commit)!;
  };
  const pool = pooled === null ? {} : { pool: 'error' in pooled ? pooled : await poolOf(pooled, repository, relate) };
  const running = answer.running.find((r) => r.repository === repository);
  if (running === undefined) return { node: 'answered', host: node.host, relation: 'none', ...pool };
  const { commit, state, problems, deployedAt } = running;
  return { node: 'answered', host: node.host, commit, state, problems, deployedAt, ...(await relate(commit)), ...pool };
}

/** What `git status` says of the package's folder, the clone at the real path `clone`, untracked files included. */
export async function statusOf(git: Runner, clone: string): Promise<Status> {
  return parseStatus(await read(git, clone, ['status', '--porcelain=v2', '--branch', '-z', '--untracked-files=all', '--', ':(literal).']));
}

/** The row of the package whose clone is at the real path `clone`, whose status is `status`. */
export async function rowOf(git: Runner, clone: string, repository: string, node: NodeState, status: Status): Promise<PackageRow> {
  const head = status.oid === null ? null : await commitOf(git, clone, status.oid);
  const [manifest, busy, fetchedAt, deployed] = await Promise.all([packageOf(clone), busyOf(clone, status), fetchedAtOf(clone), deployedOf(git, clone, repository, head, node)]);
  return {
    repository,
    ...manifest,
    branch: status.branch,
    head,
    upstream: status.upstream === null ? null : { ...status.upstream, fetchedAt },
    busy,
    changed: status.entries.filter((e) => !isSaveTemporary(e.path)).length,
    deployed,
  };
}

/**
 * The packages directly in the real working folder `root`, sorted by repository, and the folders that are no package,
 * sorted by name, each with the reason. Every entry whose name starts with `.` is passed over, and so is every entry
 * that is neither a folder nor a symbolic link to one. A folder whose clone git cannot read is listed among those that
 * are no package, with what git said.
 * The list ends at the {@link MAX_PACKAGES}th package, at most as many folders that are no package are listed, and
 * `truncated` says when there are more. Rejects with the
 * runner's 503 when git cannot be run for the list at all, and with the session's refusal by the node.
 */
export async function listPackages(git: Runner, root: string, node: NodeState): Promise<Pick<PackageList, 'packages' | 'others' | 'truncated'>> {
  const entries = (await readdir(root, { withFileTypes: true }))
    .filter((e) => !e.name.startsWith('.') && (e.isDirectory() || e.isSymbolicLink()))
    .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  const folders: Dirent[] = [];
  for (const entry of entries) {
    if (entry.isDirectory() || (await stat(join(root, entry.name)).then((info) => info.isDirectory(), () => false))) folders.push(entry);
  }
  const packages: PackageRow[] = [];
  const others: PackageList['others'] = [];
  let truncated = false;
  for (let i = 0; i < folders.length; i += AT_ONCE) {
    if (packages.length >= MAX_PACKAGES) {
      truncated = true;
      break;
    }
    const batch = await Promise.all(
      folders.slice(i, i + AT_ONCE).map(async (entry): Promise<{ row: PackageRow } | { reason: string }> => {
        try {
          const found = await folderOf(git, root, entry);
          return 'reason' in found ? found : { row: await rowOf(git, found.clone, entry.name, node, await statusOf(git, found.clone)) };
        } catch (error) {
          if ((error instanceof Refusal && error.status === 503) || isSessionRefused(error)) throw error;
          return { reason: `git could not read it: ${error instanceof Error ? error.message : String(error)}` };
        }
      }),
    );
    batch.forEach((result, j) => {
      if (packages.length >= MAX_PACKAGES) truncated = true;
      else if ('row' in result) packages.push(result.row);
      else if (others.length < MAX_PACKAGES) others.push({ folder: folders[i + j]!.name, reason: result.reason });
      else truncated = true;
    });
  }
  return { packages, others, truncated };
}
