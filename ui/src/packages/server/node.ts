import { isSessionRefused, type NodeCall } from '../../../server/session.ts';
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
  /** The direct dependencies its deploy resolved, by name: the commit and the content digest of each. */
  dependencies: { name: string; commit: string; content: string }[];
}

/** What the node answered for `GET /admin/packages`: every package it runs from a repository, or why it gave no answer. */
export type NodeRead = { answered: true; running: Running[] } | { answered: false; error: string };

/** The node the git half reads: its admin API, such as `http://127.0.0.1:8081`. Each request reaches it with its own credential. */
export interface NodeTarget {
  target: string;
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
 * What the node at `node.target` runs: `GET /admin/packages` through the request's client `node`, within 10 s on
 * `timer` and 8 MiB. Its packages deployed with no repository are left out. The session's refusal rejects as it is.
 * Every other way it gives no answer (no connection, a refused token, another status, an answer that is too large or
 * no list of packages) is an answer with `answered: false` and the reason, its credential hidden, through the formatter.
 */
export async function readNode(node: NodeCall, timer: Timer): Promise<NodeRead> {
  const host = hostOf(node.target);
  const said = (text: string) => formatted(node.redact(text));
  const failed = (error: string): NodeRead => ({ answered: false, error: said(error) });
  const stop = new AbortController();
  const cancel = timer.after(NODE_READ.ms, () => stop.abort());
  try {
    let answer: Response;
    try {
      answer = await node.send(`${node.target}/admin/packages`, { headers: { accept: 'application/json' }, signal: stop.signal });
    } catch (error) {
      if (isSessionRefused(error)) throw error;
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
    if (answer.status === 401) return failed(`the node at ${host} refuses the UI server's token (401)`);
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
        problems: Array.isArray(item.problems) ? item.problems.filter((p): p is string => typeof p === 'string').map(said) : [],
        deployedAt: typeof item.deployedAt === 'string' ? item.deployedAt : '',
        dependencies: (Array.isArray(item.dependencies) ? (item.dependencies as unknown[]) : []).flatMap((d) =>
          isRecord(d) && d.direct === true && typeof d.name === 'string' && typeof d.commit === 'string' && typeof d.content === 'string'
            ? [{ name: d.name, commit: d.commit, content: d.content }]
            : [],
        ),
      });
    }
    return { answered: true, running };
  } finally {
    cancel();
  }
}
