import { PackagesError, type Busy, type Commit, type Deployed, type PackageList, type PackageRow, type PackagesApi } from './api';
import { isRepositoryName, isSaveTemporary, manifestOf, MAX_PACKAGES, REASONS } from './rules';

/** A commit of a fake clone: its fields as git gives them, and every file it holds, by path. */
export interface FakeCommit extends Omit<Commit, 'deployed'> {
  files: Record<string, string>;
}

/** One folder directly in the fake working folder. */
export interface FakeFolder {
  name: string;
  /** A symbolic link to a folder, which is no package. */
  link?: true;
  /**
   * What its `.git` is: the folder of a clone of its own, nothing, a file (a worktree or a submodule), or a folder that
   * is no repository, which git passes over to take the folder for one of the repository the working folder lies in.
   */
  git: 'clone' | 'none' | 'file' | 'no-repository';
  /** Every commit the clone holds. */
  commits: FakeCommit[];
  /** The branch `HEAD` is on (null: detached) and the commit it names (null: none yet). */
  head: { branch: string | null; commit: string | null };
  /** The branch's upstream: its name, the commit its remote-tracking branch names, and the time of the last fetch. */
  upstream: { name: string; commit: string; fetchedAt: string | null } | null;
  /** The files of the index and of the disk, by path. */
  index: Record<string, string>;
  disk: Record<string, string>;
  /** The operation git left open, and the paths in conflict. */
  operation: Exclude<Busy, null | 'conflict'> | null;
  conflicts: string[];
}

/** What the fake node runs of one repository. */
export interface FakeRunning {
  repository: string;
  commit: string;
  state: 'RUNNING' | 'FAILED';
  problems: string[];
  deployedAt: string;
}

/** The whole world the fake git half serves. */
export interface FakeState {
  /** False when no working folder is set. */
  workspace: boolean;
  writable: boolean;
  /** True when the calls come from a page of another site, which every route refuses. */
  foreignPage: boolean;
  /** Why git cannot serve, as every route answers it with 503; null when it serves. */
  git: string | null;
  /** The node at RAIL8_ADMIN_URL: its host, and what it runs, or why it gives no answer. */
  node: { host: string; answers: true; running: FakeRunning[] } | { host: string; answers: false; error: string };
  folders: FakeFolder[];
}

/** The commits `id` reaches through its parents, itself included. */
function reach(folder: FakeFolder, id: string): Set<string> {
  const byId = new Map(folder.commits.map((c) => [c.id, c]));
  const reached = new Set<string>();
  const next = [id];
  while (next.length > 0) {
    const at = next.pop()!;
    if (reached.has(at)) continue;
    reached.add(at);
    next.push(...(byId.get(at)?.parents ?? []));
  }
  return reached;
}

/** How many commits `from` reaches that `to` does not. */
function beyond(folder: FakeFolder, from: string, to: string): number {
  const other = reach(folder, to);
  return [...reach(folder, from)].filter((id) => !other.has(id)).length;
}

function whyNot(folder: FakeFolder): string | null {
  if (!isRepositoryName(folder.name)) return REASONS.name;
  if (folder.link) return REASONS.link;
  if (folder.git === 'none') return REASONS.noClone;
  if (folder.git === 'file') return REASONS.worktree;
  if (folder.git === 'no-repository') return REASONS.nested;
  if (!('package.yaml' in folder.disk)) return REASONS.noManifest;
  return null;
}

function deployedOf(state: FakeState, folder: FakeFolder): Deployed {
  const node = state.node;
  if (!node.answers) return { node: 'unreachable', host: node.host, error: node.error };
  const running = node.running.find((r) => r.repository === folder.name);
  if (running === undefined) return { node: 'answered', host: node.host, relation: 'none' };
  const { commit, state: runs, problems, deployedAt } = running;
  const shown = { node: 'answered' as const, host: node.host, commit, state: runs, problems: [...problems], deployedAt };
  const head = folder.head.commit;
  if (commit === head) return { ...shown, relation: 'deployed' };
  if (head === null || !folder.commits.some((c) => c.id === commit)) return { ...shown, relation: 'other' };
  if (reach(folder, head).has(commit)) return { ...shown, relation: 'behind', commits: beyond(folder, head, commit) };
  if (reach(folder, commit).has(head)) return { ...shown, relation: 'ahead', commits: beyond(folder, commit, head) };
  return { ...shown, relation: 'other' };
}

function rowOf(state: FakeState, folder: FakeFolder): PackageRow {
  const commit = folder.commits.find((c) => c.id === folder.head.commit);
  const committed = commit?.files ?? {};
  const paths = new Set([...Object.keys(committed), ...Object.keys(folder.index), ...Object.keys(folder.disk), ...folder.conflicts]);
  const changed = [...paths].filter(
    (p) => !isSaveTemporary(p) && (folder.conflicts.includes(p) || committed[p] !== folder.index[p] || folder.index[p] !== folder.disk[p]),
  ).length;
  const upstream = folder.head.branch === null || folder.upstream === null || folder.head.commit === null ? null : folder.upstream;
  return {
    repository: folder.name,
    ...manifestOf(folder.disk['package.yaml']!),
    branch: folder.head.branch,
    head: commit === undefined ? null : { id: commit.id, parents: [...commit.parents], subject: commit.subject, author: commit.author, at: commit.at },
    upstream:
      upstream === null
        ? null
        : { name: upstream.name, ahead: beyond(folder, folder.head.commit!, upstream.commit), behind: beyond(folder, upstream.commit, folder.head.commit!), fetchedAt: upstream.fetchedAt },
    busy: folder.operation ?? (folder.conflicts.length > 0 ? 'conflict' : null),
    changed,
    deployed: deployedOf(state, folder),
  };
}

/**
 * The git half in memory, over `state`, which a test may change between calls: the rules of the real one, from the
 * same folder rule to the same relations of the deployed state, for the browser tests and the contract suite.
 */
export function fakePackagesApi(state: FakeState): PackagesApi {
  return {
    async list(): Promise<PackageList> {
      if (state.foreignPage) throw new PackagesError(403, "only this server's own pages may call the git half through it (Sec-Fetch-Site must be same-origin)");
      if (state.git !== null) throw new PackagesError(503, state.git);
      const shown = { writable: state.writable, node: state.node.host };
      if (!state.workspace) return { configured: false, ...shown, packages: [], others: [], truncated: false };
      const folders = state.folders.filter((f) => !f.name.startsWith('.')).sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
      const packages: PackageRow[] = [];
      const others: PackageList['others'] = [];
      let truncated = false;
      for (const folder of folders) {
        if (packages.length >= MAX_PACKAGES) {
          truncated = true;
          break;
        }
        const reason = whyNot(folder);
        if (reason === null) packages.push(rowOf(state, folder));
        else if (others.length < MAX_PACKAGES) others.push({ folder: folder.name, reason });
        else truncated = true;
      }
      return { configured: true, ...shown, packages, others, truncated };
    },
  };
}
