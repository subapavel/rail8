import type { Dirent } from 'node:fs';
import { lstat, readdir, readFile, realpath, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { MAX_FILE_BYTES, Refusal } from '../../../server/workingFolder.ts';
import type { Busy, Commit, Deployed, PackageList, PackageRow } from '../api.ts';
import { isRepositoryName, isSaveTemporary, manifestOf, MAX_PACKAGES, REASONS } from '../rules.ts';
import { holds, parseStatus, read, type Status } from './git.ts';
import type { NodeRead } from './node.ts';
import type { Runner } from './runner.ts';

/** How many folders are read at once: each runs its git reads one after the other. */
const AT_ONCE = 4;

/** What the rows need of the node: what it runs, read once and only when a row needs it, and its host. */
export interface NodeState {
  host: string;
  read: () => Promise<NodeRead>;
}

const missing = (error: unknown) => (error as NodeJS.ErrnoException).code === 'ENOENT';
const exists = (path: string) => lstat(path).then(() => true, (error: unknown) => (missing(error) ? false : Promise.reject(error)));

/**
 * Whether the folder `name` directly in the real working folder `root` is a package, and why not when it is no
 * package. A package's name is a repository name, its `.git` is a folder, `git rev-parse --show-toplevel` answers the
 * folder itself, and it holds `package.yaml` at its root. A symbolic link is no package: a package is a folder itself.
 */
async function folderOf(git: Runner, root: string, entry: Dirent): Promise<{ clone: string } | { reason: string }> {
  if (!isRepositoryName(entry.name)) return { reason: REASONS.name };
  const folder = join(root, entry.name);
  if (entry.isSymbolicLink()) return { reason: REASONS.link };
  const dotGit = await lstat(join(folder, '.git')).catch((error: unknown) => (missing(error) ? null : Promise.reject(error)));
  if (dotGit === null) return { reason: REASONS.noClone };
  if (!dotGit.isDirectory()) return { reason: REASONS.worktree };
  const top = (await read(git, folder, ['rev-parse', '--show-toplevel'])).replace(/\r?\n$/, '');
  if ((await realpath(top).catch(() => top)) !== folder) return { reason: REASONS.nested };
  const manifest = await lstat(join(folder, 'package.yaml')).catch(() => null);
  if (manifest === null || !manifest.isFile()) return { reason: REASONS.noManifest };
  return { clone: folder };
}

/** The commit `oid` of the clone at `clone`, from `git log -1 -z`. */
async function commitOf(git: Runner, clone: string, oid: string): Promise<Commit> {
  const said = await read(git, clone, ['log', '-1', '-z', '--format=%H%x00%P%x00%an%x00%aI%x00%s', '--end-of-options', oid, '--']);
  const [id = oid, parents = '', author = '', at = '', subject = ''] = said.split('\0');
  return { id, parents: parents === '' ? [] : parents.split(' '), subject, author, at };
}

/** The operation git left open in the clone, from the state files in its `.git`; else a path in conflict; else none. */
async function busyOf(clone: string, status: Status): Promise<Busy> {
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

/**
 * What the node runs of `repository`, related to the clone's `head`: the relation by `cat-file -e`,
 * `merge-base --is-ancestor` in both directions and `rev-list --count`, named by the node's host.
 */
async function deployedOf(git: Runner, clone: string, repository: string, head: Commit | null, node: NodeState): Promise<Deployed> {
  const answer = await node.read();
  if (!answer.answered) return { node: 'unreachable', host: node.host, error: answer.error };
  const running = answer.running.find((r) => r.repository === repository);
  if (running === undefined) return { node: 'answered', host: node.host, relation: 'none' };
  const { commit, state, problems, deployedAt } = running;
  const shown = { node: 'answered', host: node.host, commit, state, problems, deployedAt } as const;
  if (head !== null && commit === head.id) return { ...shown, relation: 'deployed' };
  if (head === null || !/^[0-9a-f]{40}$/.test(commit)) return { ...shown, relation: 'other' };
  // cat-file -e exits with 1, or 128 for an object it cannot name, when the clone lacks the commit
  if (!(await holds(git, clone, ['cat-file', '-e', '--end-of-options', `${commit}^{commit}`], [1, 128]))) return { ...shown, relation: 'other' };
  const isAncestor = (older: string, newer: string) => holds(git, clone, ['merge-base', '--is-ancestor', '--end-of-options', older, newer], [1]);
  const count = async (range: string) => Number((await read(git, clone, ['rev-list', '--count', '--end-of-options', range, '--'])).trim());
  if (await isAncestor(commit, head.id)) return { ...shown, relation: 'behind', commits: await count(`${commit}..${head.id}`) };
  if (await isAncestor(head.id, commit)) return { ...shown, relation: 'ahead', commits: await count(`${head.id}..${commit}`) };
  return { ...shown, relation: 'other' };
}

/** The row of the package whose clone is at the real path `clone`. */
async function rowOf(git: Runner, clone: string, repository: string, node: NodeState): Promise<PackageRow> {
  const status = parseStatus(await read(git, clone, ['status', '--porcelain=v2', '--branch', '-z', '--untracked-files=all', '--', ':(literal).']));
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
 * sorted by name, each with the reason. Hidden folders and files are passed over, and so is every file that is no
 * folder or link. A folder whose clone git cannot read is listed among those that are no package, with what git said.
 * The list ends at the {@link MAX_PACKAGES}th package, at most as many folders that are no package are listed, and
 * `truncated` says when there are more. Rejects with the
 * runner's 503 when git cannot be run for the list at all.
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
          return 'reason' in found ? found : { row: await rowOf(git, found.clone, entry.name, node) };
        } catch (error) {
          if (error instanceof Refusal && error.status === 503) throw error;
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
