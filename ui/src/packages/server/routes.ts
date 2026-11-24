import { realpath } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { foreign } from '../../../server/adminProxy.ts';
import { admitted, jsonBody, Refusal, send, sendFailure, type WorkingFolderWrites } from '../../../server/workingFolder.ts';
import type { PackageList } from '../api.ts';
import { commitBodyOf, commitPaths, CommitRefused } from './commit.ts';
import { deployBodyFrom, deployCommit, DeployRefused } from './deploy.ts';
import { artifactsAt, changesOf, cloneOf, fileOf, logOf, stateOf } from './package.ts';
import { listPackages, type NodeState } from './packages.ts';
import { hostOf, readNode, type NodeRead, type NodeTarget } from './node.ts';
import type { Runner } from './runner.ts';
import type { Timer } from './timer.ts';

/** The working folder whose packages the git half serves, how it runs git, and who may reach it. */
export interface GitPackagesOptions {
  /** The working folder; undefined when none is set, and the list says so. */
  workspace?: string;
  /** Whether a write is allowed; false under RAIL8_READ_ONLY=true. */
  writable: boolean;
  /** The working folder's writes, which the editors' saves share: each git write that touches the clone's files is admitted by them and holds their lock. */
  writes: WorkingFolderWrites;
  /** The development server's own origins; a request with any other `Origin` is refused. */
  origins: readonly string[];
  /** The node whose deployed state the rows show. */
  node: NodeTarget;
  git: Runner;
  /** The check of git made when the development server started: null when git serves, else why it does not. */
  ready: Promise<string | null>;
  timer: Timer;
  /** What calls the node; the runtime's `fetch` by default. */
  fetch?: typeof fetch;
}

const PREFIX = '/git';

/**
 * A development-server middleware over the packages of the working folder, under `/git`, for the UI's own pages only
 * (403 as the `/admin` proxy refuses). While git is missing or older than 2.30, every route answers 503 with the reason.
 *
 * - `GET /git/packages`: 200 {@link PackageList}. `configured` is false, with empty lists, when no working folder is
 *   set; 404 when the folder that is set does not exist; 503 when too many git calls wait.
 * - `GET /git/packages/{repository}`: 200 `PackageState` of the package whose clone is that folder.
 * - `GET …/{repository}/file?rev=&path=`: 200 `{text}`, or `{binary: true, size}`, with the `ETag` of its bytes.
 * - `GET …/{repository}/changes?from=&to=`: 200 `{changes, truncated}`, the files that differ between two revisions.
 * - `GET …/{repository}/artifacts?rev=`: 200 `{artifacts, truncated}`, the package's artifacts at a revision.
 * - `GET …/{repository}/log?before=&limit=`: 200 `{items, next}`, a page of the first-parent history.
 * - `POST …/{repository}/commit` `{message, branch, head, paths}`: 200 `{commit, output, package}`, a commit of the
 *   chosen paths (see {@link commitPaths}), with the package's state after it, or `package: null` and `problem` when
 *   that cannot be read. Refused with 403 under RAIL8_READ_ONLY=true, with 503 before its body is read while 9 writes of the
 *   working folder are under way, and with 413 for a body over 1 MiB; a refusal of git or a hook carries its `output`.
 * - `POST …/{repository}/deploy` `{commit, check, expect}`: 200 `{deploy, replaces, package}`, the node's answer to the
 *   deploy of the commit, or to its check (see {@link deployCommit}), with the package's state after it, or
 *   `package: null` and `problem` when that cannot be read. A deploy, never its check, is refused with 403 under
 *   RAIL8_READ_ONLY=true; a refusal of the node carries its `problems`.
 *
 * A revision is `work` or a commit of 40 hex digits, never a name such as `HEAD`. Under a package, 400 refuses a
 * malformed repository name, revision or path, or a path with a `.git` segment; 404 a folder that is no package, a
 * path outside it, no file there or a commit the clone lacks; 403 a path whose real path leaves the package's real
 * folder; 413 a file over the read limit of 1 MiB.
 *
 * Every refusal is `{error}`, `{error, output}` or `{error, problems}`. Every other path goes to `next`.
 */
export function gitPackages(options: GitPackagesOptions) {
  const host = hostOf(options.node.target);

  const rootOf = async (workspace: string): Promise<string> => {
    try {
      return await realpath(workspace);
    } catch {
      throw new Refusal(404, `the working folder ${workspace} does not exist`);
    }
  };
  const nodeState = (): NodeState => {
    let answer: Promise<NodeRead> | undefined;
    return { host, read: () => (answer ??= readNode(options.node, options.timer, options.fetch)) };
  };

  const list = async (): Promise<PackageList> => {
    const shown = { writable: options.writable, node: host };
    if (options.workspace === undefined) return { configured: false, ...shown, packages: [], others: [], truncated: false };
    return { configured: true, ...shown, ...(await listPackages(options.git, await rootOf(options.workspace), nodeState())) };
  };

  const cloneIn = async (repository: string) => {
    if (options.workspace === undefined) throw new Refusal(404, 'no working folder is set: start the UI with RAIL8_WORKSPACE');
    return cloneOf(options.git, await rootOf(options.workspace), repository);
  };

  const commit = (req: IncomingMessage, repository: string) => {
    if (!options.writable) throw new Refusal(403, 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was committed');
    return admitted(options.writes, async (lock) => {
      const body = commitBodyOf(repository, await jsonBody(req));
      const clone = await cloneIn(repository);
      const done = await commitPaths(options.git, lock, clone, repository, body);
      try {
        return { ...done, package: await stateOf(options.git, clone, repository, nodeState()) };
      } catch (error) {
        const unread = `the package's state could not be read after the commit: ${error instanceof Error ? error.message : String(error)}`;
        return { ...done, package: null, problem: done.problem === undefined ? unread : `${done.problem}; ${unread}` };
      }
    });
  };

  const deploy = async (req: IncomingMessage, repository: string) => {
    const body = deployBodyFrom(await jsonBody(req));
    if (!body.check && !options.writable) throw new Refusal(403, 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was deployed');
    const clone = await cloneIn(repository);
    const done = await deployCommit(options.git, clone, repository, body, options.node, options.timer, options.fetch);
    try {
      return { ...done, package: await stateOf(options.git, clone, repository, nodeState()) };
    } catch (error) {
      return { ...done, package: null, problem: `the package's state could not be read after ${body.check ? 'the check' : 'the deploy'}: ${error instanceof Error ? error.message : String(error)}` };
    }
  };

  const one = async (repository: string, route: string | undefined, query: URLSearchParams): Promise<{ value: unknown; etag?: string }> => {
    const clone = await cloneIn(repository);
    const param = (name: string) => query.get(name) ?? undefined;
    const required = (name: string) => {
      const value = query.get(name);
      if (value === null) throw new Refusal(400, `the query names no ${name}`);
      return value;
    };
    switch (route) {
      case undefined:
        return { value: await stateOf(options.git, clone, repository, nodeState()) };
      case 'file': {
        const { etag, ...value } = await fileOf(options.git, clone, repository, required('rev'), required('path'));
        return { value, etag };
      }
      case 'changes':
        return { value: await changesOf(options.git, clone, repository, required('from'), required('to')) };
      case 'artifacts':
        return { value: await artifactsAt(options.git, clone, repository, required('rev')) };
      case 'log':
        return { value: await logOf(options.git, clone, repository, nodeState(), param('before'), param('limit')) };
      default:
        throw new Refusal(404, `the git half has no route ${route} of a package`);
    }
  };

  const answer = async (req: IncomingMessage, url: URL): Promise<{ value: unknown; etag?: string }> => {
    const unfit = await options.ready;
    if (unfit !== null) throw new Refusal(503, unfit);
    const path = url.pathname;
    const under = /^\/git\/packages\/([^/]+)(?:\/([^/]+))?$/.exec(path);
    if (path !== '/git/packages' && under === null) throw new Refusal(404, `the git half has no route ${path}`);
    const method = under?.[2] === 'commit' || under?.[2] === 'deploy' ? 'POST' : 'GET';
    if (req.method !== method) throw new Refusal(405, `${method} is the only way to call ${path}`);
    if (under === null) return { value: await list() };
    let repository: string;
    try {
      repository = decodeURIComponent(under[1]!);
    } catch {
      throw new Refusal(400, 'the repository name is not well encoded');
    }
    if (method === 'POST') return { value: await (under[2] === 'commit' ? commit(req, repository) : deploy(req, repository)) };
    return one(repository, under[2], url.searchParams);
  };

  return (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const url = new URL(req.url ?? '', 'http://localhost');
    const path = url.pathname;
    if (path !== PREFIX && !path.startsWith(`${PREFIX}/`)) {
      next();
      return;
    }
    const refused = foreign(req.headers, options.origins, 'the git half');
    if (refused !== undefined) {
      send(res, 403, { error: refused });
      return;
    }
    answer(req, url).then(
      ({ value, etag }) => send(res, 200, value, etag),
      (error: unknown) => {
        if (res.headersSent) sendFailure(res, error);
        else if (error instanceof CommitRefused) send(res, error.status, { error: error.message, output: error.output });
        else if (error instanceof DeployRefused) send(res, error.status, { error: error.message, ...(error.problems === undefined ? {} : { problems: error.problems }) });
        else sendFailure(res, error);
      },
    );
  };
}
