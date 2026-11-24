import { uiFetch } from '../session';

/** The five roles, in the order the screen lists them. */
export const ROLES = ['administrator', 'developer', 'operator', 'message-bodies', 'secret-administrator'] as const;

export type Role = (typeof ROLES)[number];

/** A person, who logs in with a password, or a technical account, which has tokens only. */
export type Kind = 'person' | 'technical';

/** A role a user holds: who granted it, when, and how they had proved who they were (`session`, `emergency-key` or `shared-token`). */
export interface Grant {
  role: Role;
  grantedAt: string;
  grantedBy: string;
  grantedVia: string;
}

/** A live session of a user, by its id. */
export interface LiveSession {
  id: string;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
}

/** A live token of a user, by its id; never its secret. */
export interface LiveToken {
  id: string;
  name: string;
  createdAt: string;
  createdBy: string;
  expiresAt: string;
  lastUsedAt: string | null;
}

/** A live token of any user, as the lists of tokens give it. */
export interface Token extends LiveToken {
  user: string;
}

/** The installation's ceiling of a token's life, and the life of a token whose issuer names none, both in days. */
export interface Ceiling {
  maxDays: number;
  defaultDays: number;
}

/** Live tokens, soonest expiry first, with the ceiling. */
export interface Tokens extends Ceiling {
  tokens: Token[];
}

/** A token just issued: the one answer that carries the token itself, which is never shown again. */
export interface Issued {
  id: string;
  name: string;
  expiresAt: string;
  token: string;
}

/** A user as `GET /admin/users` lists it. No secret is ever part of it. */
export interface User {
  name: string;
  kind: Kind;
  roles: Grant[];
  /** When the user was blocked, or null while they are not. */
  blockedAt: string | null;
  /** True while the account still has the first password an administrator's act gave it. */
  firstPassword: boolean;
  createdAt: string;
  createdBy: string;
  sessions: LiveSession[];
  tokens: LiveToken[];
}

/** What changing roles by difference did: the roles the user holds now, and those that actually changed. */
export interface RolesChanged {
  name: string;
  roles: Role[];
  added: Role[];
  removed: Role[];
}

/** What the screen asks of the node. Every call rejects with a {@link UsersError} when the node refuses it. */
export interface UsersApi {
  /** Every user, by name. */
  list(): Promise<User[]>;
  /** Creates a user with `roles`; gives a person's first password, shown once, and null for a technical account. */
  create(name: string, kind: Kind, roles: readonly Role[]): Promise<string | null>;
  /** Gives the user the roles of `add` and takes those of `remove`. */
  changeRoles(name: string, add: readonly Role[], remove: readonly Role[]): Promise<RolesChanged>;
  block(name: string): Promise<void>;
  unblock(name: string): Promise<void>;
  /** Resets a person's password; gives the new first password, shown once. */
  resetPassword(name: string): Promise<string>;
  /** Ends every session of the user. */
  endSessions(name: string): Promise<void>;
}

/** A refusal by the node: its HTTP status and the text it gave. */
export class UsersError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'UsersError';
  }
}

async function refusal(res: Response): Promise<UsersError> {
  try {
    const body = (await res.json()) as { error?: unknown };
    if (typeof body.error === 'string') return new UsersError(res.status, body.error);
  } catch {
    // no JSON
  }
  return new UsersError(res.status, `the node answered ${res.status}`);
}

const userPath = (name: string) => `/admin/users/${encodeURIComponent(name)}`;

/**
 * Reads `url` through `send`, or posts to it: `body` as JSON when there is one, else nothing, as the acts on one user take
 * none. Gives the answer's JSON, or undefined for a 204; rejects with a {@link UsersError} for a refusal.
 */
async function callNode<T>(send: typeof fetch, url: string, post?: { body?: unknown }): Promise<T> {
  let init: RequestInit = { headers: { accept: 'application/json' } };
  if (post !== undefined && post.body === undefined) init = { ...init, method: 'POST' };
  else if (post !== undefined) {
    init = { method: 'POST', headers: { accept: 'application/json', 'content-type': 'application/json' }, body: JSON.stringify(post.body) };
  }
  const res = await send(url, init);
  if (!res.ok) throw await refusal(res);
  return (res.status === 204 ? undefined : await res.json()) as T;
}

/** The users of the node's admin API, through `send`, the page's `fetch` by default. */
export function httpUsersApi(send: typeof fetch = uiFetch): UsersApi {
  const call = <T>(url: string, post?: { body?: unknown }) => callNode<T>(send, url, post);

  return {
    async list() {
      return (await call<{ items: User[] }>('/admin/users')).items;
    },
    async create(name, kind, roles) {
      const created = await call<{ name: string; firstPassword?: string }>('/admin/users', { body: { name, kind, roles } });
      return created.firstPassword ?? null;
    },
    changeRoles(name, add, remove) {
      return call<RolesChanged>(`${userPath(name)}/roles`, { body: { add, remove } });
    },
    async block(name) {
      await call(`${userPath(name)}/block`, {});
    },
    async unblock(name) {
      await call(`${userPath(name)}/unblock`, {});
    },
    async resetPassword(name) {
      return (await call<{ firstPassword: string }>(`${userPath(name)}/password-reset`, {})).firstPassword;
    },
    async endSessions(name) {
      await call(`${userPath(name)}/sessions/end`, {});
    },
  };
}

/** What the token screens ask of the node. Every call rejects with a {@link UsersError} when the node refuses it. */
export interface TokensApi {
  /** The caller's own live tokens, with the ceiling. */
  mine(): Promise<Tokens>;
  /** Issues a token of the caller's own, asking the caller's password again; `days` within the ceiling. */
  issueMine(name: string, days: number, password: string): Promise<Issued>;
  revokeMine(id: string): Promise<void>;
  /** Every user's live tokens, or those that expire within `days` days, with the ceiling. */
  list(days?: number): Promise<Tokens>;
  /** Issues a token of the technical account `user`. */
  issue(user: string, name: string, days: number): Promise<Issued>;
  /** Revokes any user's token. */
  revoke(id: string): Promise<void>;
  /** Sets the installation's ceiling, 1 to 365 days. */
  setCeiling(maxDays: number): Promise<Ceiling>;
}

/** The tokens of the node's admin API, through `send`, the page's `fetch` by default. */
export function httpTokensApi(send: typeof fetch = uiFetch): TokensApi {
  const token = (id: string) => encodeURIComponent(id);
  return {
    mine: () => callNode<Tokens>(send, '/admin/me/tokens'),
    issueMine: (name, days, password) => callNode<Issued>(send, '/admin/me/tokens', { body: { name, days, password } }),
    revokeMine: (id) => callNode<void>(send, `/admin/me/tokens/${token(id)}/revoke`, {}),
    list: (days) => callNode<Tokens>(send, days === undefined ? '/admin/tokens' : `/admin/tokens?expiringWithin=P${days}D`),
    issue: (user, name, days) => callNode<Issued>(send, `${userPath(user)}/tokens`, { body: { name, days } }),
    revoke: (id) => callNode<void>(send, `/admin/tokens/${token(id)}/revoke`, {}),
    setCeiling: (maxDays) => callNode<Ceiling>(send, '/admin/tokens/ceiling', { body: { maxDays } }),
  };
}
