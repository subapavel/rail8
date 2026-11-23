import { formatted } from './formatter.ts';
import type { Timer } from './timer.ts';

/** A read of the node: 10 s, and at most 8 MiB of answer. */
export const NODE_READ = { ms: 10_000, bytes: 8 * 1024 * 1024 };

/** What the node runs of one package repository, as its `GET /admin/packages` lists it. */
export interface Running {
  repository: string;
  commit: string;
  state: 'RUNNING' | 'FAILED';
  problems: string[];
  deployedAt: string;
}

/** What the node answered for `GET /admin/packages`: every package it runs from a repository, or why it gave no answer. */
export type NodeRead = { answered: true; running: Running[] } | { answered: false; error: string };

/** The node the git half reads: its admin API, such as `http://127.0.0.1:8081`, and the bearer token it needs, if any. */
export interface NodeTarget {
  target: string;
  token?: string;
}

/** The host of the node's admin API, as every answer names it: never the user information of its URL. */
export function hostOf(target: string): string {
  try {
    return new URL(target).host;
  } catch {
    return formatted(target);
  }
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;

/** The body of `answer`, refused past `limit` bytes. */
async function bodyOf(answer: Response, limit: number): Promise<Buffer | null> {
  if (answer.body === null) return Buffer.alloc(0);
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of answer.body as AsyncIterable<Uint8Array>) {
    size += chunk.length;
    if (size > limit) return null;
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
}

/**
 * What the node at `node.target` runs: `GET /admin/packages` with its token, within 10 s on `timer` and 8 MiB. Its
 * packages deployed with no repository are left out. Every way it gives no answer (no connection, a refused token,
 * another status, an answer that is too large or no list of packages) is an answer with `answered: false` and the
 * reason through the formatter.
 */
export async function readNode(node: NodeTarget, timer: Timer, send: typeof fetch = fetch): Promise<NodeRead> {
  const host = hostOf(node.target);
  const failed = (error: string): NodeRead => ({ answered: false, error: formatted(error, node.token) });
  const stop = new AbortController();
  const cancel = timer.after(NODE_READ.ms, () => stop.abort());
  try {
    let answer: Response;
    try {
      answer = await send(`${node.target}/admin/packages`, {
        headers: { accept: 'application/json', ...(node.token ? { authorization: `Bearer ${node.token}` } : {}) },
        signal: stop.signal,
      });
    } catch (error) {
      if (stop.signal.aborted) return failed(`the node at ${host} did not answer within ${NODE_READ.ms / 1000} s`);
      const cause = error instanceof Error && error.cause instanceof Error ? `: ${error.cause.message}` : '';
      return failed(`the node at ${host} does not answer (is it running?): ${String(error)}${cause}`);
    }
    const body = await bodyOf(answer, NODE_READ.bytes).catch(() => undefined);
    if (body === undefined) return failed(`the node at ${host} did not answer within ${NODE_READ.ms / 1000} s`);
    if (body === null) return failed(`the node at ${host} answered more than 8 MiB`);
    let json: unknown;
    try {
      json = JSON.parse(body.toString('utf8'));
    } catch {
      json = undefined;
    }
    if (answer.status === 401) return failed(`the node at ${host} refuses RAIL8_ADMIN_TOKEN (401)`);
    if (answer.status !== 200) {
      const said = isRecord(json) && typeof json.error === 'string' ? `: ${json.error}` : '';
      return failed(`the node at ${host} answered ${answer.status}${said}`);
    }
    if (!isRecord(json) || !Array.isArray(json.items)) return failed(`the node at ${host} answered no list of packages`);
    const running: Running[] = [];
    for (const item of json.items as unknown[]) {
      if (!isRecord(item) || typeof item.repository !== 'string' || typeof item.commit !== 'string') continue;
      running.push({
        repository: item.repository,
        commit: item.commit,
        state: item.state === 'FAILED' ? 'FAILED' : 'RUNNING',
        problems: Array.isArray(item.problems) ? item.problems.filter((p): p is string => typeof p === 'string').map((p) => formatted(p, node.token)) : [],
        deployedAt: typeof item.deployedAt === 'string' ? item.deployedAt : '',
      });
    }
    return { answered: true, running };
  } finally {
    cancel();
  }
}
