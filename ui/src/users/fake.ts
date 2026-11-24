import { UsersError, type Issued, type Kind, type LiveToken, type Role, type Token, type TokensApi, type User, type UsersApi } from './api';

/**
 * The users a fake node holds, which its acts change in place, and the first passwords it handed out, in order; with
 * `maxDays`, the installation's ceiling of a token's life, 365 days without it.
 */
export interface FakeUsers {
  users: User[];
  firstPasswords: string[];
  maxDays?: number;
}

/**
 * A node's users in memory, acted on by `actor` in a session at `now`. It keeps the node's invariants, each refused with
 * 409 and the node's own words: at least one unblocked person holds the administrator role, a technical account never
 * holds it, nobody blocks themselves, and a technical account has no password. A name taken is 409 and an unknown user
 * 404. Every first password is twenty letters, different each time.
 */
export function fakeUsersApi(state: FakeUsers, actor: string, now: () => Date): UsersApi {
  const at = () => now().toISOString();
  const find = (name: string) => {
    const user = state.users.find((u) => u.name === name);
    if (user === undefined) throw new UsersError(404, `no user ${name}`);
    return user;
  };
  const administrators = (users: readonly User[]) =>
    users.filter((u) => u.kind === 'person' && u.blockedAt === null && u.roles.some((g) => g.role === 'administrator')).length;
  const lastAdministrator = () => new UsersError(409, 'at least one unblocked person holds the administrator role');
  const technicalAdministrator = () => new UsersError(409, 'a technical account never holds the administrator role');
  const firstPassword = () => {
    const n = state.firstPasswords.length;
    const password = Array.from({ length: 20 }, (_, i) => 'abcdefghijklmnopqrstuvwxyz234567'[(n * 7 + i * 13) % 32]).join('');
    state.firstPasswords.push(password);
    return password;
  };
  const replace = (user: User) => {
    state.users = state.users.map((u) => (u.name === user.name ? user : u));
  };
  const grant = (role: Role) => ({ role, grantedAt: at(), grantedBy: actor, grantedVia: 'session' });

  return {
    async list() {
      return structuredClone(state.users).sort((a, b) => a.name.localeCompare(b.name));
    },
    async create(name: string, kind: Kind, roles: readonly Role[]) {
      if (state.users.some((u) => u.name === name)) throw new UsersError(409, `the name ${name} is taken`);
      if (kind === 'technical' && roles.includes('administrator')) throw technicalAdministrator();
      const password = kind === 'person' ? firstPassword() : null;
      state.users = [
        ...state.users,
        { name, kind, roles: roles.map(grant), blockedAt: null, firstPassword: password !== null, createdAt: at(), createdBy: actor, sessions: [], tokens: [] },
      ];
      return password;
    },
    async changeRoles(name, add, remove) {
      const user = find(name);
      if (user.kind === 'technical' && add.includes('administrator')) throw technicalAdministrator();
      const held = new Set(user.roles.map((g) => g.role));
      const added = add.filter((role) => !held.has(role));
      const removed = remove.filter((role) => held.has(role));
      const changed = { ...user, roles: [...user.roles.filter((g) => !removed.includes(g.role)), ...added.map(grant)] };
      if (administrators(state.users.map((u) => (u.name === name ? changed : u))) === 0) throw lastAdministrator();
      replace(changed);
      return { name, roles: changed.roles.map((g) => g.role), added, removed };
    },
    async block(name) {
      const user = find(name);
      if (name === actor) throw new UsersError(409, 'nobody blocks themselves');
      const blocked = { ...user, blockedAt: user.blockedAt ?? at(), sessions: [] };
      if (administrators(state.users.map((u) => (u.name === name ? blocked : u))) === 0) throw lastAdministrator();
      replace(blocked);
    },
    async unblock(name) {
      replace({ ...find(name), blockedAt: null });
    },
    async resetPassword(name) {
      const user = find(name);
      if (user.kind !== 'person') throw new UsersError(409, 'a technical account has no password');
      replace({ ...user, firstPassword: true, sessions: [] });
      return firstPassword();
    },
    async endSessions(name) {
      replace({ ...find(name), sessions: [] });
    },
  };
}

const TOKEN_NAME = /^[a-z0-9][a-z0-9-]{0,63}$/;
const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The tokens of the users of `state`, acted on by `actor` at `now`, whose password is `password`. It keeps the node's
 * rules, each refused with the node's own words: a name of the token's form, 1 day to the ceiling, one live token of a
 * name and 20 per user, a technical account's tokens issued by an administrator and a person's by the person, the
 * password asked again (403), and the ceiling 1 to 365 days, which shortens a live token that would outlive it. A
 * token's id is 16 hex digits, different each time; an id it does not find is 404.
 */
export function fakeTokensApi(state: FakeUsers, actor: string, now: () => Date, password: string): TokensApi {
  let issued = 0;
  const ceiling = () => {
    const maxDays = state.maxDays ?? 365;
    return { maxDays, defaultDays: Math.min(90, maxDays) };
  };
  const live = (users: readonly User[]): Token[] =>
    users
      .flatMap((user) => user.tokens.map((t) => ({ ...t, user: user.name })))
      .filter((t) => Date.parse(t.expiresAt) > now().getTime())
      .sort((a, b) => a.expiresAt.localeCompare(b.expiresAt));
  const find = (name: string) => {
    const user = state.users.find((u) => u.name === name);
    if (user === undefined) throw new UsersError(404, `no user ${name}`);
    return user;
  };
  const issue = (user: User, name: string, days: number): Issued => {
    const { maxDays } = ceiling();
    if (!TOKEN_NAME.test(name)) throw new UsersError(400, `a token's name matches ^[a-z0-9][a-z0-9-]{0,63}$: ${name}`);
    if (!Number.isInteger(days) || days < 1 || days > maxDays) throw new UsersError(400, `a token lives 1 to ${maxDays} days, the installation's ceiling, not ${days}`);
    const held = live([user]);
    if (held.some((t) => t.name === name)) throw new UsersError(409, `${user.name} holds a live token named ${name}`);
    if (held.length >= 20) throw new UsersError(409, `${user.name} holds 20 live tokens, the most a user holds`);
    issued += 1;
    const id = (0xa000_0000_0000 + issued).toString(16).padStart(16, '0');
    const at = now();
    const token: LiveToken = { id, name, createdAt: at.toISOString(), createdBy: actor, expiresAt: new Date(at.getTime() + days * DAY_MS).toISOString(), lastUsedAt: null };
    state.users = state.users.map((u) => (u.name === user.name ? { ...u, tokens: [...u.tokens.filter((t) => t.name !== name), token] } : u));
    return { id, name, expiresAt: token.expiresAt, token: `rail8t_${id}_${'s'.repeat(42)}${issued % 10}` };
  };
  /** Revokes the token `id` of a user `owns` admits; 404 when there is none. */
  const revoke = (id: string, owns: (user: User) => boolean) => {
    const user = state.users.find((u) => owns(u) && u.tokens.some((t) => t.id === id));
    if (user === undefined) throw new UsersError(404, `no token ${id}`);
    state.users = state.users.map((u) => (u === user ? { ...u, tokens: u.tokens.filter((t) => t.id !== id) } : u));
  };

  return {
    async mine() {
      return { tokens: live([find(actor)]), ...ceiling() };
    },
    async issueMine(name, days, given) {
      if (given !== password) throw new UsersError(403, 'the password is wrong');
      return issue(find(actor), name, days);
    },
    async revokeMine(id) {
      revoke(id, (u) => u.name === actor);
    },
    async list(days) {
      const tokens = live(state.users);
      const until = days === undefined ? Infinity : now().getTime() + days * DAY_MS;
      return { tokens: tokens.filter((t) => Date.parse(t.expiresAt) <= until), ...ceiling() };
    },
    async issue(name, tokenName, days) {
      const user = find(name);
      if (user.kind !== 'technical') throw new UsersError(409, 'a person issues their own tokens');
      return issue(user, tokenName, days);
    },
    async revoke(id) {
      revoke(id, () => true);
    },
    async setCeiling(maxDays) {
      if (!Number.isInteger(maxDays) || maxDays < 1 || maxDays > 365) throw new UsersError(400, `the ceiling is 1 to 365 days, not ${maxDays}`);
      state.maxDays = maxDays;
      const latest = (t: LiveToken) => {
        const end = Date.parse(t.createdAt) + maxDays * DAY_MS;
        return end < Date.parse(t.expiresAt) ? new Date(end).toISOString() : t.expiresAt;
      };
      state.users = state.users.map((u) => ({ ...u, tokens: u.tokens.map((t) => ({ ...t, expiresAt: latest(t) })) }));
      return ceiling();
    },
  };
}
