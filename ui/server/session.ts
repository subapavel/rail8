import type { IncomingHttpHeaders, IncomingMessage, ServerResponse } from 'node:http';
import { isIPv4 } from 'node:net';

// The UI server's session: which credential every call to the node carries, the gate in front of the proxy and the
// halves, and the routes a page logs in and out with. Plain node:http, so a UI server outside development can mount it.

type Env = Readonly<Record<string, string | undefined>>;

/**
 * How the UI server calls the node, chosen at its start and never mixed. Token mode: every call carries the token of
 * `variable`. Session mode: every call carries the caller's own session, which the browser holds in the cookie `cookie`.
 */
export type Mode =
  | { kind: 'token'; token: string; variable: 'RAIL8_TOKEN' | 'RAIL8_ADMIN_TOKEN' }
  | { kind: 'session'; cookie: 'rail8-session' | '__Host-rail8-session'; secure: boolean };

/** What the gate decided for one request: the credential its calls to the node carry, or the refusal it answers itself. */
export type Decision =
  | { authorization: string; session: string | null }
  | { refused: { status: 401 | 409; code: 'no-credential' | 'session-changed'; error: string } };

/** A session as the node hands it out; group 1 is its id. */
const SESSION_FORM = /^rail8s_([0-9a-f]{16})_[A-Za-z0-9_-]{43}$/;

/** The emergency key's form, which the UI server never sends. */
const KEY_PREFIX = 'rail8k_';

/** The node's 401 codes after which the session is gone for good, so its cookie is cleared. */
const ENDED = new Set(['unknown-credential', 'session-ended', 'blocked']);

/** The paths whose requests the gate admits; every other path goes on untouched. */
const GATED = ['/admin', '/git', '/api'] as const;

/** Where the gate leaves its decision on a request; a `Symbol.for` key, so every copy of this file finds it. */
const DECIDED = Symbol.for('rail8.uiSession');

const BRAND = Symbol.for('rail8.sessionRefused');

/** How long one call of the `/session` routes to the node may take. */
export const SESSION_CALL_MS = 10_000;

/** The most bytes of a login's body. */
export const LOGIN_MAX_BYTES = 1024;

/** The most bytes of a node's answer the `/session` routes read, and of a 401 that {@link nodeFor} reads for its code. */
const ANSWER_MAX_BYTES = 64 * 1024;
const REFUSAL_MAX_BYTES = 4 * 1024;

/** What `/session` answers of the node's `me`: never anything else it might carry. */
const PUBLIC = ['name', 'kind', 'roles', 'actions', 'refused', 'via', 'firstPassword', 'expiresAt', 'emergencyKey'] as const;

/**
 * Whether `hostname`, as the URL parser writes it, is loopback: `localhost`, an IPv4 address in 127.0.0.0/8, or
 * `[::1]`. It is never resolved.
 */
export function isLoopbackName(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '[::1]' || (isIPv4(hostname) && hostname.startsWith('127.'));
}

/**
 * Whether the development server's resolved `server.host` listens on loopback only: `localhost`, `127.x.x.x`, `::1`
 * or `[::1]`. `true`, `undefined`, `0.0.0.0`, `::` and every other name are false.
 */
export function loopbackHost(host: string | boolean | undefined): boolean {
  if (typeof host !== 'string') return false;
  return host === '::1' || isLoopbackName(host);
}

/**
 * The mode the UI server runs in, from its environment and its fixed origins. Each variable is trimmed, blank as unset:
 *
 * 1. `RAIL8_TOKEN` gives token mode; a value of the emergency key's form (`rail8k_`) throws.
 * 2. Else `RAIL8_ADMIN_TOKEN`, the shared token of the bridge, gives token mode; a value of the key's form is skipped
 *    with one line to `log`, since whoever made the first administrator with it is meant to log in.
 * 3. Else session mode: `__Host-rail8-session` when every origin is `https:`, `rail8-session` when every origin is
 *    `http:` on loopback; anything else throws.
 *
 * Token mode throws for an origin off loopback. No refusal names a value.
 */
export function modeOf(env: Env, origins: readonly string[], log: (line: string) => void): Mode {
  const urls = origins.map((origin) => {
    try {
      return new URL(origin);
    } catch {
      throw new Error(`the UI server's origin '${origin}' is not a URL`);
    }
  });
  const token = (variable: 'RAIL8_TOKEN' | 'RAIL8_ADMIN_TOKEN', value: string): Mode => {
    if (!urls.every((url) => isLoopbackName(url.hostname))) {
      throw new Error(`the UI server runs in token mode with ${variable} on loopback only, and one of its origins is not: ${origins.join(', ')}`);
    }
    return { kind: 'token', token: value, variable };
  };
  const own = env.RAIL8_TOKEN?.trim();
  if (own) {
    if (own.startsWith(KEY_PREFIX)) {
      throw new Error('RAIL8_TOKEN holds an emergency key, which the UI server never sends to the node: set a token there, or unset it and log in');
    }
    return token('RAIL8_TOKEN', own);
  }
  const shared = env.RAIL8_ADMIN_TOKEN?.trim();
  if (shared && !shared.startsWith(KEY_PREFIX)) return token('RAIL8_ADMIN_TOKEN', shared);
  if (shared) log('RAIL8_ADMIN_TOKEN holds an emergency key, which the UI server never sends to the node: it runs in session mode, and you log in');
  if (urls.length > 0 && urls.every((url) => url.protocol === 'https:')) return { kind: 'session', cookie: '__Host-rail8-session', secure: true };
  if (urls.length > 0 && urls.every((url) => url.protocol === 'http:' && isLoopbackName(url.hostname))) {
    return { kind: 'session', cookie: 'rail8-session', secure: false };
  }
  throw new Error(`the UI server's origins must all be https:, or all be http: on loopback, for its session's cookie: ${origins.join(', ')}`);
}

/** The values of every cookie named `name` in the request's `Cookie` header. */
function cookiesNamed(header: string | undefined, name: string): string[] {
  if (header === undefined) return [];
  const values: string[] = [];
  for (const pair of header.split(';')) {
    const at = pair.indexOf('=');
    if (at >= 0 && pair.slice(0, at).trim() === name) values.push(pair.slice(at + 1).trim());
  }
  return values;
}

/** The session the request's cookie holds, or null for none, two, or one of another form. */
function cookieSession(mode: Extract<Mode, { kind: 'session' }>, headers: IncomingHttpHeaders): { value: string; id: string } | null {
  const values = cookiesNamed(headers.cookie, mode.cookie);
  if (values.length !== 1) return null;
  const value = values[0]!;
  const id = SESSION_FORM.exec(value)?.[1];
  return id === undefined ? null : { value, id };
}

/**
 * The credential of one request to the node, or why the UI server refuses it itself. Token mode: the token, whatever
 * the request carries. Session mode, in this order:
 *
 * 1. the session is the one cookie of the mode's name of the session's form; none, two or a malformed one give none;
 * 2. any method but GET whose `Rail8-Session` is not that session's id (no header and no session count as different)
 *    is 409 `session-changed`;
 * 3. a GET that carries `Rail8-Session` with any other value is 409 `session-changed`; a GET without it is not checked;
 * 4. no session is 401 `no-credential`;
 * 5. else `Bearer <cookie>`, with the session's id.
 */
export function credentialOf(mode: Mode, method: string, headers: IncomingHttpHeaders): Decision {
  if (mode.kind === 'token') return { authorization: `Bearer ${mode.token}`, session: null };
  const found = cookieSession(mode, headers);
  const named = headers['rail8-session'];
  const changed = { refused: { status: 409, code: 'session-changed', error: 'this tab was shown for another session than the one this browser holds now' } } as const;
  if (method !== 'GET' && (named === undefined || found === null || named !== found.id)) return changed;
  if (method === 'GET' && named !== undefined && named !== found?.id) return changed;
  if (found === null) return { refused: { status: 401, code: 'no-credential', error: 'log in first: this browser holds no session' } };
  return { authorization: `Bearer ${found.value}`, session: found.id };
}

/** What the gate leaves on an admitted request. */
interface Admitted {
  authorization: string;
  session: string | null;
  /** The `Set-Cookie` that clears the session's cookie; null in token mode. */
  clear: string | null;
}

/** The `Set-Cookie` that sets the session's cookie to `value`, or clears it when `value` is empty. */
function cookieHeader(mode: Extract<Mode, { kind: 'session' }>, value: string): string {
  return `${mode.cookie}=${value}; HttpOnly; SameSite=Strict; Path=/${mode.secure ? '; Secure' : ''}${value === '' ? '; Max-Age=0' : ''}`;
}

/** The node ended the caller's session, or refused it (401): `code` is the node's, and `clear` whether its cookie goes. */
export class SessionRefused extends Error {
  readonly [BRAND] = true;
  readonly code: string;
  readonly clear: boolean;
  /** The `Set-Cookie` that clears the cookie, when `clear`. */
  readonly setCookie: string | undefined;

  constructor(code: string, setCookie: string | undefined) {
    super(`the node refused this browser's session (${code}): log in again`);
    this.code = code;
    this.clear = setCookie !== undefined;
    this.setCookie = setCookie;
  }
}

/** Whether `error` is a {@link SessionRefused}, from this copy of this file or any other: it tests the brand. */
export function isSessionRefused(error: unknown): error is SessionRefused {
  return typeof error === 'object' && error !== null && (error as Record<symbol, unknown>)[BRAND] === true;
}

/**
 * The body of one of the UI server's own refusals: `{error, code}`, or under `/api` problem+json of the type
 * `urn:rail8:ui:problem:<code>`, which names the code there, as the mapping studio's contract gives a problem no other
 * member.
 */
function refusalBody(status: number, code: string, error: string, problem: boolean): { type: string; body: string } {
  if (!problem) return { type: 'application/json', body: JSON.stringify({ error, code }) };
  const title = status === 401 ? 'Unauthenticated' : status === 409 ? 'Session changed' : status === 403 ? 'Forbidden' : 'Refused';
  return { type: 'application/problem+json', body: JSON.stringify({ type: `urn:rail8:ui:problem:${code}`, title, status, detail: error }) };
}

function refuse(res: ServerResponse, status: number, code: string, error: string, problem = false, headers: Record<string, string> = {}) {
  const { type, body } = refusalBody(status, code, error, problem);
  res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store', ...headers }).end(body);
}

/** Answers a {@link SessionRefused} as 401, with problem+json when `problem`, clearing the cookie when the session is gone. */
export function refusedSession(res: ServerResponse, error: SessionRefused, problem = false): void {
  refuse(res, 401, error.code, error.message, problem, error.setCookie === undefined ? {} : { 'set-cookie': error.setCookie });
}

/**
 * Why a request does not come from a page of this server, or undefined when it does: it must carry
 * `Sec-Fetch-Site: same-origin`, and an `Origin`, when there is one, from `origins` (never compared with the request's
 * `Host`, which a page on a re-bound domain controls). `what` names what the request is for in the refusal.
 */
export function foreign(headers: IncomingHttpHeaders, origins: readonly string[], what: string): string | undefined {
  if (headers['sec-fetch-site'] !== 'same-origin') {
    return `only this server's own pages may call ${what} through it (Sec-Fetch-Site must be same-origin)`;
  }
  if (headers.origin !== undefined && !origins.includes(headers.origin)) {
    return `the origin ${headers.origin} may not call ${what} through this server`;
  }
  return undefined;
}

const under = (path: string, prefix: string) => path === prefix || path.startsWith(`${prefix}/`) || path.startsWith(`${prefix}?`);

/** The gated prefix of `url`, as it is or as the URL parser resolves it, or undefined. */
function gatedPrefix(url: string): string | undefined {
  let resolved = '';
  try {
    resolved = new URL(url, 'http://localhost').pathname;
  } catch {
    // an unparsable path is gated by its text alone
  }
  return GATED.find((prefix) => under(url, prefix) || under(resolved, prefix));
}

/** The body of `answer`, at most `limit` bytes; null past them, or when it cannot be read. */
async function bodyOf(answer: Response, limit: number): Promise<Buffer | null> {
  if (answer.body === null) return Buffer.alloc(0);
  const chunks: Buffer[] = [];
  let size = 0;
  try {
    for await (const chunk of answer.body as AsyncIterable<Uint8Array>) {
      size += chunk.length;
      if (size > limit) return null;
      chunks.push(Buffer.from(chunk));
    }
  } catch {
    return null;
  }
  return Buffer.concat(chunks);
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

function jsonOf(bytes: Buffer | null): unknown {
  if (bytes === null) return undefined;
  try {
    return JSON.parse(bytes.toString('utf8'));
  } catch {
    return undefined;
  }
}

/** The `code` of a node's refusal, from `{error, code}` or from problem+json. */
function codeOf(json: unknown): string {
  return isRecord(json) && typeof json.code === 'string' ? json.code : 'unknown';
}

/** The node's client for one request, as the gate admitted it. */
export interface NodeCall {
  target: string;
  /** Calls a URL of `target` with this request's credential. */
  send: typeof fetch;
  /** `text` with the credential's secret hidden. */
  redact(text: string): string;
}

/**
 * The node's client for the request `req`, which the gate has admitted; it throws when the gate has not, so a half
 * mounted without the gate in front of it never reaches the node. Its `send` takes a URL of `target` and passes on
 * only `Accept` and `Content-Type`, and `X-Rail8-Actor` in token mode; it sets `Authorization` once, from the gate's
 * decision. It follows no redirect: a 3xx rejects. In session mode a 401 rejects with a {@link SessionRefused}, which
 * clears the cookie for `unknown-credential`, `session-ended` and `blocked`; in token mode every status passes.
 */
export function nodeFor(req: IncomingMessage, target: string, send: typeof fetch = fetch): NodeCall {
  const admitted = (req as unknown as Record<symbol, Admitted | undefined>)[DECIDED];
  if (admitted === undefined) throw new Error('the UI server sends nothing to the node for a request its session gate did not admit');
  const origin = new URL(target).origin;
  const secret = admitted.authorization.slice('Bearer '.length);
  return {
    target,
    redact: (text) => (secret === '' ? text : text.split(secret).join('***')),
    send: async (input, init = {}) => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      if (url.origin !== origin) throw new Error(`the UI server calls only the node at ${origin}, not ${url.origin}`);
      const given = new Headers(init.headers);
      const headers: Record<string, string> = {};
      for (const name of ['accept', 'content-type']) {
        const value = given.get(name);
        if (value !== null) headers[name] = value;
      }
      const actor = given.get('x-rail8-actor');
      if (admitted.session === null && actor !== null) headers['x-rail8-actor'] = actor;
      headers.authorization = admitted.authorization;
      const answer = await send(url, { ...init, headers, redirect: 'manual' });
      if (answer.status >= 300 && answer.status < 400) {
        await answer.body?.cancel().catch(() => undefined);
        throw new Error(`the node at ${url.host} answered ${answer.status}, a redirect, which the UI server does not follow`);
      }
      if (admitted.session !== null && answer.status === 401) {
        const code = codeOf(jsonOf(await bodyOf(answer, REFUSAL_MAX_BYTES)));
        throw new SessionRefused(code, ENDED.has(code) && admitted.clear !== null ? admitted.clear : undefined);
      }
      return answer;
    },
  };
}

/** What `/session` answers: the node's `me`, its public members only, with the session's id. */
function meOf(me: Record<string, unknown>, session: string | null): Record<string, unknown> {
  const shown: Record<string, unknown> = {};
  for (const name of PUBLIC) if (name in me) shown[name] = me[name];
  return { ...shown, session };
}

/** Whether a `Content-Type` names `application/json`, in any case, with or without parameters. */
export const isJson = (contentType: string | undefined) => contentType?.split(';', 1)[0]?.trim().toLowerCase() === 'application/json';

/** The login's body, or the status and code of its refusal. */
async function loginOf(req: IncomingMessage): Promise<{ name: string; password: string } | { status: number; code: string; error: string }> {
  if (!isJson(req.headers['content-type'])) return { status: 415, code: 'unsupported-media-type', error: 'a login is application/json' };
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req as AsyncIterable<Buffer>) {
    size += chunk.length;
    if (size > LOGIN_MAX_BYTES) return { status: 413, code: 'too-large', error: `a login is at most ${LOGIN_MAX_BYTES} bytes` };
    chunks.push(chunk);
  }
  const body = jsonOf(Buffer.concat(chunks));
  if (!isRecord(body) || typeof body.name !== 'string' || typeof body.password !== 'string') {
    return { status: 400, code: 'bad-login', error: 'a login is {"name": "<name>", "password": "<password>"}' };
  }
  return { name: body.name, password: body.password };
}

/** Where the gate sends the node's answers it reads itself, and what it needs to call the node. */
export interface UiSessionOptions {
  mode: Mode;
  /** The development server's own origins; a request with any other `Origin` is refused. */
  origins: readonly string[];
  /** The node's admin API, such as `http://127.0.0.1:8081`. */
  target: string;
  /** The node's client; `fetch` by default. */
  send?: typeof fetch;
  /** Makes the signal that ends one call of the `/session` routes to the node; `AbortSignal.timeout` by default. */
  deadline?: (ms: number) => AbortSignal;
}

/**
 * The UI server's session, a middleware mounted before the `/admin` proxy and every half.
 *
 * It serves the page's own routes, each for this server's pages only (403 `foreign-origin`), with 405 for another
 * method, and `Cache-Control: no-store`; each call to the node ends with the browser's request, or after
 * {@link SESSION_CALL_MS} on `deadline`:
 *
 * - `POST /session {name, password}` logs in: 404 `token-mode` in token mode; 415, 413 over {@link LOGIN_MAX_BYTES},
 *   400 `bad-login`. It sends the node's `POST /admin/sessions` without a credential and answers 200 with what the node
 *   answers of the caller, its session's id and never its secret, setting the cookie. The node's 401, 429 and 503 pass
 *   on with `Retry-After`, the cookie left alone; anything else is 502 `node-unavailable`.
 * - `POST /session/end` logs out: 404 `token-mode` in token mode, the refusals of {@link credentialOf}, then the node's
 *   `POST /admin/me/logout`. The cookie is cleared whatever the node answers: 204 when it answered 204 or 401, else 502.
 * - `GET /session` answers the node's `GET /admin/me` with the session's id (null in token mode). A node's 401 is 401 in
 *   session mode, clearing the cookie as {@link nodeFor} does, and 502 in token mode, naming the token's variable. A 503
 *   passes on with `Retry-After`; anything else is 502.
 *
 * For a request under `/admin`, `/git` or `/api` it refuses what {@link foreign} refuses (403 `foreign-origin`), then
 * what {@link credentialOf} refuses, reading no body and sending nothing; refusals under `/api` are problem+json. In
 * session mode the answer carries `Cache-Control: no-store`. It leaves its decision on the request for
 * {@link nodeFor}, and goes on. Every other path goes on untouched.
 */
export function uiSession(options: UiSessionOptions) {
  const { mode, origins, target } = options;
  const send = options.send ?? fetch;
  const deadline = options.deadline ?? ((ms: number) => AbortSignal.timeout(ms));

  /** Calls the node once for a `/session` route; null when it gave no answer that could be read. */
  const call = async (res: ServerResponse, path: string, init: RequestInit): Promise<{ status: number; json: unknown; retryAfter: string | null } | null> => {
    const closed = new AbortController();
    res.on('close', () => closed.abort());
    try {
      const answer = await send(`${target}${path}`, { ...init, redirect: 'manual', signal: AbortSignal.any([closed.signal, deadline(SESSION_CALL_MS)]) });
      const bytes = await bodyOf(answer, ANSWER_MAX_BYTES);
      if (bytes === null) return null;
      return { status: answer.status, json: jsonOf(bytes), retryAfter: answer.headers.get('retry-after') };
    } catch {
      return null;
    }
  };

  const unavailable = (res: ServerResponse, detail: string, headers: Record<string, string> = {}) =>
    refuse(res, 502, 'node-unavailable', `the node's admin API at ${new URL(target).host} ${detail}`, false, headers);

  const passOn = (res: ServerResponse, answer: { status: number; json: unknown; retryAfter: string | null }, headers: Record<string, string> = {}) => {
    const error = isRecord(answer.json) && typeof answer.json.error === 'string' ? answer.json.error : `the node answered ${answer.status}`;
    refuse(res, answer.status, codeOf(answer.json), error, false, { ...(answer.retryAfter === null ? {} : { 'retry-after': answer.retryAfter }), ...headers });
  };

  const login = async (req: IncomingMessage, res: ServerResponse) => {
    if (mode.kind === 'token') return refuse(res, 404, 'token-mode', `this UI server runs with ${mode.variable} and has no login`);
    const body = await loginOf(req);
    if ('status' in body) return refuse(res, body.status, body.code, body.error);
    const answer = await call(res, '/admin/sessions', {
      method: 'POST',
      headers: { accept: 'application/json', 'content-type': 'application/json' },
      body: JSON.stringify({ name: body.name, password: body.password }),
    });
    if (answer === null) return unavailable(res, 'does not answer');
    if (answer.status === 401 || answer.status === 429 || answer.status === 503) return passOn(res, answer);
    const json = answer.json;
    if (answer.status !== 201 || !isRecord(json) || typeof json.session !== 'string' || typeof json.expiresAt !== 'string' || !isRecord(json.me)) {
      return unavailable(res, `answered the login with ${answer.status}, which is no session`);
    }
    const id = SESSION_FORM.exec(json.session)?.[1];
    if (id === undefined) return unavailable(res, 'answered the login with a session of another form');
    res
      .writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store', 'set-cookie': cookieHeader(mode, json.session) })
      .end(JSON.stringify(meOf(json.me, id)));
  };

  const logout = async (req: IncomingMessage, res: ServerResponse) => {
    if (mode.kind === 'token') return refuse(res, 404, 'token-mode', `this UI server runs with ${mode.variable} and has no logout`);
    const decided = credentialOf(mode, 'POST', req.headers);
    if ('refused' in decided) return refuse(res, decided.refused.status, decided.refused.code, decided.refused.error);
    const answer = await call(res, '/admin/me/logout', { method: 'POST', headers: { accept: 'application/json', authorization: decided.authorization } });
    const clear = { 'set-cookie': cookieHeader(mode, '') };
    if (answer === null) return unavailable(res, 'does not answer; this browser holds the session no more', clear);
    if (answer.status !== 204 && answer.status !== 401) return unavailable(res, `answered the logout with ${answer.status}; this browser holds the session no more`, clear);
    res.writeHead(204, { 'cache-control': 'no-store', ...clear }).end();
  };

  const me = async (req: IncomingMessage, res: ServerResponse) => {
    const decided = credentialOf(mode, 'GET', req.headers);
    if ('refused' in decided) return refuse(res, decided.refused.status, decided.refused.code, decided.refused.error);
    const answer = await call(res, '/admin/me', { headers: { accept: 'application/json', authorization: decided.authorization } });
    if (answer === null) return unavailable(res, 'does not answer');
    if (answer.status === 200 && isRecord(answer.json)) {
      return res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify(meOf(answer.json, decided.session)));
    }
    if (answer.status === 401 && mode.kind === 'session') {
      const code = codeOf(answer.json);
      return refusedSession(res, new SessionRefused(code, ENDED.has(code) ? cookieHeader(mode, '') : undefined));
    }
    if (answer.status === 401 && mode.kind === 'token') return unavailable(res, `refuses ${mode.variable} (401)`);
    if (answer.status === 503) return passOn(res, answer);
    return unavailable(res, `answered ${answer.status}`);
  };

  return (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const url = req.url ?? '';
    const path = url.split('?', 1)[0];
    if (path === '/session' || path === '/session/end') {
      const refused = foreign(req.headers, origins, "the UI server's session");
      if (refused !== undefined) return refuse(res, 403, 'foreign-origin', refused);
      const route = path === '/session/end' ? (req.method === 'POST' ? logout : undefined) : req.method === 'POST' ? login : req.method === 'GET' ? me : undefined;
      if (route === undefined) return refuse(res, 405, 'method-not-allowed', path === '/session' ? '/session takes GET and POST' : '/session/end takes POST');
      route(req, res).catch(() => {
        if (res.headersSent) res.destroy();
        else refuse(res, 500, 'failed', "the UI server's session failed");
      });
      return;
    }
    const prefix = gatedPrefix(url);
    if (prefix === undefined) return next();
    const problem = prefix === '/api';
    const refused = foreign(req.headers, origins, prefix === '/admin' ? 'the admin API' : prefix === '/git' ? 'the git half' : "the mapping studio's API");
    if (refused !== undefined) return refuse(res, 403, 'foreign-origin', refused, problem);
    const decided = credentialOf(mode, req.method ?? 'GET', req.headers);
    if ('refused' in decided) return refuse(res, decided.refused.status, decided.refused.code, decided.refused.error, problem);
    if (mode.kind === 'session') res.setHeader('cache-control', 'no-store');
    const admitted: Admitted = { ...decided, clear: mode.kind === 'session' ? cookieHeader(mode, '') : null };
    (req as unknown as Record<symbol, Admitted>)[DECIDED] = admitted;
    next();
  };
}
