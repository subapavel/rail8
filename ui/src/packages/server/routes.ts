import { realpath } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { foreign } from '../../../server/adminProxy.ts';
import { isSessionRefused, nodeFor, refusedSession } from '../../../server/session.ts';
import { admitted, jsonBody, Refusal, send, sendFailure, type WorkingFolderWrites } from '../../../server/workingFolder.ts';
import type { CloneAnswer, NewPackageAnswer, PackageList, PackageState } from '../api.ts';
import { cloneSourceOf, CLONE_REFUSALS, LOCK_REFUSALS, NEW_PACKAGE_REFUSALS, newPackageBodyOf, REMOTE_REFUSALS } from '../rules.ts';
import { cloneInto, CloneRefused } from './clone.ts';
import { commitBodyOf, commitPaths, CommitRefused } from './commit.ts';
import { deployBodyFrom, deployCommit, DeployRefused } from './deploy.ts';
import { discardBodyOf, discardPaths, DiscardRefused } from './discard.ts';
import { lockBodyOf, lockPackage, LockRefused } from './lock.ts';
import { createPackage } from './newPackage.ts';
import { artifactsAt, changesOf, cloneOf, fileOf, logOf, stateOf } from './package.ts';
import { folderOf, listPackages, type NodeState } from './packages.ts';
import { hostOf, readNode, readPool, type NodeRead, type NodeTarget } from './node.ts';
import { fetchRemote, pullRemote, pushRemote, remoteBodyOf, RemoteRefused } from './remote.ts';
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
  /** The node whose deployed state the rows show; each request reaches it with the credential the session gate gave it. */
  node: NodeTarget;
  git: Runner;
  /** The check of git made when the development server started: null when git serves, else why it does not. */
  ready: Promise<string | null>;
  timer: Timer;
  /** What calls the node, under each request's credential; the runtime's `fetch` by default. */
  fetch?: typeof fetch;
  /** The Rail8 command line a lock runs: RAIL8_CLI, or the `bin/rail8` of the Rail8 checkout. */
  cli: string;
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
 * - `POST …/{repository}/discard` `{head, paths}`: 200 `{discarded, left, package}`, the chosen paths put back as in the
 *   last commit (see {@link discardPaths}), with the package's state after it, or `package: null` and `problem` when
 *   that cannot be read. Refused, as a commit is, with 403, 503 and 413 before its body is read; a refusal of git
 *   carries its `output`.
 * - `POST …/{repository}/deploy` `{commit, check, expect}`: 200 `{deploy, replaces, package}`, the node's answer to the
 *   deploy of the commit, or to its check (see {@link deployCommit}), with the package's state after it, or
 *   `package: null` and `problem` when that cannot be read. A deploy, never its check, is refused with 403 under
 *   RAIL8_READ_ONLY=true; a refusal of the node carries its `problems`.
 * - `POST /git/clone` `{address}`: 201 {@link CloneAnswer}, Get a package (see {@link cloneInto}): the address cloned
 *   into a new folder of the working folder named as the repository. Refused, in this order: 403 under
 *   RAIL8_READ_ONLY=true; 503 before its body is read while 9 writes of the working folder are under way; 409 while
 *   another clone is under way; 413 for a body over 1 MiB; 400 for a body that is no `{address}` of text, or an address
 *   the rule refuses (see {@link cloneSourceOf}); 404 without a working folder; 409 when anything of that name is in the
 *   working folder; 503 when the server is closing; 422 with git's `output` when git fails, and over the size limit;
 *   504 past 300 s. It holds one admission of the working folder's writes for its whole run, and the lock only for
 *   the move into place.
 * - `POST /git/packages` `{repository, name}`: 201 {@link NewPackageAnswer}, New package (see {@link createPackage}): a
 *   new folder of the working folder named `repository`, a clone of its own whose first commit holds the package
 *   `name`. Refused, in this order: 403 under RAIL8_READ_ONLY=true; 503 before its body is read while 9 writes of the
 *   working folder are under way; 413 for a body over 1 MiB; 400 for a body that is no `{repository, name}` of two
 *   texts, or a repository name that is no name of a package's folder; 422 for a package name `package.yaml`'s schema
 *   refuses; 404 without a working folder; 409 when anything of that name is in the working folder; 503 when the server
 *   is closing; 422 without an author identity, and with git's `output` when git or a hook fails. It holds the working
 *   folder's lock for its whole run.
 * - `POST …/{repository}/lock` `{manifestTag, lockTag, dependencies}`: 200 `{pins, package}`, the pins `rail8 lock`
 *   wrote into `package.lock` (see {@link lockPackage}), with the package's state after it. Refused with 403 under
 *   RAIL8_READ_ONLY=true, with 503 before its body is read while 9 writes of the working folder are under way, with 413
 *   for a body over 1 MiB, with 412 when what it states is no longer on disk, with 409 while a dependency has several
 *   candidates, with 422 and the command line's `problems` when it refused, and with 503, saying how to build it, when
 *   the command line is missing.
 * - `POST …/{repository}/fetch` `{}`: 200 `{package}`, a fetch of the branch's remote (see {@link fetchRemote}).
 * - `POST …/{repository}/pull` `{branch, head, upstream}`: 200 `{head, changed, package}`, a fetch and a fast-forward
 *   to the upstream under the working folder's lock (see {@link pullRemote}), with 503 before its body is read while
 *   9 writes of the working folder are under way.
 * - `POST …/{repository}/push` `{branch, head, upstream}`: 200 `{pushed, package}`, a push of `head` to the upstream's
 *   branch (see {@link pushRemote}).
 *
 *   Each of the three is refused with 403 under RAIL8_READ_ONLY=true and with 413 for a body over 1 MiB, and answers
 *   `package: null` and `problem` when the package's state cannot be read after it; a refusal of git carries its
 *   `output`. Fetch and push take no lock of the working folder, as they change no file of it.
 *
 * A revision is `work` or a commit of 40 hex digits, never a name such as `HEAD`. Under a package, 400 refuses a
 * malformed repository name, revision or path, or a path with a `.git` segment; 404 a folder that is no package, a
 * path outside it, no file there or a commit the clone lacks; 403 a path whose real path leaves the package's real
 * folder; 413 a file over the read limit of 1 MiB.
 *
 * Every call to the node goes through the client of the request, as the session gate admitted it. The session's refusal
 * by the node is answered as the gate answers it (401), but after a commit, which was made: its 200 then carries
 * `package: null` and the reason. Every other refusal is `{error}`, `{error, output}` or `{error, problems}`. Every
 * other path goes to `next`.
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
  const nodeState = (req: IncomingMessage): NodeState => {
    const node = nodeFor(req, options.node.target, options.fetch);
    let answer: Promise<NodeRead> | undefined;
    let pool: ReturnType<typeof readPool> | undefined;
    return { host, read: () => (answer ??= readNode(node, options.timer)), pool: () => (pool ??= readPool(node, options.timer)) };
  };

  const list = async (req: IncomingMessage): Promise<PackageList> => {
    const shown = { writable: options.writable, node: host };
    if (options.workspace === undefined) return { configured: false, ...shown, packages: [], others: [], truncated: false };
    return { configured: true, ...shown, ...(await listPackages(options.git, await rootOf(options.workspace), nodeState(req))) };
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
        return { ...done, package: await stateOf(options.git, clone, repository, nodeState(req)) };
      } catch (error) {
        const unread = `the package's state could not be read after the commit: ${error instanceof Error ? error.message : String(error)}`;
        return { ...done, package: null, problem: done.problem === undefined ? unread : `${done.problem}; ${unread}` };
      }
    });
  };

  const discard = (req: IncomingMessage, repository: string) => {
    if (!options.writable) throw new Refusal(403, 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was discarded');
    return admitted(options.writes, async (lock) => {
      const body = discardBodyOf(repository, await jsonBody(req));
      const clone = await cloneIn(repository);
      return withState(req, clone, repository, 'the discard', await discardPaths(options.git, lock, clone, repository, body));
    });
  };

  /** `done` with the package's state after `what`, or `package: null` and the reason when that cannot be read. */
  const withState = async <T extends object>(req: IncomingMessage, clone: string, repository: string, what: string, done: T): Promise<T & { package: PackageState | null; problem?: string }> => {
    try {
      return { ...done, package: await stateOf(options.git, clone, repository, nodeState(req)) };
    } catch (error) {
      const unread = `the package's state could not be read after ${what}: ${error instanceof Error ? error.message : String(error)}`;
      const before = (done as { problem?: string }).problem;
      return { ...done, package: null, problem: before === undefined ? unread : `${before}; ${unread}` };
    }
  };

  const remote = async (req: IncomingMessage, repository: string, route: 'fetch' | 'pull' | 'push') => {
    if (!options.writable) throw new Refusal(403, REMOTE_REFUSALS.readOnly(route === 'fetch' ? 'fetched' : route === 'pull' ? 'pulled' : 'pushed'));
    if (route === 'pull') {
      return admitted(options.writes, async (lock) => {
        const body = remoteBodyOf(await jsonBody(req));
        const clone = await cloneIn(repository);
        return withState(req, clone, repository, 'the pull', await pullRemote(options.git, lock, clone, repository, body));
      });
    }
    const body = await jsonBody(req);
    if (route === 'fetch') {
      const clone = await cloneIn(repository);
      await fetchRemote(options.git, clone);
      return withState(req, clone, repository, 'the fetch', {});
    }
    const asked = remoteBodyOf(body);
    const clone = await cloneIn(repository);
    return withState(req, clone, repository, 'the push', { pushed: await pushRemote(options.git, clone, asked) });
  };

  const lock = (req: IncomingMessage, repository: string) => {
    if (!options.writable) throw new Refusal(403, LOCK_REFUSALS.readOnly);
    return admitted(options.writes, async (held) => {
      const body = lockBodyOf(await jsonBody(req));
      const clone = await cloneIn(repository);
      const pins = await lockPackage(options.git, held, options.cli, clone, repository, body);
      return withState(req, clone, repository, 'the lock', { pins });
    });
  };

  let cloning = false;
  const clone = (req: IncomingMessage): Promise<CloneAnswer> => {
    if (!options.writable) throw new Refusal(403, CLONE_REFUSALS.readOnly);
    return admitted(options.writes, async (lock) => {
      if (cloning) throw new Refusal(409, CLONE_REFUSALS.another);
      cloning = true;
      try {
        const body = await jsonBody(req);
        const source = cloneSourceOf(typeof body === 'object' && body !== null && !Array.isArray(body) ? (body as { address?: unknown }).address : undefined);
        if ('error' in source) throw new Refusal(source.status, source.error);
        if (options.workspace === undefined) throw new Refusal(404, 'no working folder is set: start the UI with RAIL8_WORKSPACE');
        const root = await rootOf(options.workspace);
        const { repository } = source;
        const done = await cloneInto(options.git, options.timer, lock, root, source.address, repository);
        const problem = (error: unknown) => {
          const unread = `the package's state could not be read after the clone: ${error instanceof Error ? error.message : String(error)}`;
          return { repository, package: null, problem: done.problem === undefined ? unread : `${done.problem}; ${unread}` };
        };
        try {
          const found = await folderOf(options.git, root, { name: repository, isSymbolicLink: () => false });
          if ('reason' in found) return done.problem === undefined ? { repository, package: null, reason: found.reason } : problem(new Error(found.reason));
          const state = await stateOf(options.git, found.clone, repository, nodeState(req));
          return done.problem === undefined ? { repository, package: state } : { repository, package: null, problem: done.problem };
        } catch (error) {
          return problem(error);
        }
      } finally {
        cloning = false;
      }
    });
  };

  const create = (req: IncomingMessage): Promise<NewPackageAnswer> => {
    if (!options.writable) throw new Refusal(403, NEW_PACKAGE_REFUSALS.readOnly);
    return admitted(options.writes, async (lock) => {
      const body = newPackageBodyOf(await jsonBody(req));
      if ('error' in body) throw new Refusal(body.status, body.error);
      if (options.workspace === undefined) throw new Refusal(404, 'no working folder is set: start the UI with RAIL8_WORKSPACE');
      const { repository } = body;
      const done = await createPackage(options.git, lock, await rootOf(options.workspace), repository, body.name);
      try {
        const state = await stateOf(options.git, done.clone, repository, nodeState(req));
        return done.problem === undefined ? { repository, package: state } : { repository, package: null, problem: done.problem };
      } catch (error) {
        const unread = `the package's state could not be read after it was made: ${error instanceof Error ? error.message : String(error)}`;
        return { repository, package: null, problem: done.problem === undefined ? unread : `${done.problem}; ${unread}` };
      }
    });
  };

  const deploy = async (req: IncomingMessage, repository: string) => {
    const body = deployBodyFrom(await jsonBody(req));
    if (!body.check && !options.writable) throw new Refusal(403, 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was deployed');
    const clone = await cloneIn(repository);
    const done = await deployCommit(options.git, clone, repository, body, nodeFor(req, options.node.target, options.fetch), options.timer);
    try {
      return { ...done, package: await stateOf(options.git, clone, repository, nodeState(req)) };
    } catch (error) {
      return { ...done, package: null, problem: `the package's state could not be read after ${body.check ? 'the check' : 'the deploy'}: ${error instanceof Error ? error.message : String(error)}` };
    }
  };

  const one = async (req: IncomingMessage, repository: string, route: string | undefined, query: URLSearchParams): Promise<{ value: unknown; etag?: string }> => {
    const clone = await cloneIn(repository);
    const param = (name: string) => query.get(name) ?? undefined;
    const required = (name: string) => {
      const value = query.get(name);
      if (value === null) throw new Refusal(400, `the query names no ${name}`);
      return value;
    };
    switch (route) {
      case undefined:
        return { value: await stateOf(options.git, clone, repository, nodeState(req)) };
      case 'file': {
        const { etag, ...value } = await fileOf(options.git, clone, repository, required('rev'), required('path'));
        return { value, etag };
      }
      case 'changes':
        return { value: await changesOf(options.git, clone, repository, required('from'), required('to')) };
      case 'artifacts':
        return { value: await artifactsAt(options.git, clone, repository, required('rev')) };
      case 'log':
        return { value: await logOf(options.git, clone, repository, nodeState(req), param('before'), param('limit')) };
      default:
        throw new Refusal(404, `the git half has no route ${route} of a package`);
    }
  };

  const answer = async (req: IncomingMessage, url: URL): Promise<{ value: unknown; etag?: string; status?: number }> => {
    const unfit = await options.ready;
    if (unfit !== null) throw new Refusal(503, unfit);
    const path = url.pathname;
    if (path === '/git/clone') {
      if (req.method !== 'POST') throw new Refusal(405, `POST is the only way to call ${path}`);
      return { value: await clone(req), status: 201 };
    }
    if (path === '/git/packages' && req.method === 'POST') return { value: await create(req), status: 201 };
    if (path === '/git/packages' && req.method !== 'GET') throw new Refusal(405, `GET and POST are the only ways to call ${path}`);
    const under = /^\/git\/packages\/([^/]+)(?:\/([^/]+))?$/.exec(path);
    if (path !== '/git/packages' && under === null) throw new Refusal(404, `the git half has no route ${path}`);
    const writes = ['commit', 'discard', 'deploy', 'lock', 'fetch', 'pull', 'push'];
    const method = under?.[2] !== undefined && writes.includes(under[2]) ? 'POST' : 'GET';
    if (req.method !== method) throw new Refusal(405, `${method} is the only way to call ${path}`);
    if (under === null) return { value: await list(req) };
    let repository: string;
    try {
      repository = decodeURIComponent(under[1]!);
    } catch {
      throw new Refusal(400, 'the repository name is not well encoded');
    }
    if (under[2] === 'fetch' || under[2] === 'pull' || under[2] === 'push') return { value: await remote(req, repository, under[2]) };
    if (under[2] === 'lock') return { value: await lock(req, repository) };
    if (under[2] === 'discard') return { value: await discard(req, repository) };
    if (method === 'POST') return { value: await (under[2] === 'commit' ? commit(req, repository) : deploy(req, repository)) };
    return one(req, repository, under[2], url.searchParams);
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
      ({ value, etag, status }) => send(res, status ?? 200, value, etag),
      (error: unknown) => {
        if (res.headersSent) sendFailure(res, error);
        else if (isSessionRefused(error)) refusedSession(res, error);
        else if (error instanceof CommitRefused || error instanceof DiscardRefused || error instanceof CloneRefused || error instanceof RemoteRefused) send(res, error.status, { error: error.message, output: error.output });
        else if (error instanceof LockRefused)
          send(res, error.status, { error: error.message, ...(error.problems === undefined ? {} : { problems: error.problems }), ...(error.output === undefined ? {} : { output: error.output }) });
        else if (error instanceof DeployRefused) send(res, error.status, { error: error.message, ...(error.problems === undefined ? {} : { problems: error.problems }) });
        else sendFailure(res, error);
      },
    );
  };
}
