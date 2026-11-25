import { isSessionRefused, type NodeCall } from '../../../server/session.ts';
import { Refusal } from '../../../server/workingFolder.ts';
import type { DeployBody, NodeDeploy } from '../api.ts';
import type { RefusalValues } from '../../../server/refusal.ts';
import { actorOf, deployBodyOf, DEPLOY_MS, DEPLOY_REFUSALS, PACKAGE_REFUSALS, staleDeploy, type Refused } from '../rules.ts';
import { formatted } from './formatter.ts';
import { holds } from './git.ts';
import { hostOf, NODE_READ } from './node.ts';
import { identityOf } from './package.ts';
import { refusal } from './refused.ts';
import type { Runner } from './runner.ts';
import type { Timer } from './timer.ts';

/** A refusal of a deploy that names the problems the node found, each through the formatter. */
export class DeployRefused extends Refusal {
  readonly problems: string[] | undefined;

  constructor(status: number, refused: Refused, problems?: string[]) {
    super(status, refused.code, refused.error, refused.values);
    this.problems = problems;
  }
}

/** `body` as a deploy or its check takes it, refused with 400 as {@link deployBodyOf} refuses it. */
export function deployBodyFrom(body: unknown): DeployBody {
  const found = deployBodyOf(body);
  if ('error' in found) throw refusal(found.status, found);
  return found;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const texts = (value: unknown): value is string[] => Array.isArray(value) && value.every((v) => typeof v === 'string');

/** The values of the node's refusal `json`, as it gives them: each a string or a number. */
function valuesOf(json: Record<string, unknown>): RefusalValues {
  if (!isRecord(json.values)) return {};
  return Object.fromEntries(Object.entries(json.values).filter((entry): entry is [string, string | number] => typeof entry[1] === 'string' || typeof entry[1] === 'number'));
}

/** The node's answer to a deploy or its check, when it is one: its deploy and the commit it replaces; else null. */
function answerOf(json: unknown): { deploy: NodeDeploy; replaces: string } | null {
  if (!isRecord(json)) return null;
  const { package: name, version, repository, commit, flows, added, swapped, removed, replaces } = json;
  if (typeof name !== 'string' || typeof version !== 'string' || typeof repository !== 'string' || typeof commit !== 'string' || typeof replaces !== 'string') return null;
  if (!texts(flows) || !texts(added) || !texts(swapped) || !texts(removed)) return null;
  return { deploy: { package: name, version, repository, commit, flows, added, swapped, removed }, replaces };
}

/**
 * Deploys the commit `body.commit` of the package `repository`, whose clone is at the real path `clone`, on the node
 * `node`, the request's client, or checks it (`body.check`). The clone must hold the commit (404 otherwise). The node
 * gets `POST /admin/deploy` (with `?check=true` for the check) and `{repository, commit}`, with the deploy's `expect`
 * and `X-Rail8-Actor`: git's identity in the clone as {@link actorOf} writes it, which the client drops in session mode,
 * where the node names the session's user. It answers the node's deploy and what it replaces. The node's refusals are
 * passed on with its status, its error, its code and values, and its problems; one without a code is `node-refused`.
 * But for:
 *
 * - the session's refusal: rejected as it is, and the node did nothing;
 * - its 412 to a deploy, the pool's record changed since the check: 412 with the node's code, saying so;
 * - its 401 in token mode: 502, saying the token is refused;
 * - no connection: 502;
 * - an answer broken off, over 8 MiB, or a 200 that is no deploy: 502 for a check, and 504 for a deploy, its outcome
 *   unknown;
 * - no answer within 5 min on `timer`: 504, the outcome of a deploy unknown.
 *
 * Every text of the node passes through the formatter, the credential removed.
 */
export async function deployCommit(
  git: Runner,
  clone: string,
  repository: string,
  body: DeployBody,
  node: NodeCall,
  timer: Timer,
): Promise<{ deploy: NodeDeploy; replaces: string }> {
  if (!(await holds(git, clone, ['rev-parse', '--verify', '-q', '--end-of-options', `${body.commit}^{commit}`], [1, 128]))) {
    throw refusal(404, PACKAGE_REFUSALS.noCommit({ commit: body.commit }));
  }
  const actor = actorOf(await identityOf(git, clone));
  const host = hostOf(node.target);
  const said = (text: string) => formatted(node.redact(text));
  const lost = () => refusal(504, (body.check ? DEPLOY_REFUSALS.checkLost : DEPLOY_REFUSALS.deployLost)({ host, minutes: DEPLOY_MS / 60_000 }));
  // an answer the node began but the UI cannot read: 502 for a check, which changes nothing; for a deploy the node may
  // have deployed, so its outcome is unknown (504)
  const unreadable = (check: Refused, deploy: Refused) => (body.check ? refusal(502, check) : refusal(504, deploy));

  const stop = new AbortController();
  const cancel = timer.after(DEPLOY_MS, () => stop.abort());
  try {
    let answer: Response;
    try {
      answer = await node.send(`${node.target}/admin/deploy${body.check ? '?check=true' : ''}`, {
        method: 'POST',
        headers: { accept: 'application/json', 'content-type': 'application/json', 'x-rail8-actor': actor },
        body: JSON.stringify(body.check ? { repository, commit: body.commit } : { repository, commit: body.commit, expect: body.expect }),
        signal: stop.signal,
      });
    } catch (error) {
      if (isSessionRefused(error)) throw error;
      if (stop.signal.aborted) throw lost();
      const cause = error instanceof Error && error.cause instanceof Error ? `: ${error.cause.message}` : '';
      throw refusal(502, (body.check ? DEPLOY_REFUSALS.checkUnreachable : DEPLOY_REFUSALS.deployUnreachable)(said(`${String(error)}${cause}`), { host }));
    }
    let text: string | null;
    try {
      text = await bodyText(answer, NODE_READ.bytes);
    } catch {
      throw stop.signal.aborted ? lost() : unreadable(DEPLOY_REFUSALS.checkBroken({ host }), DEPLOY_REFUSALS.deployBroken({ host }));
    }
    if (text === null) throw unreadable(DEPLOY_REFUSALS.checkTooLarge({ host }), DEPLOY_REFUSALS.deployTooLarge({ host }));
    let json: unknown;
    try {
      json = JSON.parse(text);
    } catch {
      json = undefined;
    }
    if (answer.status === 401) throw refusal(502, (body.check ? DEPLOY_REFUSALS.checkTokenRefused : DEPLOY_REFUSALS.deployTokenRefused)({ host }));
    if (answer.ok) {
      const found = answerOf(json);
      if (found === null) throw unreadable(DEPLOY_REFUSALS.checkUnreadable({ host, status: answer.status }), DEPLOY_REFUSALS.deployUnreadable({ host, status: answer.status }));
      const { deploy } = found;
      return {
        deploy: { ...deploy, flows: deploy.flows.map(said), added: deploy.added.map(said), swapped: deploy.swapped.map(said), removed: deploy.removed.map(said) },
        replaces: said(found.replaces),
      };
    }
    const error = isRecord(json) && typeof json.error === 'string' ? said(json.error) : `the node at ${host} answered ${answer.status}`;
    const problems = isRecord(json) && texts(json.problems) ? json.problems.map(said) : undefined;
    const stated = answer.status === 412 && !body.check ? staleDeploy(error) : error;
    const code = isRecord(json) && typeof json.code === 'string' && json.code !== '' ? json.code : undefined;
    const refused = code === undefined ? DEPLOY_REFUSALS.nodeRefused(stated) : { code, error: stated, values: isRecord(json) ? valuesOf(json) : {} };
    throw new DeployRefused(answer.status, refused, problems);
  } finally {
    cancel();
  }
}

/** The body of `answer` as text, null past `limit` bytes. */
async function bodyText(answer: Response, limit: number): Promise<string | null> {
  if (answer.body === null) return '';
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of answer.body as AsyncIterable<Uint8Array>) {
    size += chunk.length;
    if (size > limit) return null;
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString('utf8');
}
