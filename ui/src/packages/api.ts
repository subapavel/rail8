/** A commit of a package's clone: its id (40 hex digits), its parents' ids, its subject, its author's name and its author date. */
export interface Commit {
  id: string;
  parents: string[];
  subject: string;
  author: string;
  /** The author date, as ISO 8601 with its offset. */
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

/** What the screens of packages ask of the development server's git half. Every call rejects with a {@link PackagesError} when it is refused. */
export interface PackagesApi {
  list(): Promise<PackageList>;
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

/** The git half of the server that serves the UI, under `/git`, through `send` (the browser's `fetch` by default). */
export function httpPackagesApi(send: typeof fetch = (input, init) => fetch(input, init)): PackagesApi {
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

  return {
    async list() {
      return (await (await call('/git/packages')).json()) as PackageList;
    },
  };
}
