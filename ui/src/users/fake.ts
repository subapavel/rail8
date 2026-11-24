import { UsersError, type Kind, type Role, type User, type UsersApi } from './api';

/** The users a fake node holds, which its acts change in place, and the first passwords it handed out, in order. */
export interface FakeUsers {
  users: User[];
  firstPasswords: string[];
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
