import { realpath } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { foreign } from '../../../server/adminProxy.ts';
import { Refusal, send, sendFailure } from '../../../server/workingFolder.ts';
import type { PackageList } from '../api.ts';
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
 *
 * Every refusal is `{error}`. Every other path goes to `next`.
 */
export function gitPackages(options: GitPackagesOptions) {
  const host = hostOf(options.node.target);

  const list = async (): Promise<PackageList> => {
    const shown = { writable: options.writable, node: host };
    if (options.workspace === undefined) return { configured: false, ...shown, packages: [], others: [], truncated: false };
    let root: string;
    try {
      root = await realpath(options.workspace);
    } catch {
      throw new Refusal(404, `the working folder ${options.workspace} does not exist`);
    }
    let answer: Promise<NodeRead> | undefined;
    const node: NodeState = { host, read: () => (answer ??= readNode(options.node, options.timer, options.fetch)) };
    return { configured: true, ...shown, ...(await listPackages(options.git, root, node)) };
  };

  const answer = async (req: IncomingMessage, path: string): Promise<unknown> => {
    const unfit = await options.ready;
    if (unfit !== null) throw new Refusal(503, unfit);
    if (path !== '/git/packages') throw new Refusal(404, `the git half has no route ${path}`);
    if (req.method !== 'GET') throw new Refusal(405, 'GET /git/packages is the only way to call this route');
    return list();
  };

  return (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const path = new URL(req.url ?? '', 'http://localhost').pathname;
    if (path !== PREFIX && !path.startsWith(`${PREFIX}/`)) {
      next();
      return;
    }
    const refused = foreign(req.headers, options.origins, 'the git half');
    if (refused !== undefined) {
      send(res, 403, { error: refused });
      return;
    }
    answer(req, path).then(
      (value) => send(res, 200, value),
      (error: unknown) => sendFailure(res, error),
    );
  };
}
