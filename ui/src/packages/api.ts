import type { Artifact, ChangeStatus, Kind } from '../artifacts';
import { uiFetch } from '../session';

export type { Artifact, ChangeStatus, Kind };

/** A commit of a package's clone: its id (40 hex digits), its parents' ids, its subject, its author's name and its author date. */
export interface Commit {
  id: string;
  parents: string[];
  subject: string;
  author: string;
  /** The author date, always as ISO 8601 with its offset, such as `2026-11-24T05:00:45+00:00`: UTC is `+00:00`, never `Z`. */
  at: string;
  deployed?: true;
}

/**
 * What the node at RAIL8_ADMIN_URL runs of a package, named by that node's host: no answer from the node (`error`
 * says why), nothing of the package's repository, or the commit it runs with its relation to the clone's `HEAD`:
 *
 * - `deployed`: it runs `HEAD`;
 * - `behind`: it runs an ancestor of `HEAD`, `commits` back;
 * - `ahead`: `HEAD` is an ancestor of what it runs, `commits` back;
 * - `other`: the commit is not in `HEAD`'s history, or the clone lacks it.
 */
export type Deployed =
  | { node: 'unreachable'; host: string; error: string }
  | { node: 'answered'; host: string; relation: 'none' }
  | {
      node: 'answered';
      host: string;
      relation: 'deployed' | 'behind' | 'ahead' | 'other';
      commit: string;
      commits?: number;
      state: 'RUNNING' | 'FAILED';
      problems: string[];
      deployedAt: string;
    };

/** An operation git left open in a clone, or a path in conflict; null when there is none. */
export type Busy = null | 'merge' | 'rebase' | 'cherry-pick' | 'revert' | 'bisect' | 'conflict';

/** One package of the working folder, as the Packages screen lists it. */
export interface PackageRow {
  /** The clone's folder name: what a deploy and a pin name. */
  repository: string;
  /** The name and version `package.yaml` gives; null when it cannot be read, and `problem` says why. */
  package: { name: string; version: string } | null;
  problem?: string;
  /** The branch `HEAD` is on; null for a detached `HEAD`. */
  branch: string | null;
  /** The commit `HEAD` names; null before the first commit. */
  head: Commit | null;
  /** The branch's upstream with the commits ahead of it and behind it, as of the last fetch, and that fetch's time (null: never). */
  upstream: { name: string; ahead: number; behind: number; fetchedAt: string | null } | null;
  busy: Busy;
  /** The paths of the package's folder that differ from `HEAD`, untracked ones included. */
  changed: number;
  deployed: Deployed;
}

/** What `GET /git/packages` answers. */
export interface PackageList {
  /** False when no working folder is set; the lists are then empty. */
  configured: boolean;
  /** False when the server takes no write, as under RAIL8_READ_ONLY=true. */
  writable: boolean;
  /** The node whose deployed state the rows show: the host of RAIL8_ADMIN_URL. */
  node: string;
  /** The packages, sorted by repository. */
  packages: PackageRow[];
  /** The folders of the working folder that are no package, sorted by name, each with the reason. */
  others: { folder: string; reason: string }[];
  /** True when the working folder holds more packages, or more folders that are no package, than are listed. */
  truncated: boolean;
}

/**
 * One file that differs between two versions of a package: its path relative to the working folder, with `/`, as the
 * editors' routes name it; its kind; how it differs, and the path it was renamed from; whether the index holds
 * `HEAD`'s version (`clean`), the version on disk (`staged`) or neither (`differs`); the git half's tag of its bytes on
 * disk (null when it is not on disk, or the newer version is a commit); and whether a version is over the read limit,
 * so that it is listed and not compared.
 */
export interface Change {
  path: string;
  kind: Kind;
  status: ChangeStatus;
  renamedFrom?: string;
  index: 'clean' | 'staged' | 'differs';
  tag: string | null;
  large?: true;
}

/**
 * One dependency of a package, as its Dependencies tab shows it: its name and version from `package.yaml`; its pin from
 * `package.lock` (null: none); its candidate, the one folder directly in the working folder, the package's own aside,
 * whose `package.yaml` has that name and version, which `rail8 lock` would pin, with the commit its `HEAD` names (null:
 * none, or no clone at the folder's root), never looked up by the pin's repository (null: no such folder, or several);
 * and its state:
 *
 * - `pinned`: the candidate is the pinned repository, and its `HEAD` is the pinned commit;
 * - `unpinned`: `package.lock` pins nothing for it yet, and a lock would pin the candidate;
 * - `moved`: the candidate is another folder, or its `HEAD` another commit, than the pin, and a lock would pin it;
 * - `changed`: the candidate's files differ from its `HEAD`, or it has no `HEAD`, so a lock would refuse it;
 * - `missing`: no folder holds that name and version, or several do.
 *
 * `running` is what the node resolved for it, when the node runs the package.
 */
export interface Dependency {
  name: string;
  version: string;
  pin: Pin | null;
  candidate: { folder: string; head: string | null } | null;
  state: 'pinned' | 'unpinned' | 'moved' | 'changed' | 'missing';
  running?: { commit: string; content: string };
}

/** A pin of `package.lock`: the repository and commit of the dependency, and the content digest of that commit, `sha256:<hex>`. */
export interface Pin {
  repository: string;
  commit: string;
  content: string;
}

/**
 * One package, as its tab shows it: its row, git's author identity in its clone, every file of its folder that differs
 * from `HEAD`, sorted by path, its artifacts on disk, sorted by path, its dependencies, sorted by name, and the git
 * half's tags of `package.yaml` and `package.lock` on disk. `truncated` says when either list was cut at its limit.
 */
export interface PackageState extends PackageRow {
  /** The author identity git gives in this clone, as `git var GIT_AUTHOR_IDENT` names it; null when it gives none, and a commit is refused. */
  identity: { name: string; email: string } | null;
  changes: Change[];
  artifacts: Artifact[];
  dependencies: Dependency[];
  /** The tag of `package.yaml` on disk; null when it is not there. */
  manifestTag: string | null;
  /** The tag of `package.lock` on disk; null when there is none. */
  lockTag: string | null;
  truncated: boolean;
}

/** One version of one file: its text, or its size when it is binary, and the git half's tag of its bytes. */
export type FileVersion = ({ text: string } | { binary: true; size: number }) & { etag: string };

/** One page of a package's first-parent history, newest first, and the commit the next page starts at (null: none). */
export interface LogPage {
  items: Commit[];
  next: string | null;
}

/**
 * A commit of chosen paths: its message; the branch and the commit `HEAD` the author reviewed (`head` null: the branch
 * has no commit yet); and each chosen path, relative to the working folder, with the tag of the version on disk the
 * author reviewed (null: not on disk). A renamed change is named with the path it was renamed from too, with that
 * path's own tag, or null when it is not on disk.
 */
export interface CommitBody {
  message: string;
  branch: string;
  head: string | null;
  paths: { path: string; tag: string | null }[];
}

/**
 * The commit a commit of chosen paths made, as it was read back: the commit itself; `staged`, the paths it changes
 * against its parent that were not chosen, which a hook staged; `rewritten`, the chosen paths it does not hold as their
 * bytes were checked (a path it lacks counts), which a hook rewrote; and `changedOnDisk`, the chosen paths whose bytes
 * on disk after the commit are not those it holds, which a hook changed without staging. Each list is sorted, and none
 * is undone. Its first parent is not the request's `head` when a commit was made in a terminal in between.
 */
export interface Committed extends Commit {
  staged: string[];
  rewritten: string[];
  changedOnDisk: string[];
}

/**
 * What a commit answers: the commit (null when git committed but its commit could not be read back, or `HEAD` is no
 * longer the commit git named), what git and the hooks said, and the package's state after it (null when it could not
 * be read). `problem` says why either is null.
 */
export interface CommitAnswer {
  commit: Committed | null;
  output: string;
  package: PackageState | null;
  problem?: string;
}

/**
 * A deploy of a commit of the package to the node at RAIL8_ADMIN_URL, or its check: the commit as 40 hex digits from
 * the package's state; whether this is the check, which changes nothing on the node; and, for the deploy, the state it
 * expects the node to replace, the `replaces` its check answered: a commit of 40 hex digits, or `none`.
 */
export interface DeployBody {
  commit: string;
  check: boolean;
  expect?: string;
}

/**
 * What the node answered for a deploy or its check: the package's name and version at the commit, its repository and
 * commit, every flow it runs after the deploy, and the flows the deploy starts for the first time (`added`), stops and
 * starts again on the new version (`swapped`) and stops (`removed`), each by its qualified id `<package>/<flow>`, sorted.
 */
export interface NodeDeploy {
  package: string;
  version: string;
  repository: string;
  commit: string;
  flows: string[];
  added: string[];
  swapped: string[];
  removed: string[];
}

/**
 * What a deploy or its check answers: the node's answer; `replaces`, the commit of the pool's record of the package that
 * the deploy replaces, or `none`, as the node names it; and the package's state after it (null when it could not be
 * read, and `problem` says why).
 */
export interface DeployAnswer {
  deploy: NodeDeploy;
  replaces: string;
  package: PackageState | null;
  problem?: string;
}

/**
 * A pull or a push: the branch, `HEAD` and the upstream's name (null: none) as the package's state showed them. It is
 * refused, changing nothing, when they are no longer what the clone has.
 */
export interface RemoteBody {
  branch: string;
  head: string;
  upstream: string | null;
}

/**
 * A lock of the package's dependencies: what the author saw, the tags of `package.yaml` and `package.lock` (null: none),
 * and each dependency's candidate folder with its `HEAD`, as the package's state gave them (null: none). It is refused,
 * changing nothing, when any of them is no longer what is on disk.
 */
export interface LockBody {
  manifestTag: string | null;
  lockTag: string | null;
  dependencies: { name: string; folder: string | null; head: string | null }[];
}

/** One pin `rail8 lock` wrote, as it printed it: the dependency's name and version, and its pin. */
export interface LockedPin extends Pin {
  name: string;
  version: string;
}

/** What a lock answers: the pins `rail8 lock` wrote, sorted by name, and the package's state after it (null when it could not be read, and `problem` says why). */
export interface LockAnswer {
  pins: LockedPin[];
  package: PackageState | null;
  problem?: string;
}

/** What a fetch answers: the package's state after it (null when it could not be read, and `problem` says why). */
export interface FetchAnswer {
  package: PackageState | null;
  problem?: string;
}

/**
 * What a pull answers: `HEAD` after it; the files the pull changed, relative to the working folder (null when they could
 * not be read, and `problem` says why); and the package's state after it (null when it could not be read).
 */
export interface PullAnswer {
  head: string;
  changed: string[] | null;
  package: PackageState | null;
  problem?: string;
}

/**
 * What a push answers: the branch of the remote it pushed to, as `refs/heads/<name>`, the commit its upstream named
 * before and the commit pushed, each of 40 hex digits; and the package's state after it (null when it could not be read).
 */
export interface PushAnswer {
  pushed: { ref: string; from: string; to: string };
  package: PackageState | null;
  problem?: string;
}

/**
 * What Get a package answers: the new folder, named as its repository, and exactly one of its state as a package (a
 * malformed `package.yaml` stays its own `problem`, as the list shows it), why it is no package (the reason the list's
 * `others` gives, such as no `package.yaml` at its root), or why its state could not be read though the clone is in
 * place.
 */
export type CloneAnswer =
  | { repository: string; package: PackageState }
  | { repository: string; package: null; reason: string }
  | { repository: string; package: null; problem: string };

/** What the screens of packages ask of the development server's git half. Every call rejects with a {@link PackagesError} when it is refused. */
export interface PackagesApi {
  list(): Promise<PackageList>;
  /** The package of the working folder whose clone is the folder `repository`. */
  get(repository: string): Promise<PackageState>;
  /**
   * One version of the file at `path`, relative to the working folder, at `rev`. A revision is `work`, the files on
   * disk, or a commit of 40 hex digits that the clone holds; never a name such as `HEAD`.
   */
  file(repository: string, rev: string, path: string): Promise<FileVersion>;
  /** The files that differ between the commit `from` and `to`, `work` or a commit, sorted by path. */
  changes(repository: string, from: string, to: string): Promise<{ changes: Change[]; truncated: boolean }>;
  /** The package's artifacts at `rev`, sorted by path. */
  artifacts(repository: string, rev: string): Promise<{ artifacts: Artifact[]; truncated: boolean }>;
  /** The first-parent history of `HEAD`, or of the commit `before`, `limit` commits to a page (50 by default, at most 100). */
  log(repository: string, page?: { before?: string; limit?: number }): Promise<LogPage>;
  /**
   * Commits the chosen paths alone, with the author's own git identity and hooks, once it has checked under the
   * working folder's lock that the branch, `HEAD` and each path's tag are still the ones the author reviewed.
   */
  commit(repository: string, body: CommitBody): Promise<CommitAnswer>;
  /**
   * Checks or deploys a commit the clone holds on the node at RAIL8_ADMIN_URL, as git's identity in the clone. A deploy
   * is refused, changing nothing, when the node's state of the package is no longer its `expect`.
   */
  deploy(repository: string, body: DeployBody): Promise<DeployAnswer>;
  /**
   * Gets a package: clones the repository at `address`, an https or ssh address the rule `cloneSourceOf` takes, into a
   * new folder of the working folder named as the repository, with the author's own credentials and without hooks,
   * submodules or LFS content. An entry of that name is never written into.
   */
  clone(address: string): Promise<CloneAnswer>;
  /**
   * Pins the package's dependencies: once it has checked under the working folder's lock that `package.yaml`,
   * `package.lock` and each candidate are still what the author saw, it runs `rail8 lock`, which writes `package.lock`.
   * Refused while a dependency has several candidates; the command line's problems are a refusal's `problems`.
   */
  lock(repository: string, body: LockBody): Promise<LockAnswer>;
  /** Fetches the remote of the package's branch, with the author's own credentials; it changes no file. */
  fetch(repository: string): Promise<FetchAnswer>;
  /**
   * Fetches, then fast-forwards the branch to its upstream under the working folder's lock. Refused when the branch
   * has diverged from it, or git would overwrite a file on disk.
   */
  pull(repository: string, body: RemoteBody): Promise<PullAnswer>;
  /** Pushes `HEAD`, as the commit the body names, to the upstream's branch of its remote; never forced. */
  push(repository: string, body: RemoteBody): Promise<PushAnswer>;
}

/** A refusal: its HTTP status (0 when the server did not answer), the error text it gave, and the problems or output it named. */
export class PackagesError extends Error {
  readonly status: number;
  readonly problems?: string[];
  readonly output?: string;

  constructor(status: number, message: string, problems?: string[], output?: string) {
    super(message);
    this.name = 'PackagesError';
    this.status = status;
    if (problems !== undefined) this.problems = problems;
    if (output !== undefined) this.output = output;
  }
}

async function refusal(res: Response): Promise<PackagesError> {
  let text = `${res.status} ${res.statusText}`.trim();
  let problems: string[] | undefined;
  let output: string | undefined;
  try {
    const body: unknown = await res.json();
    if (typeof body === 'object' && body !== null) {
      if ('error' in body && typeof body.error === 'string') text = body.error;
      if ('problems' in body && Array.isArray(body.problems)) problems = body.problems.filter((p): p is string => typeof p === 'string');
      if ('output' in body && typeof body.output === 'string') output = body.output;
    }
  } catch {
    // not JSON: the status line stands
  }
  return new PackagesError(res.status, text, problems, output);
}

/** The git half of the server that serves the UI, under `/git`, through `send` (the page's `uiFetch` by default). */
export function httpPackagesApi(send: typeof fetch = uiFetch): PackagesApi {
  async function call(url: string, init: RequestInit = {}): Promise<Response> {
    let res: Response;
    try {
      res = await send(url, { ...init, headers: { accept: 'application/json', ...init.headers } });
    } catch (failed) {
      throw new PackagesError(0, `the server does not answer: ${String(failed)}`);
    }
    if (!res.ok) throw await refusal(res);
    return res;
  }

  const of = (repository: string, route = '', query: Record<string, string | undefined> = {}) => {
    const search = new URLSearchParams(Object.entries(query).filter((e): e is [string, string] => e[1] !== undefined)).toString();
    return `/git/packages/${encodeURIComponent(repository)}${route}${search === '' ? '' : `?${search}`}`;
  };
  const json = async <T>(url: string) => (await (await call(url)).json()) as T;

  const post = async <T>(url: string, body: unknown) =>
    (await (await call(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })).json()) as T;

  return {
    list: () => json<PackageList>('/git/packages'),
    get: (repository) => json<PackageState>(of(repository)),
    async file(repository, rev, path) {
      const res = await call(of(repository, '/file', { rev, path }));
      const body = (await res.json()) as { text: string } | { binary: true; size: number };
      return { ...body, etag: res.headers.get('etag') ?? '' };
    },
    changes: (repository, from, to) => json(of(repository, '/changes', { from, to })),
    artifacts: (repository, rev) => json(of(repository, '/artifacts', { rev })),
    log: (repository, page = {}) => json<LogPage>(of(repository, '/log', { before: page.before, limit: page.limit?.toString() })),
    commit: (repository, body) => post<CommitAnswer>(of(repository, '/commit'), body),
    deploy: (repository, body) => post<DeployAnswer>(of(repository, '/deploy'), body),
    clone: (address) => post<CloneAnswer>('/git/clone', { address }),
    lock: (repository, body) => post<LockAnswer>(of(repository, '/lock'), body),
    fetch: (repository) => post<FetchAnswer>(of(repository, '/fetch'), {}),
    pull: (repository, body) => post<PullAnswer>(of(repository, '/pull'), body),
    push: (repository, body) => post<PushAnswer>(of(repository, '/push'), body),
  };
}
