import {
  PackagesError,
  type Artifact,
  type Busy,
  type Change,
  type CloneAnswer,
  type Commit,
  type CommitAnswer,
  type DeployAnswer,
  type Deployed,
  type FetchAnswer,
  type FileVersion,
  type PackageList,
  type PackageRow,
  type PackagesApi,
  type PackageState,
  type PullAnswer,
  type PushAnswer,
  type RemoteBody,
} from './api';
import {
  actorOf,
  artifactsOf,
  CLONE_REFUSALS,
  cloneSourceOf,
  commitBodyOf,
  deployBodyOf,
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
  REMOTE_REFUSALS,
  remoteBodyOf,
  statusCompared,
  unstageCommand,
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
  /**
   * The upstream's remote, as a fetch and a push find it (by default, at the commit the upstream names): the commit its
   * branch names, the commits it holds that the clone lacks, and whether it asks for credentials, which git has none of.
   */
  remote?: FakeRemote;
  /** The files of the index and of the disk, by path. */
  index: Record<string, string>;
  disk: Record<string, string>;
  /** The operation git left open, and the paths in conflict. */
  operation: Exclude<Busy, null | 'conflict'> | null;
  conflicts: string[];
  /** Paths on disk that are symbolic links out of the package or into its `.git` folder; a path below one is refused too. */
  escapes?: string[];
  /** Another git holds the index. */
  indexLocked?: true;
  /**
   * A pre-commit hook: it prints `says`, writes and stages each file of `writes`, by path, writes each file of
   * `unstaged` without staging it, and refuses the commit when `refuses`; with `stopsGit`, git is stopped once it has
   * committed.
   */
  hook?: { says?: string; writes?: Record<string, string>; unstaged?: Record<string, string>; refuses?: true; stopsGit?: true };
  /** A commit made in a terminal just before the next commit starts: it adds `<label>.txt`. */
  meanwhile?: string;
  /** What a terminal does just after the next commit ends: a commit that adds `<commits>.txt`, and files written and staged, by path. */
  afterwards?: { commits?: string; stages?: Record<string, string>; amends?: string };
}

/** The remote of a fake clone's upstream. */
export interface FakeRemote {
  commit: string;
  commits: FakeCommit[];
  signIn?: true;
}

/** The fake working folder's lock: it runs each work once every work given to it before has settled, first in, first out. */
export type FakeLock = <T>(work: () => Promise<T>) => Promise<T>;

/** A new lock of the fake working folder. */
export function fakeLock(): FakeLock {
  let last: Promise<unknown> = Promise.resolve();
  return (work) => {
    const done = last.then(() => work());
    last = done.catch(() => undefined);
    return done;
  };
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
  /** The author identity git gives; null when it gives none, and a commit is refused. */
  identity: { name: string; email: string } | null;
  /**
   * The node at RAIL8_ADMIN_URL: its host, and what it runs, or why it gives no answer (with `refusesToken` when it
   * refuses the UI server's token). A node that answers takes a deploy as it runs, unless `deploys` scripts it: a refusal
   * of its own of every deploy and check, or `silent`, no answer to a deploy, though it answers its check.
   */
  node:
    | { host: string; answers: true; running: FakeRunning[]; deploys?: FakeNodeRefusal | 'silent' }
    | { host: string; answers: false; error: string; refusesToken?: true };
  /** Every deploy and check the node was sent, in order: whether it was the check, its body and its actor. */
  sent: FakeSent[];
  folders: FakeFolder[];
  /** The repositories Get a package can clone. */
  sources?: FakeSource[];
}

/**
 * A repository on a git host that Get a package can clone, by its address as the rule answers it (a trailing `/`
 * aside): its commits, its default branch and the commit it names (null: an empty repository, or with `noHead` a
 * remote HEAD that names no branch it has). It fails for want of credentials, or halfway (`broken`); it is over the size
 * limit (`large`); a folder of its name appears in the working folder while git runs (`appears`); or its answer waits
 * for `held` (`waits` resolves once a clone waits for it).
 */
export interface FakeSource {
  address: string;
  commits: FakeCommit[];
  branch: string;
  head: string | null;
  noHead?: true;
  fails?: 'credentials' | 'broken';
  large?: true;
  appears?: true;
  held?: Promise<void>;
  waits?: () => void;
}

/** A refusal the fake node gives: its status, its error and its problems. */
export interface FakeNodeRefusal {
  status: number;
  error: string;
  problems?: string[];
}

/** What the fake node was sent for a deploy or its check. */
export interface FakeSent {
  check: boolean;
  body: { repository: string; commit: string; expect?: string };
  actor: string;
}

/**
 * The flows of the files `files` of a package, as a node names them by the files under `flows/`: the qualified id
 * `<package>/<file name without .yaml>` of each, sorted, with the text of its file.
 */
function flowsOf(name: string, files: Record<string, string>): Map<string, string> {
  const flows = sorted(Object.keys(files).filter((p) => /^flows\/[^/]+\.yaml$/.test(p)));
  return new Map(flows.map((p) => [`${name}/${p.slice('flows/'.length, -'.yaml'.length)}`, files[p]!]));
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

/** `folder` with the commits of its remote too, which a push and a fetch compare against. */
const withRemote = (folder: FakeFolder): FakeFolder => ({ ...folder, commits: [...folder.commits, ...(folder.remote?.commits ?? [])] });

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
export async function tagOf(text: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(text)));
  return `"sha256-${Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('')}"`;
}

/** A commit id for `what`: the SHA-1 of its JSON, as 40 hex digits. */
async function idOf(what: unknown): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-1', encoder.encode(JSON.stringify(what))));
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** A new commit of `folder` on its `HEAD`, of `files`, with `message`'s first line as its subject, a minute after its parent. */
async function commitOf(folder: FakeFolder, files: Record<string, string>, message: string, author: string): Promise<FakeCommit> {
  const parent = folder.commits.find((c) => c.id === folder.head.commit);
  const at = new Date((parent === undefined ? Date.parse('2026-11-01T09:00:00Z') : Date.parse(parent.at)) + 60_000).toISOString().replace('.000Z', '+00:00');
  const parents = parent === undefined ? [] : [parent.id];
  const made = { id: await idOf({ parents, files, message, at }), parents, subject: message.split('\n')[0]!, author, at, files };
  folder.commits.push(made);
  folder.head.commit = made.id;
  return made;
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
 * same folder rule to the same relations of the deployed state, for the browser tests and the contract suite. A commit
 * holds `lock`, the fake working folder's, from its check to its end.
 */
export function fakePackagesApi(state: FakeState, lock: FakeLock = fakeLock()): PackagesApi {
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
    return {
      ...rowOf(state, folder),
      identity: state.identity === null ? null : { ...state.identity },
      changes,
      artifacts: artifacts.artifacts,
      truncated: compared.length > MAX_LISTED || artifacts.truncated,
    };
  };

  /** The package `repository` for a fetch, a pull or a push, refused as the real half refuses it before it reads the clone. */
  const remoteOf = (repository: string, what: 'fetched' | 'pulled' | 'pushed'): FakeFolder => {
    if (state.foreignPage) throw refused(403, "only this server's own pages may call the git half through it (Sec-Fetch-Site must be same-origin)");
    if (state.git !== null) throw refused(503, state.git);
    if (!state.writable) throw refused(403, REMOTE_REFUSALS.readOnly(what));
    return packageOf(repository);
  };
  /** The remote of `folder`'s upstream once the clone is checked as the real half checks it, with the body of a pull or a push. */
  const remoteChecked = (folder: FakeFolder, body: RemoteBody | null): FakeRemote => {
    const busy = folder.operation ?? (folder.conflicts.length > 0 ? 'conflict' : null);
    if (busy !== null) throw refused(409, `the clone is busy: ${busy === 'conflict' ? 'a path is in conflict' : `a ${busy} is in progress`}; finish it in a terminal first`);
    if (folder.indexLocked) throw refused(409, 'the clone is busy: another git holds its index (.git/index.lock); try again once it ends');
    if (folder.head.branch === null) throw refused(409, REMOTE_REFUSALS.detached);
    const upstream = folder.head.commit === null ? null : folder.upstream;
    if (body !== null && (folder.head.branch !== body.branch || folder.head.commit !== body.head || (upstream?.name ?? null) !== body.upstream)) throw refused(412, REMOTE_REFUSALS.stale);
    if (upstream === null) throw refused(409, REMOTE_REFUSALS.noUpstream);
    return (folder.remote ??= { commit: upstream.commit, commits: [] });
  };
  /**
   * A fetch of `remote` into `folder`: its commits, and its branch's commit as the upstream's. The fake has no clock: the
   * fetch is dated one minute after the newest commit the folder knows, as each fake commit is a minute after its parent.
   */
  const fetched = (folder: FakeFolder, remote: FakeRemote) => {
    if (remote.signIn) throw new PackagesError(422, REMOTE_REFUSALS.failed('fetch'), undefined, "fatal: could not read Username for 'https://git.example.com': terminal prompts disabled");
    for (const commit of remote.commits.splice(0)) if (!folder.commits.some((c) => c.id === commit.id)) folder.commits.push(commit);
    const newest = Math.max(...folder.commits.map((c) => Date.parse(c.at)));
    folder.upstream = { ...folder.upstream!, commit: remote.commit, fetchedAt: new Date(newest + 60_000).toISOString() };
  };

  let cloning = false;

  return {
    async get(repository) {
      return stateOf(packageOf(repository));
    },
    async clone(address): Promise<CloneAnswer> {
      if (state.foreignPage) throw refused(403, "only this server's own pages may call the git half through it (Sec-Fetch-Site must be same-origin)");
      if (state.git !== null) throw refused(503, state.git);
      if (!state.writable) throw refused(403, CLONE_REFUSALS.readOnly);
      if (cloning) throw refused(409, CLONE_REFUSALS.another);
      cloning = true;
      try {
        const asked = cloneSourceOf(JSON.parse(JSON.stringify({ address })).address as unknown);
        if ('error' in asked) throw refused(asked.status, asked.error);
        if (!state.workspace) throw refused(404, 'no working folder is set: start the UI with RAIL8_WORKSPACE');
        const { repository } = asked;
        if (state.folders.some((f) => f.name === repository)) throw refused(409, CLONE_REFUSALS.exists);
        const source = state.sources?.find((s) => s.address === asked.address.replace(/\/$/, ''));
        if (source?.held !== undefined) {
          source.waits?.();
          await source.held;
        }
        const failed = (said: string) => new PackagesError(422, CLONE_REFUSALS.gitFailed, undefined, said);
        if (source === undefined) throw failed('remote: Repository not found.\nfatal: repository not found');
        if (source.fails === 'credentials') throw failed('fatal: could not read Username: terminal prompts disabled');
        if (source.fails === 'broken') throw failed('error: RPC failed; curl 18 transfer closed with outstanding read data remaining\nfatal: early EOF\nfatal: fetch-pack: invalid index-pack output');
        if (source.large) throw refused(422, CLONE_REFUSALS.tooLarge);
        if (source.appears) {
          state.folders.push({ name: repository, git: 'none', commits: [], head: { branch: null, commit: null }, upstream: null, index: {}, disk: {}, operation: null, conflicts: [] });
          throw refused(409, CLONE_REFUSALS.exists);
        }
        const head = source.noHead ? null : source.head;
        const files = filesOf({ commits: source.commits } as FakeFolder, head);
        const folder: FakeFolder = {
          name: repository,
          git: 'clone',
          commits: source.commits.map((c) => ({ ...c, parents: [...c.parents], files: { ...c.files } })),
          head: { branch: source.noHead ? 'main' : source.branch, commit: head },
          upstream: head === null ? null : { name: `origin/${source.branch}`, commit: head, fetchedAt: null },
          index: { ...files },
          disk: { ...files },
          operation: null,
          conflicts: [],
        };
        state.folders.push(folder);
        const reason = whyNot(folder);
        return reason === null ? { repository, package: await stateOf(folder) } : { repository, package: null, reason };
      } finally {
        cloning = false;
      }
    },
    async file(repository, rev, path): Promise<FileVersion> {
      const folder = packageOf(repository);
      revisionIn(folder, rev, 'rev');
      const found = inPackage(repository, path);
      if ('error' in found) throw refused(found.status, found.error);
      if (rev === 'work' && folder.escapes?.some((e) => found.inside === e || found.inside.startsWith(`${e}/`))) throw refused(403, `${path} leads out of the package's folder`);
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
    async commit(repository, body): Promise<CommitAnswer> {
      if (state.foreignPage) throw refused(403, "only this server's own pages may call the git half through it (Sec-Fetch-Site must be same-origin)");
      if (state.git !== null) throw refused(503, state.git);
      if (!state.writable) throw refused(403, 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was committed');
      const asked = commitBodyOf(repository, JSON.parse(JSON.stringify(body)) as unknown);
      if ('error' in asked) throw refused(asked.status, asked.error);
      const folder = packageOf(repository);
      return lock(async () => {
        const entries = statusEntries(folder);
        const busy = folder.operation ?? (folder.conflicts.length > 0 ? 'conflict' : null);
        if (busy !== null) throw refused(409, `the clone is busy: ${busy === 'conflict' ? 'a path is in conflict' : `a ${busy} is in progress`}; finish it in a terminal, then commit`);
        if (folder.indexLocked) throw refused(409, 'the clone is busy: another git holds its index (.git/index.lock); try again once it ends');
        if (folder.head.branch === null) throw refused(409, 'HEAD is detached: commit on a branch, which a terminal can switch to');
        if (state.identity === null) throw refused(422, 'git gives no author identity in this clone: set it in a terminal with git config user.name and git config user.email');
        if (folder.head.branch !== asked.branch || folder.head.commit !== asked.head) throw refused(412, 'the clone is not at the branch and HEAD you reviewed; nothing was committed');
        const changed = new Set(statusCompared(entries).flatMap((c) => [c.inside, ...(c.renamedFrom === undefined ? [] : [c.renamedFrom])]));
        const index = indexFrom(entries);
        const insides = asked.paths.map((p) => (inPackage(repository, p.path) as { inside: string }).inside);
        asked.paths.forEach((p, n) => {
          const inside = insides[n]!;
          if (!changed.has(inside)) throw refused(412, `${p.path} is no longer among the package's changes; nothing was committed`);
        });
        // a renamed change is chosen with the path it was renamed from: not on disk, unless named with a tag of its own
        const renamedFrom = new Map(statusCompared(entries).flatMap((c) => (c.renamedFrom === undefined ? [] : [[c.inside, c.renamedFrom] as const])));
        asked.paths.forEach((p, n) => {
          const from = renamedFrom.get(insides[n]!);
          if (from === undefined) return;
          const source = insides.indexOf(from);
          if (source < 0) throw refused(412, `${p.path} is renamed from ${folder.name}/${from}, which is not among the paths chosen with it; nothing was committed`);
          if (asked.paths[source]!.tag === null && from in folder.disk) throw refused(412, `${folder.name}/${from}, which ${p.path} was renamed from, is on disk: choose it too; nothing was committed`);
        });
        const tags = await Promise.all(insides.map((inside) => (inside in folder.disk ? tagOf(folder.disk[inside]!) : null)));
        asked.paths.forEach((p, n) => {
          if (tags[n] !== p.tag) throw refused(412, `${p.path} changed since you reviewed it; nothing was committed`);
        });
        insides.forEach((inside, n) => {
          if (index(inside, inside in folder.disk) === 'differs') throw refused(409, `${asked.paths[n]!.path} is staged in a terminal with other content: commit or unstage it there; nothing was committed`);
        });
        const checked = Object.fromEntries(insides.map((inside) => [inside, folder.disk[inside]]));
        if (folder.meanwhile !== undefined) {
          const label = folder.meanwhile;
          delete folder.meanwhile;
          folder.disk[`${label}.txt`] = `${label}\n`;
          folder.index[`${label}.txt`] = `${label}\n`;
          await commitOf(folder, { ...filesOf(folder, folder.head.commit), [`${label}.txt`]: `${label}\n` }, label, 'Terminal Author');
        }
        // as git commit --only does: the commit is HEAD with the chosen paths as on disk, and what the hook stages
        const parent = folder.head.commit;
        const tree = { ...filesOf(folder, parent) };
        for (const inside of insides) {
          if (checked[inside] === undefined) delete tree[inside];
          else tree[inside] = checked[inside];
        }
        const says = folder.hook?.says === undefined ? [] : [folder.hook.says];
        for (const [path, text] of Object.entries(folder.hook?.writes ?? {})) {
          folder.disk[path] = text;
          tree[path] = text;
        }
        for (const [path, text] of Object.entries(folder.hook?.unstaged ?? {})) folder.disk[path] = text;
        // the paths the request's add added, which the index did not hold
        const added = insides.filter((inside) => !(inside in folder.index) && checked[inside] !== undefined);
        const afterwards = folder.afterwards;
        delete folder.afterwards;
        const terminal = async () => {
          for (const [path, text] of Object.entries(afterwards?.stages ?? {})) {
            folder.disk[path] = text;
            folder.index[path] = text;
          }
          if (afterwards?.commits !== undefined) {
            const label = afterwards.commits;
            folder.disk[`${label}.txt`] = `${label}\n`;
            folder.index[`${label}.txt`] = `${label}\n`;
            await commitOf(folder, { ...filesOf(folder, folder.head.commit), [`${label}.txt`]: `${label}\n` }, label, 'Terminal Author');
          }
          if (afterwards?.amends !== undefined) {
            const label = afterwards.amends;
            const amended = folder.commits.find((c) => c.id === folder.head.commit)!;
            folder.disk[`${label}.txt`] = `${label}\n`;
            folder.index[`${label}.txt`] = `${label}\n`;
            folder.head.commit = amended.parents[0] ?? null;
            await commitOf(folder, { ...amended.files, [`${label}.txt`]: `${label}\n` }, label, amended.author);
          }
        };
        if (folder.hook?.refuses) {
          // what the request's add added leaves the index again, as git rm --cached takes it out: unless another git
          // staged other content meanwhile, or the bytes on disk changed since, as a hook that rewrote the file leaves it
          await terminal();
          const kept = added.filter((inside) => inside in folder.index && folder.index[inside] !== checked[inside]);
          const stuck = added.filter((inside) => !kept.includes(inside) && folder.disk[inside] !== checked[inside]);
          for (const inside of added.filter((a) => !kept.includes(a))) {
            if (stuck.includes(inside)) folder.index[inside] = checked[inside]!;
            else delete folder.index[inside];
          }
          const pathsOf = (insides: string[]) => insides.map((k) => `${folder.name}/${k}`).join(', ');
          const stay = [
            ...(kept.length === 0 ? [] : [`; ${pathsOf(kept)} stay staged, as another git staged them meanwhile`]),
            ...(stuck.length === 0 ? [] : [`; ${pathsOf(stuck)} stay staged, since git would not take them out of the index: unstage them in a terminal with ${unstageCommand(stuck, parent === null)}`]),
          ].join('');
          const rm = stuck.length === 0 ? [] : [`error: the following files have staged content different from both the\nfile and the HEAD:\n${stuck.map((k) => `    ${k}\n`).join('')}(use -f to force removal)`];
          throw new PackagesError(422, `git commit failed, as git and the hooks say; nothing was committed${stay}`, undefined, [...says, ...rm].join('\n'));
        }
        const made = await commitOf(folder, tree, asked.message, state.identity.name);
        for (const inside of insides) {
          if (checked[inside] === undefined) delete folder.index[inside];
          else folder.index[inside] = checked[inside];
        }
        if (folder.hook?.stopsGit) {
          throw new PackagesError(422, `git commit failed, as git and the hooks say; HEAD moved to ${made.id.slice(0, 7)} meanwhile, so git may have committed: the package's history shows it`, undefined, says.join('\n'));
        }
        await terminal();
        if (folder.head.commit !== made.id) {
          return {
            commit: null,
            output: [...says, `[${folder.head.branch} ${made.id.slice(0, 7)}] ${made.subject}`].join('\n'),
            package: await stateOf(folder),
            problem: `git committed ${made.id.slice(0, 7)}, but HEAD moved on at once to ${folder.head.commit!.slice(0, 7)}, as when a terminal commits or amends right after, so the commit HEAD shows is not the one git made`,
          };
        }
        const before = filesOf(folder, parent);
        const chosen = new Set(insides);
        const pathOf = (inside: string) => `${folder.name}/${inside}`;
        const answer: CommitAnswer = {
          commit: {
            id: made.id,
            parents: [...made.parents],
            subject: made.subject,
            author: made.author,
            at: made.at,
            staged: sorted(Object.keys({ ...before, ...tree }).filter((p) => !chosen.has(p) && before[p] !== tree[p])).map(pathOf),
            rewritten: sorted(insides.filter((inside) => tree[inside] !== checked[inside])).map(pathOf),
            changedOnDisk: sorted(insides.filter((inside) => folder.disk[inside] !== tree[inside])).map(pathOf),
          },
          output: [...says, `[${folder.head.branch} ${made.id.slice(0, 7)}] ${made.subject}`].join('\n'),
          package: await stateOf(folder),
        };
        return answer;
      });
    },
    async deploy(repository, body): Promise<DeployAnswer> {
      if (state.foreignPage) throw refused(403, "only this server's own pages may call the git half through it (Sec-Fetch-Site must be same-origin)");
      if (state.git !== null) throw refused(503, state.git);
      const asked = deployBodyOf(JSON.parse(JSON.stringify(body)) as unknown);
      if ('error' in asked) throw refused(asked.status, asked.error);
      if (!asked.check && !state.writable) throw refused(403, 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was deployed');
      const folder = packageOf(repository);
      if (!folder.commits.some((c) => c.id === asked.commit)) throw refused(404, `the clone holds no commit ${asked.commit}`);
      const node = state.node;
      const nothing = asked.check ? 'the check changes nothing' : 'nothing was deployed';
      if (!node.answers) throw refused(502, node.refusesToken ? `the node at ${node.host} refuses the UI server's token (401); ${nothing}` : `${node.error}; ${nothing}`);
      state.sent.push({
        check: asked.check,
        body: asked.check ? { repository, commit: asked.commit } : { repository, commit: asked.commit, expect: asked.expect! },
        actor: actorOf(state.identity),
      });
      if (typeof node.deploys === 'object') {
        const { status, error, problems } = node.deploys;
        throw new PackagesError(status, status === 412 ? `the node's commit changed since the check: ${error}; ${nothing}` : error, problems);
      }
      const record = node.running.find((r) => r.repository === repository);
      const replaces = record?.commit ?? 'none';
      if (!asked.check && asked.expect !== replaces) {
        throw new PackagesError(412, `the node's commit changed since the check: the pool records ${replaces}, not ${asked.expect!}; ${nothing}`);
      }
      if (!asked.check && node.deploys === 'silent') {
        throw refused(504, `the node at ${node.host} gave no answer within 5 min, so the outcome of the deploy is unknown: read what it runs before you try again`);
      }
      const files = filesOf(folder, asked.commit);
      const shown = manifestOf(files['package.yaml'] ?? '').package ?? { name: repository, version: '' };
      const before = record === undefined ? new Map<string, string>() : flowsOf(shown.name, filesOf(folder, record.commit));
      const after = flowsOf(shown.name, files);
      if (!asked.check) {
        node.running = [
          ...node.running.filter((r) => r.repository !== repository),
          { repository, commit: asked.commit, state: 'RUNNING', problems: [], deployedAt: folder.commits.find((c) => c.id === asked.commit)!.at },
        ];
      }
      return {
        deploy: {
          package: shown.name,
          version: shown.version,
          repository,
          commit: asked.commit,
          flows: [...after.keys()],
          added: [...after.keys()].filter((f) => !before.has(f)),
          swapped: [...after.keys()].filter((f) => before.has(f) && before.get(f) !== after.get(f)),
          removed: [...before.keys()].filter((f) => !after.has(f)),
        },
        replaces,
        package: await stateOf(folder),
      };
    },
    async fetch(repository): Promise<FetchAnswer> {
      const folder = remoteOf(repository, 'fetched');
      const remote = remoteChecked(folder, null);
      fetched(folder, remote);
      return { package: await stateOf(folder) };
    },
    async pull(repository, body): Promise<PullAnswer> {
      const folder = remoteOf(repository, 'pulled');
      const asked = remoteBodyOf(JSON.parse(JSON.stringify(body)) as unknown);
      if ('error' in asked) throw refused(asked.status, asked.error);
      fetched(folder, remoteChecked(folder, asked));
      return lock(async () => {
        remoteChecked(folder, asked);
        const target = folder.upstream!.commit;
        if (reach(folder, asked.head).has(target)) return { head: asked.head, changed: [], package: await stateOf(folder) };
        if (!reach(folder, target).has(asked.head)) throw refused(409, REMOTE_REFUSALS.diverged);
        const [older, newer] = [filesOf(folder, asked.head), filesOf(folder, target)];
        const paths = sorted(new Set([...Object.keys(older), ...Object.keys(newer)])).filter((p) => older[p] !== newer[p]);
        // as git merge --ff-only --no-overwrite-ignore refuses: a path it changes that differs from HEAD on disk or in the index
        const changedHere = paths.filter((p) => p in older && (folder.disk[p] !== older[p] || folder.index[p] !== older[p]));
        const inTheWay = paths.filter((p) => !(p in older) && p in folder.disk);
        if (changedHere.length > 0 || inTheWay.length > 0) {
          const said = [
            ...(changedHere.length === 0 ? [] : ['error: Your local changes to the following files would be overwritten by merge:', ...changedHere.map((p) => `\t${p}`), 'Please commit your changes or stash them before you merge.']),
            ...(inTheWay.length === 0 ? [] : ['error: The following untracked working tree files would be overwritten by merge:', ...inTheWay.map((p) => `\t${p}`), 'Please move or remove them before you merge.']),
            'Aborting',
          ];
          throw new PackagesError(409, REMOTE_REFUSALS.overwrites, undefined, said.join('\n'));
        }
        for (const p of paths) {
          if (p in newer) {
            folder.disk[p] = newer[p]!;
            folder.index[p] = newer[p]!;
          } else {
            delete folder.disk[p];
            delete folder.index[p];
          }
        }
        folder.head.commit = target;
        return { head: target, changed: paths.map((p) => `${folder.name}/${p}`), package: await stateOf(folder) };
      });
    },
    async push(repository, body): Promise<PushAnswer> {
      const folder = remoteOf(repository, 'pushed');
      const asked = remoteBodyOf(JSON.parse(JSON.stringify(body)) as unknown);
      if ('error' in asked) throw refused(asked.status, asked.error);
      const remote = remoteChecked(folder, asked);
      const upstream = folder.upstream!;
      const ref = `refs/heads/${upstream.name.slice(upstream.name.indexOf('/') + 1)}`;
      if (remote.signIn) throw new PackagesError(422, REMOTE_REFUSALS.failed('push'), undefined, "fatal: could not read Username for 'https://git.example.com': terminal prompts disabled");
      if (!reach(withRemote(folder), asked.head).has(remote.commit)) {
        const why = remote.commit === upstream.commit ? 'non-fast-forward' : 'fetch first';
        throw new PackagesError(409, REMOTE_REFUSALS.rejected, undefined, `To https://git.example.com/${folder.name}.git\n!\t${asked.head}:${ref}\t[rejected] (${why})\nDone\nerror: failed to push some refs to 'https://git.example.com/${folder.name}.git'`);
      }
      const from = upstream.commit;
      remote.commit = asked.head;
      upstream.commit = asked.head;
      return { pushed: { ref, from, to: asked.head }, package: await stateOf(folder) };
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
