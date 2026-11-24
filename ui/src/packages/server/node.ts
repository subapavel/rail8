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

/**
 * What the node answered for `GET /admin/pool/packages`: the pool's record of each package, by repository, and every
 * node whose lease holds with what it runs, why it is behind included.
 */
export interface PoolRead {
  records: { repository: string; commit: string }[];
  nodes: { node: string; packages: { repository: string; commit: string; state: 'RUNNING' | 'FAILED'; deployedAt: string; behind: string[] }[] }[];
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

/** What the node answered for one read: its status and its body as JSON (undefined: none), or why it gave no answer. */
type Answer = { status: number; json: unknown } | { error: string };

/**
 * `GET <path>` of the node at `node.target` through the request's client `node`, within 10 s on `timer` and 8 MiB. The
 * session's refusal rejects as it is. Every other way it gives no answer (no connection, a refused token, an answer that
 * is too large) is the reason, its credential hidden, through the formatter.
 */
async function readFrom(node: NodeCall, path: string, timer: Timer): Promise<Answer> {
  const host = hostOf(node.target);
  const stop = new AbortController();
  const cancel = timer.after(NODE_READ.ms, () => stop.abort());
  const failed = (error: string): Answer => ({ error: formatted(node.redact(error)) });
  try {
    let answer: Response;
    try {
      answer = await node.send(`${node.target}${path}`, { headers: { accept: 'application/json' }, signal: stop.signal });
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
    return { status: answer.status, json };
  } finally {
    cancel();
  }
}

/** The reason an answer other than 200 gives: its status, and the node's own error when it names one. */
function statusError(node: NodeCall, status: number, json: unknown): string {
  const said = isRecord(json) && typeof json.error === 'string' ? `: ${json.error}` : '';
  return formatted(node.redact(`the node at ${hostOf(node.target)} answered ${status}${said}`));
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
  const answer = await readFrom(node, '/admin/packages', timer);
  if ('error' in answer) return { answered: false, error: answer.error };
  const { status, json } = answer;
  if (status !== 200) return { answered: false, error: statusError(node, status, json) };
  if (!isRecord(json) || !Array.isArray(json.items)) return { answered: false, error: said(`the node at ${host} answered no list of packages`) };
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
}

/**
 * What the node's pool runs: `GET /admin/pool/packages`, read as {@link readNode} reads, with the records and rows
 * deployed with no repository left out. Null when the node answers no pool route (404); `error` when it gives no answer
 * otherwise, or no records and nodes.
 */
export async function readPool(node: NodeCall, timer: Timer): Promise<PoolRead | { error: string } | null> {
  const strings = (value: unknown) => (Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string').map((v) => formatted(node.redact(v))) : []);
  const answer = await readFrom(node, '/admin/pool/packages', timer);
  if ('error' in answer) return answer;
  const { status, json } = answer;
  if (status === 404) return null;
  if (status !== 200) return { error: statusError(node, status, json) };
  if (!isRecord(json) || !Array.isArray(json.records) || !Array.isArray(json.nodes)) {
    return { error: formatted(node.redact(`the node at ${hostOf(node.target)} answered no records and nodes of its pool`)) };
  }
  const records = (json.records as unknown[]).flatMap((r) =>
    isRecord(r) && typeof r.repository === 'string' && typeof r.commit === 'string' ? [{ repository: r.repository, commit: r.commit }] : [],
  );
  const nodes = (json.nodes as unknown[]).flatMap((n) =>
    isRecord(n) && typeof n.node === 'string'
      ? [
          {
            node: n.node,
            packages: (Array.isArray(n.packages) ? (n.packages as unknown[]) : []).flatMap((p) =>
              isRecord(p) && typeof p.repository === 'string' && typeof p.commit === 'string'
                ? [
                    {
                      repository: p.repository,
                      commit: p.commit,
                      state: p.state === 'FAILED' ? ('FAILED' as const) : ('RUNNING' as const),
                      deployedAt: typeof p.deployedAt === 'string' ? p.deployedAt : '',
                      behind: strings(p.behind),
                    },
                  ]
                : [],
            ),
          },
        ]
      : [],
  );
  return { records, nodes };
}
