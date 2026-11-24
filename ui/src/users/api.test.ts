import { describe, expect, it } from 'vitest';
import { httpTokensApi, httpUsersApi, UsersError, type User } from './api';

/** A fetch that records every call and answers each with the next of `answers`. */
function fakeFetch(...answers: Response[]) {
  const calls: { url: string; init?: RequestInit }[] = [];
  const send = (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init });
    const next = answers.shift();
    return next === undefined ? Promise.reject(new Error('no answer left')) : Promise.resolve(next);
  };
  return { send: send as typeof fetch, calls };
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

const ANNA: User = {
  name: 'anna',
  kind: 'person',
  roles: [{ role: 'operator', grantedAt: '2026-11-20T09:00:00Z', grantedBy: 'alice', grantedVia: 'session' }],
  blockedAt: null,
  firstPassword: false,
  createdAt: '2026-11-20T09:00:00Z',
  createdBy: 'alice',
  sessions: [],
  tokens: [],
};

describe('httpUsersApi', () => {
  it('lists the users', async () => {
    const { send, calls } = fakeFetch(json(200, { items: [ANNA] }));
    expect(await httpUsersApi(send).list()).toEqual([ANNA]);
    expect(calls).toEqual([{ url: '/admin/users', init: { headers: { accept: 'application/json' } } }]);
  });

  it('creates a person and gives the first password, and a technical account with none', async () => {
    const { send, calls } = fakeFetch(json(201, { name: 'bob', firstPassword: 'abcdefghijklmnopqrst' }), json(201, { name: 'ci-deploy' }));
    const api = httpUsersApi(send);
    expect(await api.create('bob', 'person', ['operator'])).toBe('abcdefghijklmnopqrst');
    expect(await api.create('ci-deploy', 'technical', [])).toBeNull();
    expect(calls[0]).toEqual({
      url: '/admin/users',
      init: {
        method: 'POST',
        headers: { accept: 'application/json', 'content-type': 'application/json' },
        body: JSON.stringify({ name: 'bob', kind: 'person', roles: ['operator'] }),
      },
    });
    expect(calls[1]?.init?.body).toBe(JSON.stringify({ name: 'ci-deploy', kind: 'technical', roles: [] }));
  });

  it('changes roles by difference', async () => {
    const changed = { name: 'anna', roles: ['developer'], added: ['developer'], removed: ['operator'] };
    const { send, calls } = fakeFetch(json(200, changed));
    expect(await httpUsersApi(send).changeRoles('anna', ['developer'], ['operator'])).toEqual(changed);
    expect(calls[0]?.url).toBe('/admin/users/anna/roles');
    expect(calls[0]?.init?.body).toBe(JSON.stringify({ add: ['developer'], remove: ['operator'] }));
  });

  it('blocks, unblocks, resets a password and ends sessions with a POST that has no body', async () => {
    const { send, calls } = fakeFetch(json(200, { name: 'a.b' }), json(200, { name: 'a.b' }), json(200, { firstPassword: 'p'.repeat(20) }), json(200, { name: 'a.b' }));
    const api = httpUsersApi(send);
    await api.block('a.b');
    await api.unblock('a.b');
    expect(await api.resetPassword('a.b')).toBe('p'.repeat(20));
    await api.endSessions('a.b');
    expect(calls.map((c) => c.url)).toEqual(['/admin/users/a.b/block', '/admin/users/a.b/unblock', '/admin/users/a.b/password-reset', '/admin/users/a.b/sessions/end']);
    for (const call of calls) expect(call.init).toEqual({ headers: { accept: 'application/json' }, method: 'POST' });
  });

  it('rejects with the status and the text of a refusal', async () => {
    const { send } = fakeFetch(
      json(409, { error: 'at least one unblocked person holds the administrator role' }),
      json(403, { error: 'roles.change needs a session', code: 'session-required', needs: ['administrator'] }),
      new Response('gateway', { status: 502 }),
    );
    const api = httpUsersApi(send);
    await expect(api.block('alice')).rejects.toEqual(new UsersError(409, 'at least one unblocked person holds the administrator role'));
    await expect(api.changeRoles('anna', ['developer'], [])).rejects.toEqual(new UsersError(403, 'roles.change needs a session'));
    await expect(api.list()).rejects.toMatchObject({ status: 502, message: 'the node answered 502' });
  });
});

describe('httpTokensApi', () => {
  const TOKEN = { id: '0123456789abcdef', user: 'alice', name: 'laptop', createdAt: '2026-11-20T09:00:00Z', createdBy: 'alice', expiresAt: '2027-02-18T09:00:00Z', lastUsedAt: null };
  const ISSUED = { id: '0123456789abcdef', name: 'laptop', expiresAt: '2027-02-18T09:00:00Z', token: `rail8t_0123456789abcdef_${'s'.repeat(43)}` };
  const listed = { tokens: [TOKEN], maxDays: 365, defaultDays: 90 };
  const post = (body: unknown) => ({ method: 'POST', headers: { accept: 'application/json', 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const get = { headers: { accept: 'application/json' } };
  const bare = { headers: { accept: 'application/json' }, method: 'POST' };

  it("reads, issues and revokes the caller's own tokens, the password asked again", async () => {
    const { send, calls } = fakeFetch(json(200, listed), json(201, ISSUED), new Response(null, { status: 204 }));
    const api = httpTokensApi(send);
    expect(await api.mine()).toEqual(listed);
    expect(await api.issueMine('laptop', 90, 'my password')).toEqual(ISSUED);
    expect(await api.revokeMine('0123456789abcdef')).toBeUndefined();
    expect(calls).toEqual([
      { url: '/admin/me/tokens', init: get },
      { url: '/admin/me/tokens', init: post({ name: 'laptop', days: 90, password: 'my password' }) },
      { url: '/admin/me/tokens/0123456789abcdef/revoke', init: bare },
    ]);
  });

  it("lists every user's tokens, those expiring within days, the ceiling, and issues, revokes and sets the ceiling", async () => {
    const { send, calls } = fakeFetch(
      json(200, listed),
      json(200, listed),
      json(200, listed),
      json(201, ISSUED),
      new Response(null, { status: 204 }),
      json(200, { maxDays: 30, defaultDays: 30 }),
    );
    const api = httpTokensApi(send);
    expect(await api.list()).toEqual(listed);
    expect(await api.list(14)).toEqual(listed);
    expect(await api.ceiling()).toEqual({ maxDays: 365, defaultDays: 90 });
    expect(await api.issue('ci deploy', 'nightly', 30)).toEqual(ISSUED);
    await api.revoke('fedcba9876543210');
    expect(await api.setCeiling(30)).toEqual({ maxDays: 30, defaultDays: 30 });
    expect(calls).toEqual([
      { url: '/admin/tokens', init: get },
      { url: '/admin/tokens?expiringWithin=P14D', init: get },
      { url: '/admin/tokens?expiringWithin=P1D', init: get },
      { url: '/admin/users/ci%20deploy/tokens', init: post({ name: 'nightly', days: 30 }) },
      { url: '/admin/tokens/fedcba9876543210/revoke', init: bare },
      { url: '/admin/tokens/ceiling', init: post({ maxDays: 30 }) },
    ]);
  });

  it('rejects with the status and the text of a refusal', async () => {
    const { send } = fakeFetch(json(403, { error: 'the password is wrong', code: 'wrong-password', needs: [] }), json(404, { error: 'no token abc' }));
    const api = httpTokensApi(send);
    await expect(api.issueMine('laptop', 90, 'not it')).rejects.toEqual(new UsersError(403, 'the password is wrong'));
    await expect(api.revoke('abc')).rejects.toEqual(new UsersError(404, 'no token abc'));
  });
});
