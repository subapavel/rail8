/**
 * Who uses the page, and the page's `fetch`.
 *
 * The UI server runs in one of two modes. In token mode it sends every call to the node with its own token, and
 * `GET /session` names who that token is, with `session: null`. In session mode it sends each call with the session of
 * the browser's cookie, which the page never sees: the page knows only the session's id, which `GET /session` and the
 * login give, keeps it in memory and names it on every call in the header `Rail8-Session`, so that a tab shown to one
 * user never reads or writes as another.
 *
 * `uiFetch` is the page's `fetch`. In token mode it is the browser's. In session mode:
 * - A 401 means the session ended. The page asks for the login again over its open tabs, and holds the call. From the
 *   first 401 on nothing new is sent; every new call is held too.
 * - A 409 `session-changed` means the browser holds another session than the page names, after a login in another tab.
 *   The page reads `GET /session`: for the same user it takes the new id and roles and sends the call again once, and a
 *   second 409 reloads the page; for another user it reloads; when no session is left it holds the call as after a 401.
 * - A held call keeps its method, its headers and its body; only `Rail8-Session` changes. At most 100 calls and 32 MiB of
 *   their bodies are held; beyond that a call gets its 401. A call whose signal aborts is no longer held and rejects
 *   with the signal's reason.
 * - The login over the tabs is sent once every call sent under the ended session has answered, or after 10 s, so that
 *   a late answer of the ended session cannot clear the cookie of the new one. The same user logged in again sends
 *   each held call once more; a first password asks for the change first; another user reloads the page, and no held
 *   call is settled.
 *
 * After a change of the caller's own roles the page reads `GET /session` again, the same way, so that what it allows
 * follows the new roles at once.
 *
 * The session's own calls, `GET /session`, the login, the password change and the logout, go straight out and are
 * never held.
 */

/** Who uses the page, as `GET /session` and the login answer. */
export interface Me {
  name: string;
  kind: string;
  roles: string[];
  /** The actions the caller may do. */
  actions: string[];
  /** Each action the caller may not do, with why: its code and the roles that may. */
  refused: Record<string, Refusal>;
  via: string;
  /** True while the account still has the password an administrator's act gave it, which must be changed first. */
  firstPassword: boolean;
  expiresAt: string | null;
  emergencyKey: boolean;
  /** The id of the browser's session; null in token mode. */
  session: string | null;
}

/** Why an action is refused: `not-allowed`, `session-required` or `password-change-required`, and the roles that may. */
export interface Refusal {
  code: string;
  needs: string[];
}

/** Why the login or `GET /session` was refused, with the wait the server asks for, as the text of `Retry-After`. */
export interface Refused {
  code: string;
  retryAfter?: string;
}

export type SessionState =
  | { kind: 'starting' }
  | { kind: 'failed'; error: string; retryAfter?: string }
  | { kind: 'out'; refusal?: Refused }
  | { kind: 'in'; me: Me }
  /** The session ended while tabs were open: the login, or the first password's change, is asked over them. */
  | { kind: 'asking'; me: Me; refusal?: Refused };

export interface SessionClient {
  /** The page's `fetch`. */
  fetch: typeof fetch;
  state(): SessionState;
  /** Calls `listener` on every change of the state; gives what stops that. */
  subscribe(listener: () => void): () => void;
  /** Reads `GET /session`: `in`, `out`, or `failed` on a 5xx or no answer within 10 s. */
  start(): Promise<void>;
  /** Logs in. A refusal goes into the state, with its code and its Retry-After. */
  login(name: string, password: string): Promise<void>;
  /** Changes the password. Rejects with a `PasswordRefused` that says why when the node refuses it. */
  changePassword(current: string, next: string): Promise<void>;
  /** Ends the session and reloads the page. */
  logout(): Promise<void>;
  /**
   * Reads `GET /session` again while logged in, after a change of the caller's own roles: the same user's answer gives
   * the new roles and actions, another user reloads the page, a 401 asks for the login, and no answer keeps the state.
   */
  refresh(): Promise<void>;
  /** True when the caller may do `action`, else why not. */
  may(action: string): true | Refusal;
}

/** The node's refusal of a password change: its code (`wrong-password`, `too-many-attempts`, …) and what it said. */
export class PasswordRefused extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly retryAfter?: string,
  ) {
    super(message);
  }
}

export const SESSION_HEADER = 'Rail8-Session';
export const HELD_MAX = 100;
export const HELD_MAX_BYTES = 32 * 1024 * 1024;
/** How long the start waits for `GET /session`, and the login over the tabs for the calls of the ended session. */
export const WAIT_MS = 10_000;

const JSON_HEADERS = { accept: 'application/json', 'content-type': 'application/json' };
const NOT_ALLOWED: Refusal = { code: 'not-allowed', needs: [] };

/** A held call: what sends it again, what gives it back, and its body's size. */
interface Held {
  bytes: number;
  release: () => void;
  refuse: () => void;
}

/** The size of a body to hold: infinite for one that cannot be sent twice, such as a stream. */
function bytesOf(body: BodyInit | null | undefined): number {
  if (body === null || body === undefined) return 0;
  if (typeof body === 'string') return new TextEncoder().encode(body).length;
  if (body instanceof Blob) return body.size;
  if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) return body.byteLength;
  if (body instanceof URLSearchParams) return new TextEncoder().encode(body.toString()).length;
  return Infinity;
}

/** The code of a refusal: its `code` member, or the last part of a problem's `type`; null when it has none. */
async function codeOf(response: Response): Promise<string | null> {
  try {
    const body = (await response.clone().json()) as { code?: unknown; type?: unknown };
    if (typeof body.code === 'string') return body.code;
    if (typeof body.type === 'string' && body.type.includes(':problem:')) return body.type.slice(body.type.lastIndexOf(':') + 1);
  } catch {
    // no JSON
  }
  return null;
}

async function errorOf(response: Response): Promise<string> {
  try {
    const body = (await response.clone().json()) as { error?: unknown; detail?: unknown };
    if (typeof body.error === 'string') return body.error;
    if (typeof body.detail === 'string') return body.detail;
  } catch {
    // no JSON
  }
  return `the UI server answered ${response.status}`;
}

const retryAfterOf = (response: Response) => response.headers.get('retry-after') ?? undefined;

function unauthorized(): Response {
  return new Response(JSON.stringify({ error: 'log in first', code: 'no-credential' }), {
    status: 401,
    headers: { 'content-type': 'application/json' },
  });
}

/**
 * The session of a page, sending through `send`, reloading the page with `reload`, and waiting with `timer`, which
 * resolves after the milliseconds it is given.
 */
export function sessionClient(deps: { send: typeof fetch; reload: () => void; timer: (ms: number) => Promise<void> }): SessionClient {
  const { send, reload, timer } = deps;
  let current: SessionState = { kind: 'starting' };
  const listeners = new Set<() => void>();
  // the id the page names: null until a session-mode answer, and in token mode
  let id: string | null = null;
  // set by the first 401 of a call: from then on nothing new is sent
  let stopped = false;
  const held: Held[] = [];
  let heldBytes = 0;
  // the calls sent and not yet answered, for the login over the tabs to wait for
  const inFlight = new Set<Promise<unknown>>();
  let rechecking: Promise<'same' | 'ended' | 'other' | 'unknown'> | null = null;

  const set = (next: SessionState) => {
    current = next;
    listeners.forEach((listener) => listener());
  };
  const adopt = (me: Me) => {
    id = me.session;
  };

  /** The session ended: asks for the login over the open tabs. */
  const ended = () => {
    stopped = true;
    if (current.kind === 'in') set({ kind: 'asking', me: current.me });
  };

  const release = () => {
    stopped = false;
    heldBytes = 0;
    held.splice(0).forEach((call) => call.release());
  };

  /** Holds a call until the same user logs in again; gives `answer`, or a 401, when it cannot be held. */
  const hold = (input: RequestInfo | URL, init: RequestInit | undefined, answer: Response | null): Promise<Response> => {
    const bytes = bytesOf(init?.body);
    if (held.length >= HELD_MAX || heldBytes + bytes > HELD_MAX_BYTES) return Promise.resolve(answer ?? unauthorized());
    const signal = init?.signal ?? null;
    if (signal?.aborted) return Promise.reject(signal.reason);
    return new Promise<Response>((resolve, reject) => {
      const call: Held = {
        bytes,
        release: () => {
          signal?.removeEventListener('abort', abort);
          named(input, init, true).then(resolve, reject);
        },
        refuse: () => resolve(answer ?? unauthorized()),
      };
      const abort = () => {
        const at = held.indexOf(call);
        if (at !== -1) {
          held.splice(at, 1);
          heldBytes -= bytes;
        }
        reject(signal?.reason);
      };
      signal?.addEventListener('abort', abort, { once: true });
      held.push(call);
      heldBytes += bytes;
    });
  };

  /** Reads `GET /session` after a 409, or after a change of the caller's own roles; one read at a time answers every call that asked. */
  const recheck = () => {
    rechecking ??= (async () => {
      try {
        const response = await send('/session', { headers: { accept: 'application/json' } });
        if (response.status === 401) {
          ended();
          return 'ended' as const;
        }
        if (response.status !== 200) return 'unknown' as const;
        const me = (await response.json()) as Me;
        const shown = current.kind === 'in' || current.kind === 'asking' ? current.me : null;
        if (shown === null || me.name !== shown.name) {
          reload();
          return 'other' as const;
        }
        adopt(me);
        if (current.kind === 'in') set({ kind: 'in', me });
        return 'same' as const;
      } catch {
        return 'unknown' as const;
      } finally {
        rechecking = null;
      }
    })();
    return rechecking;
  };

  /** Sends a call naming the session; `again` when it is sent for the second time, after a 409 or once held. */
  const named = async (input: RequestInfo | URL, init: RequestInit | undefined, again: boolean): Promise<Response> => {
    const headers = new Headers(init?.headers);
    if (id === null) headers.delete(SESSION_HEADER);
    else headers.set(SESSION_HEADER, id);
    const sent = send(input, { ...init, headers });
    inFlight.add(sent);
    let response: Response;
    try {
      response = await sent;
    } finally {
      inFlight.delete(sent);
    }
    if (response.status === 401) {
      ended();
      return again ? response : hold(input, init, response);
    }
    if (response.status === 409 && (await codeOf(response)) === 'session-changed') {
      if (again) {
        reload();
        return response;
      }
      const found = await recheck();
      if (found === 'same') return named(input, init, true);
      if (found === 'ended') return hold(input, init, response);
    }
    return response;
  };

  const fetch_: typeof fetch = (input, init) => {
    const session = (current.kind === 'in' || current.kind === 'asking') && id !== null;
    if (!session) return send(input, init);
    if (stopped) return hold(input, init, null);
    return named(input, init, false);
  };

  const refuse = (refusal: Refused) => {
    if (current.kind === 'asking') set({ ...current, refusal });
    else set({ kind: 'out', refusal });
  };

  /** `GET /session` for the session's own needs: the answer, or null without one. */
  const readSession = async (): Promise<Me | null> => {
    try {
      const response = await send('/session', { headers: { accept: 'application/json' } });
      return response.status === 200 ? ((await response.json()) as Me) : null;
    } catch {
      return null;
    }
  };

  return {
    fetch: fetch_,
    state: () => current,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    async start() {
      set({ kind: 'starting' });
      const stop = new AbortController();
      const late = timer(WAIT_MS).then(() => null);
      let response: Response | null;
      try {
        response = await Promise.race([send('/session', { headers: { accept: 'application/json' }, signal: stop.signal }), late]);
      } catch {
        set({ kind: 'failed', error: 'the UI server did not answer' });
        return;
      }
      if (response === null) {
        stop.abort();
        set({ kind: 'failed', error: 'the UI server did not answer within 10 s' });
        return;
      }
      if (response.status === 200) {
        const me = (await response.json()) as Me;
        adopt(me);
        set({ kind: 'in', me });
      } else if (response.status === 401) {
        const code = await codeOf(response);
        set(code === null || code === 'no-credential' ? { kind: 'out' } : { kind: 'out', refusal: { code } });
      } else {
        set({ kind: 'failed', error: await errorOf(response), retryAfter: retryAfterOf(response) });
      }
    },

    async login(name, password) {
      const asked = current.kind === 'asking' ? current.me : null;
      await Promise.race([Promise.allSettled([...inFlight]), timer(WAIT_MS)]);
      let response: Response;
      try {
        response = await send('/session', { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ name, password }) });
      } catch {
        refuse({ code: 'node-unavailable' });
        return;
      }
      if (response.status !== 200) {
        refuse({ code: (await codeOf(response)) ?? String(response.status), retryAfter: retryAfterOf(response) });
        return;
      }
      const me = (await response.json()) as Me;
      if (asked !== null && me.name !== asked.name) {
        reload();
        return;
      }
      adopt(me);
      if (asked !== null && me.firstPassword) {
        set({ kind: 'asking', me });
        return;
      }
      set({ kind: 'in', me });
      if (asked !== null) release();
    },

    async changePassword(currentPassword, next) {
      const headers: Record<string, string> = { ...JSON_HEADERS };
      if (id !== null) headers[SESSION_HEADER] = id;
      let response: Response;
      try {
        response = await send('/admin/me/password', { method: 'POST', headers, body: JSON.stringify({ current: currentPassword, next }) });
      } catch {
        throw new PasswordRefused('node-unavailable', 'the UI server did not answer');
      }
      if (response.status !== 204) {
        throw new PasswordRefused((await codeOf(response)) ?? String(response.status), await errorOf(response), retryAfterOf(response));
      }
      const me = await readSession();
      if (me === null || me.session !== id || me.firstPassword) {
        reload();
        return;
      }
      const asking = current.kind === 'asking';
      set({ kind: 'in', me });
      if (asking) release();
    },

    async logout() {
      const headers: Record<string, string> = id === null ? {} : { [SESSION_HEADER]: id };
      try {
        await send('/session/end', { method: 'POST', headers });
      } catch {
        // the page reloads either way
      }
      held.splice(0).forEach((call) => call.refuse());
      reload();
    },

    async refresh() {
      if (current.kind === 'in') await recheck();
    },

    may(action) {
      const me = current.kind === 'in' || current.kind === 'asking' ? current.me : null;
      if (me === null) return NOT_ALLOWED;
      if (me.actions.includes(action)) return true;
      return me.refused?.[action] ?? NOT_ALLOWED;
    },
  };
}

/** The words for why an action is refused. */
export function reasonOf(refusal: Refusal): string {
  switch (refusal.code) {
    case 'session-required':
      return 'Needs a person logged in';
    case 'password-change-required':
      return 'Change the first password first';
    default:
      return refusal.needs.length > 0 ? `Needs the role ${refusal.needs.join(' or ')}` : 'Not allowed for this account';
  }
}

/** The page's session. */
export const pageSession: SessionClient = sessionClient({
  send: (input, init) => fetch(input, init),
  reload: () => location.reload(),
  timer: (ms) => new Promise((done) => setTimeout(done, ms)),
});

/** The page's `fetch`: every page client's default `send`. */
export const uiFetch: typeof fetch = (input, init) => pageSession.fetch(input, init);
