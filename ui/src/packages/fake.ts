import { PackagesError, type Artifact, type Busy, type Change, type Commit, type Deployed, type FileVersion, type PackageList, type PackageRow, type PackagesApi, type PackageState } from './api';
import {
  artifactsOf,
  indexFrom,
  inPackage,
  isCommitId,
  isRepositoryName,
  isRevision,
  isSaveTemporary,
  kindOf,
  LOG_MAX,
  LOG_PAGE,
  manifestOf,
  MAX_LISTED,
  MAX_PACKAGES,
  oncePerPath,
  READ_LIMIT,
  readForKind,
  REASONS,
  statusCompared,
  statusOf,
  walked,
  type Compared,
  type StatusEntry,
} from './rules';

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
  /** Paths on disk that are symbolic links to a file outside the package. */
  escapes?: string[];
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
  const changed = statusEntries(folder).filter((e) => !isSaveTemporary(e.path)).length;
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

const encoder = new TextEncoder();
const sizeOf = (text: string) => encoder.encode(text).length;
const sorted = (paths: Iterable<string>) => [...paths].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));

/** The git half's tag of the bytes of `text`: the `ETag` of their SHA-256, as the real one gives it. */
async function tagOf(text: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(text)));
  return `"sha256-${Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('')}"`;
}

/** The files of the commit `id` of `folder`, or none for null. */
function filesOf(folder: FakeFolder, id: string | null): Record<string, string> {
  return id === null ? {} : (folder.commits.find((c) => c.id === id)?.files ?? {});
}

/** How alike two texts are, as git's rename detection scores it roughly: the bytes of the lines they share against the larger one's size. */
function similarity(a: string, b: string): number {
  const lines = new Map<string, number>();
  for (const line of a.split(/(?<=\n)/)) lines.set(line, (lines.get(line) ?? 0) + 1);
  let shared = 0;
  for (const line of b.split(/(?<=\n)/)) {
    const left = lines.get(line) ?? 0;
    if (left > 0) {
      lines.set(line, left - 1);
      shared += sizeOf(line);
    }
  }
  return shared / Math.max(sizeOf(a), sizeOf(b), 1);
}

/**
 * What two versions `older` and `newer` differ in, as `diff -M` names it: each path added, deleted or modified, and a
 * path added that is at least half alike a deleted one as a rename of it, the most alike first.
 */
function compare(older: Record<string, string | undefined>, newer: Record<string, string | undefined>): Compared[] {
  const paths = sorted(new Set([...Object.keys(older), ...Object.keys(newer)]));
  const added = paths.filter((p) => older[p] === undefined && newer[p] !== undefined);
  const deleted = paths.filter((p) => older[p] !== undefined && newer[p] === undefined);
  const pairs = added
    .flatMap((to) => deleted.map((from) => ({ to, from, score: similarity(older[from]!, newer[to]!) })))
    .filter((p) => p.score >= 0.5)
    .sort((a, b) => b.score - a.score);
  const renamed = new Map<string, string>();
  const sources = new Set<string>();
  for (const { to, from } of pairs) {
    if (renamed.has(to) || sources.has(from)) continue;
    renamed.set(to, from);
    sources.add(from);
  }
  return paths.flatMap((p): Compared[] => {
    if (sources.has(p) || older[p] === newer[p]) return [];
    if (renamed.has(p)) return [{ inside: p, older: false, renamedFrom: renamed.get(p)! }];
    return [{ inside: p, older: older[p] !== undefined }];
  });
}

/** What `git status --porcelain=v2` lists of `folder`: each path whose index differs from `HEAD` or from the disk, and each untracked one. */
function statusEntries(folder: FakeFolder): StatusEntry[] {
  const head = filesOf(folder, folder.head.commit);
  const { index, disk } = folder;
  const staged = compare(head, index);
  const y = (p: string) => (!(p in disk) ? 'D' : disk[p] !== index[p] ? 'M' : '.');
  const entries: StatusEntry[] = [];
  for (const c of staged) {
    if (folder.conflicts.includes(c.inside)) continue;
    if (c.renamedFrom !== undefined) entries.push({ kind: 'renamed', xy: `R${y(c.inside)}`, path: c.inside, from: c.renamedFrom });
    else entries.push({ kind: 'ordinary', xy: `${!c.older ? 'A' : !(c.inside in index) ? 'D' : 'M'}${c.inside in index ? y(c.inside) : '.'}`, path: c.inside });
  }
  const listed = new Set(staged.map((c) => c.inside));
  for (const p of sorted(Object.keys(index))) if (!listed.has(p) && !folder.conflicts.includes(p) && y(p) !== '.') entries.push({ kind: 'ordinary', xy: `.${y(p)}`, path: p });
  for (const p of folder.conflicts) entries.push({ kind: 'unmerged', xy: 'UU', path: p });
  for (const p of sorted(Object.keys(disk))) if (!(p in index)) entries.push({ kind: 'untracked', xy: '??', path: p });
  return entries;
}

/** `text`, the file at `inside` in its newer version, else in its older one, when it is read to tell its kind; else null. */
const kindText = (inside: string, text: string | undefined) =>
  text === undefined || sizeOf(text) > READ_LIMIT || !readForKind(inside.slice(inside.lastIndexOf('/') + 1)) ? null : text;

/** The changes of `compared` against the files on disk of `folder`, as the real half builds them. */
async function workChanges(folder: FakeFolder, compared: readonly Compared[], entries: readonly StatusEntry[], older: Record<string, string>): Promise<Change[]> {
  const index = indexFrom(entries);
  return Promise.all(
    compared.slice(0, MAX_LISTED).map(async (c) => {
      const path = `${folder.name}/${c.inside}`;
      const text = folder.disk[c.inside];
      const onDisk = text !== undefined;
      return {
        path,
        kind: kindOf(path, kindText(c.inside, text ?? older[c.renamedFrom ?? c.inside])).kind,
        status: statusOf(c, onDisk),
        ...(c.renamedFrom === undefined ? {} : { renamedFrom: `${folder.name}/${c.renamedFrom}` }),
        index: index(c.inside, onDisk),
        tag: onDisk ? await tagOf(text) : null,
        ...(onDisk && sizeOf(text) > READ_LIMIT ? { large: true as const } : {}),
      };
    }),
  );
}

/** The artifacts of `folder` among `files`, the files of a revision, each with its status in `changes`. */
function artifactsIn(folder: FakeFolder, files: Record<string, string>, changes: readonly Change[]): Promise<{ artifacts: Artifact[]; truncated: boolean }> {
  const seen = sorted(Object.keys(files)).filter(walked);
  const typed = seen.filter((p) => readForKind(p.slice(p.lastIndexOf('/') + 1)));
  const found = typed.slice(0, MAX_LISTED).map((inside) => ({ inside, text: sizeOf(files[inside]!) > READ_LIMIT ? null : files[inside]! }));
  const present = async (insides: string[]) => new Set(insides.filter((p) => seen.includes(p)));
  return artifactsOf(folder.name, found, present, new Map(changes.map((c) => [c.path, c.status]))).then((artifacts) => ({ artifacts, truncated: typed.length > MAX_LISTED }));
}

/**
 * The git half in memory, over `state`, which a test may change between calls: the rules of the real one, from the
 * same folder rule to the same relations of the deployed state, for the browser tests and the contract suite.
 */
export function fakePackagesApi(state: FakeState): PackagesApi {
  const refused = (status: number, error: string) => new PackagesError(status, error);
  /** The package `repository`, refused as the real half refuses it. */
  const packageOf = (repository: string): FakeFolder => {
    if (state.foreignPage) throw refused(403, "only this server's own pages may call the git half through it (Sec-Fetch-Site must be same-origin)");
    if (state.git !== null) throw refused(503, state.git);
    if (!state.workspace) throw refused(404, 'no working folder is set: start the UI with RAIL8_WORKSPACE');
    if (!isRepositoryName(repository)) throw refused(400, `${JSON.stringify(repository)} is no repository name`);
    const folder = state.folders.find((f) => f.name === repository);
    if (folder === undefined) throw refused(404, `no package ${repository} in the working folder`);
    const reason = whyNot(folder);
    if (reason !== null) throw refused(404, `${repository} is no package: ${reason}`);
    return folder;
  };
  const revisionIn = (folder: FakeFolder, rev: string, name: string, commitOnly = false) => {
    if (commitOnly ? !isCommitId(rev) : !isRevision(rev)) throw refused(400, `${name} ${JSON.stringify(rev)} is no revision: ${commitOnly ? '' : 'work, or '}a commit of 40 hex digits`);
    if (rev !== 'work' && !folder.commits.some((c) => c.id === rev)) throw refused(404, `the clone holds no commit ${rev}`);
  };
  const stateOf = async (folder: FakeFolder): Promise<PackageState> => {
    const entries = statusEntries(folder);
    const compared = statusCompared(entries);
    const changes = await workChanges(folder, compared, entries, filesOf(folder, folder.head.commit));
    const artifacts = await artifactsIn(folder, folder.disk, changes);
    return { ...rowOf(state, folder), changes, artifacts: artifacts.artifacts, truncated: compared.length > MAX_LISTED || artifacts.truncated };
  };

  return {
    async get(repository) {
      return stateOf(packageOf(repository));
    },
    async file(repository, rev, path): Promise<FileVersion> {
      const folder = packageOf(repository);
      revisionIn(folder, rev, 'rev');
      const found = inPackage(repository, path);
      if ('error' in found) throw refused(found.status, found.error);
      if (rev === 'work' && folder.escapes?.includes(found.inside)) throw refused(403, `${path} leads out of the package's folder`);
      const text = rev === 'work' ? folder.disk[found.inside] : filesOf(folder, rev)[found.inside];
      if (text === undefined) throw refused(404, rev === 'work' ? `no file ${path} on disk` : `no file ${path} in the commit ${rev}`);
      if (sizeOf(text) > READ_LIMIT) throw refused(413, `${path} is over 1 MiB, the read limit`);
      return { ...(text.includes('\0') ? { binary: true as const, size: sizeOf(text) } : { text }), etag: await tagOf(text) };
    },
    async changes(repository, from, to) {
      const folder = packageOf(repository);
      revisionIn(folder, from, 'from', true);
      revisionIn(folder, to, 'to');
      const older = filesOf(folder, from);
      if (to === 'work') {
        const entries = statusEntries(folder);
        const tracked = Object.fromEntries(Object.keys(folder.index).map((p) => [p, folder.disk[p]]));
        const compared = oncePerPath([
          ...compare(older, tracked),
          ...entries.filter((e) => e.kind === 'untracked').map((e) => ({ inside: e.path, older: false })),
        ]);
        return { changes: await workChanges(folder, compared, entries, older), truncated: compared.length > MAX_LISTED };
      }
      const newer = filesOf(folder, to);
      const compared = oncePerPath(compare(older, newer));
      const changes = compared.slice(0, MAX_LISTED).map((c): Change => {
        const path = `${folder.name}/${c.inside}`;
        const sides = [older[c.renamedFrom ?? c.inside], newer[c.inside]];
        return {
          path,
          kind: kindOf(path, kindText(c.inside, newer[c.inside] ?? older[c.inside])).kind,
          status: statusOf(c, newer[c.inside] !== undefined),
          ...(c.renamedFrom === undefined ? {} : { renamedFrom: `${folder.name}/${c.renamedFrom}` }),
          index: 'clean',
          tag: null,
          ...(sides.some((t) => t !== undefined && sizeOf(t) > READ_LIMIT) ? { large: true as const } : {}),
        };
      });
      return { changes, truncated: compared.length > MAX_LISTED };
    },
    async artifacts(repository, rev) {
      const folder = packageOf(repository);
      revisionIn(folder, rev, 'rev');
      if (rev !== 'work') return artifactsIn(folder, filesOf(folder, rev), []);
      return { artifacts: (await stateOf(folder)).artifacts, truncated: false };
    },
    async log(repository, page = {}) {
      const folder = packageOf(repository);
      const limit = page.limit ?? LOG_PAGE;
      if (!Number.isInteger(limit) || limit < 1 || limit > LOG_MAX) throw refused(400, `limit ${JSON.stringify(String(limit))} is no number from 1 to ${LOG_MAX}`);
      if (page.before !== undefined) revisionIn(folder, page.before, 'before', true);
      const running = state.node.answers ? state.node.running.find((r) => r.repository === folder.name)?.commit : undefined;
      const items: Commit[] = [];
      let at: string | undefined = page.before ?? folder.head.commit ?? undefined;
      while (at !== undefined && items.length <= limit) {
        const commit = folder.commits.find((c) => c.id === at)!;
        const { id, subject, author, at: when } = commit;
        items.push({ id, parents: [...commit.parents], subject, author, at: when, ...(id === running ? { deployed: true as const } : {}) });
        at = commit.parents[0];
      }
      return { items: items.slice(0, limit), next: items[limit]?.id ?? null };
    },
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
