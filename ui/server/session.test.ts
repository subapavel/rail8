import { createServer, request, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { gatedRequest, SESSION, SESSION_MODE, tokenMode } from './gatedRequest';
import { credentialOf, heldDevice, isSessionRefused, loopbackHost, modeOf, nodeFor, SessionRefused, uiSession, type Mode } from './session';

const HTTP = ['http://localhost:5173', 'http://127.0.0.1:5173'];
const ID = '0123456789abcdef';
const OTHER = `rail8s_fedcba9876543210_${'o'.repeat(43)}`;
const DEVICE = `rail8d_00112233445566aa_${'d'.repeat(43)}`;
const NEW_DEVICE = `rail8d_ffeeddccbbaa0099_${'n'.repeat(43)}`;

describe('modeOf', () => {
  const lines: string[] = [];
  const mode = (env: Record<string, string>, origins = HTTP) => modeOf(env, origins, (line) => lines.push(line));

  it('is token mode with RAIL8_TOKEN, before RAIL8_ADMIN_TOKEN, each trimmed', () => {
    expect(mode({ RAIL8_TOKEN: ' own ', RAIL8_ADMIN_TOKEN: 'shared' })).toEqual({ kind: 'token', token: 'own', variable: 'RAIL8_TOKEN' });
    expect(mode({ RAIL8_ADMIN_TOKEN: ' shared ' })).toEqual({ kind: 'token', token: 'shared', variable: 'RAIL8_ADMIN_TOKEN' });
    expect(mode({ RAIL8_TOKEN: '  ', RAIL8_ADMIN_TOKEN: 'shared' })).toEqual({ kind: 'token', token: 'shared', variable: 'RAIL8_ADMIN_TOKEN' });
  });

  it('is session mode without a token, blank counting as none, with the cookie of the origins', () => {
    expect(mode({})).toEqual({ kind: 'session', cookie: 'rail8-session', secure: false });
    expect(mode({ RAIL8_TOKEN: ' ', RAIL8_ADMIN_TOKEN: '' })).toEqual({ kind: 'session', cookie: 'rail8-session', secure: false });
    expect(mode({}, ['https://ui.example', 'https://localhost:5173'])).toEqual({ kind: 'session', cookie: '__Host-rail8-session', secure: true });
    expect(mode({}, ['http://[::1]:5173'])).toEqual({ kind: 'session', cookie: 'rail8-session', secure: false });
  });

  it('never sends an emergency key: RAIL8_TOKEN of its form stops the server, RAIL8_ADMIN_TOKEN of its form is skipped with one line', () => {
    const key = `rail8k_${'k'.repeat(43)}`;
    expect(() => mode({ RAIL8_TOKEN: key })).toThrow(/RAIL8_TOKEN holds an emergency key/);
    expect(() => mode({ RAIL8_TOKEN: key })).toThrow(/^(?!.*kkkk)/);
    lines.length = 0;
    expect(mode({ RAIL8_ADMIN_TOKEN: key })).toEqual({ kind: 'session', cookie: 'rail8-session', secure: false });
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatch(/session mode/);
    expect(lines[0]).not.toContain('kkkk');
  });

  it.each([
    [['http://localhost:5173', 'https://ui.example']],
    [['http://ui.example:5173']],
    [['http://0.0.0.0:5173']],
    [[]],
  ])('refuses the origins %j for session mode', (origins) => {
    expect(() => mode({}, origins)).toThrow(/origins must all be https:, or all be http: on loopback/);
  });

  it('refuses token mode with an origin off loopback, and an origin that is no URL', () => {
    expect(() => mode({ RAIL8_TOKEN: 't' }, ['https://ui.example'])).toThrow(/token mode with RAIL8_TOKEN on loopback only/);
    expect(() => mode({ RAIL8_ADMIN_TOKEN: 't' }, ['http://localhost:5173', 'http://10.0.0.5:5173'])).toThrow(/token mode with RAIL8_ADMIN_TOKEN/);
    expect(mode({ RAIL8_TOKEN: 't' }, ['https://localhost:5173'])).toMatchObject({ kind: 'token' });
    expect(() => mode({}, ['localhost'])).toThrow(/is not a URL/);
  });
});

describe('loopbackHost', () => {
  it.each(['localhost', '127.0.0.1', '127.8.0.1', '::1', '[::1]'])('takes %s', (host) => {
    expect(loopbackHost(host)).toBe(true);
  });

  it.each([true, false, undefined, '0.0.0.0', '::', '', 'ui.example', '10.0.0.5', 'localhost.', '128.0.0.1'])('refuses %s', (host) => {
    expect(loopbackHost(host)).toBe(false);
  });
});

describe('credentialOf', () => {
  const cookie = (...values: string[]) => ({ cookie: values.map((v) => `rail8-session=${v}`).join('; ') });

  it('is the token in token mode, whatever the request carries', () => {
    const headers = { ...cookie(SESSION), 'rail8-session': 'other', authorization: 'Bearer browser' };
    expect(credentialOf(tokenMode('t'), 'POST', headers)).toEqual({ authorization: 'Bearer t', session: null });
    expect(credentialOf(tokenMode('t'), 'GET', {})).toEqual({ authorization: 'Bearer t', session: null });
  });

  it('is the cookie in session mode, with the session id read by position', () => {
    expect(credentialOf(SESSION_MODE, 'GET', cookie(SESSION))).toEqual({ authorization: `Bearer ${SESSION}`, session: ID });
    expect(credentialOf(SESSION_MODE, 'GET', { cookie: `a=1; rail8-session=${SESSION}; b=2` })).toEqual({ authorization: `Bearer ${SESSION}`, session: ID });
    expect(credentialOf(SESSION_MODE, 'POST', { ...cookie(SESSION), 'rail8-session': ID })).toEqual({ authorization: `Bearer ${SESSION}`, session: ID });
  });

  it.each([
    ['no cookie', {}],
    ['another cookie only', { cookie: `__Host-rail8-session=${SESSION}` }],
    ['two cookies of its name', cookie(SESSION, SESSION)],
    ['a malformed one', cookie(`rail8s_0123_${'a'.repeat(43)}`)],
    ['a token in it', cookie(`rail8t_${ID}_${'a'.repeat(43)}`)],
    ['an id of capitals', cookie(`rail8s_0123456789ABCDEF_${'a'.repeat(43)}`)],
  ])('is 401 no-credential for a GET with %s', (_, headers: IncomingHttpHeaders) => {
    expect(credentialOf(SESSION_MODE, 'GET', headers)).toEqual({ refused: expect.objectContaining({ status: 401, code: 'no-credential' }) });
  });

  it.each(['POST', 'PUT', 'DELETE', 'HEAD'])('is 409 session-changed for %s naming another session, none, or with no session', (method) => {
    const changed = { refused: expect.objectContaining({ status: 409, code: 'session-changed' }) };
    expect(credentialOf(SESSION_MODE, method, { ...cookie(SESSION), 'rail8-session': 'fedcba9876543210' })).toEqual(changed);
    expect(credentialOf(SESSION_MODE, method, cookie(SESSION))).toEqual(changed);
    expect(credentialOf(SESSION_MODE, method, { 'rail8-session': ID })).toEqual(changed);
    expect(credentialOf(SESSION_MODE, method, {})).toEqual(changed);
    expect(credentialOf(SESSION_MODE, method, { ...cookie(SESSION, SESSION), 'rail8-session': ID })).toEqual(changed);
  });

  it('checks the header of a GET that carries one, and not one without', () => {
    const changed = { refused: expect.objectContaining({ status: 409, code: 'session-changed' }) };
    expect(credentialOf(SESSION_MODE, 'GET', { ...cookie(OTHER), 'rail8-session': ID })).toEqual(changed);
    expect(credentialOf(SESSION_MODE, 'GET', { 'rail8-session': ID })).toEqual(changed);
    expect(credentialOf(SESSION_MODE, 'GET', cookie(OTHER))).toEqual({ authorization: `Bearer ${OTHER}`, session: 'fedcba9876543210' });
  });
});

describe('uiSession', () => {
  const servers: Server[] = [];

  afterEach(async () => {
    await Promise.all(servers.splice(0).map((s) => new Promise((done) => s.close(done))));
  });

  /** What the fake node was sent, and how it answers: by path, a status and a JSON body, with headers. */
  interface Sent {
    method: string;
    path: string;
    headers: IncomingHttpHeaders;
    body: string;
  }
  type Answer = { status: number; json?: unknown; headers?: Record<string, string> };

  /** The gate in `mode` over a recording `send`; whatever it lets through is answered 299 `next` by the next handler. */
  async function gate(mode: Mode, answers: Record<string, Answer> = {}, deadline?: (ms: number) => AbortSignal) {
    const sent: Sent[] = [];
    const send = (async (input: string | URL, init: RequestInit = {}) => {
      const url = new URL(String(input));
      sent.push({ method: init.method ?? 'GET', path: url.pathname, headers: Object.fromEntries(new Headers(init.headers)), body: String(init.body ?? '') });
      const answer = answers[url.pathname];
      if (answer === undefined) throw new Error('no answer');
      if (init.redirect !== 'manual') throw new Error('a redirect would be followed');
      return new Response(answer.json === undefined ? null : JSON.stringify(answer.json), { status: answer.status, headers: answer.headers });
    }) as typeof fetch;
    const middleware = uiSession({ mode, origins: HTTP, target: 'http://127.0.0.1:8081', send, deadline });
    const server = createServer((req, res) => middleware(req, res, () => res.writeHead(299).end('next')));
    servers.push(server);
    await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
    const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    const call = (method: string, path: string, headers: Record<string, string> = {}, body?: string) =>
      new Promise<{ status: number; headers: IncomingHttpHeaders; text: string; json: Record<string, unknown> }>((done, fail) => {
        const req = request(base + path, { method, headers: { 'sec-fetch-site': 'same-origin', ...headers } }, (res) => {
          let text = '';
          res.setEncoding('utf8').on('data', (chunk: string) => (text += chunk));
          res.on('end', () => {
            let json = {};
            try {
              json = JSON.parse(text) as Record<string, unknown>;
            } catch {
              // a 204 or `next`
            }
            done({ status: res.statusCode ?? 0, headers: res.headers, text, json });
          });
        });
        req.on('error', fail).end(body);
      });
    return { sent, call };
  }

  const holding = { cookie: `rail8-session=${SESSION}` };
  const naming = { ...holding, 'rail8-session': ID };
  const json = { 'content-type': 'application/json' };
  const me = { name: 'anna', kind: 'person', roles: ['operator'], actions: ['message.read'], refused: { 'package.deploy': { code: 'not-allowed', needs: ['developer'] } }, via: 'session', firstPassword: false, expiresAt: '2026-11-24T23:00:00Z', emergencyKey: false };
  const cleared = 'rail8-session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0';

  it('logs in: the node is sent the name and password and no credential, and the page gets who it is and the id, never the secret', async () => {
    const { sent, call } = await gate(SESSION_MODE, { '/admin/sessions': { status: 201, json: { session: SESSION, expiresAt: me.expiresAt, idleSeconds: 7200, me: { ...me, extra: 'x' } } } });
    const answer = await call('POST', '/session', { ...json, cookie: 'rail8-session=old', authorization: 'Bearer browser' }, JSON.stringify({ name: 'anna', password: 'twelve chars!' }));
    expect(answer.status).toBe(200);
    expect(answer.json).toEqual({ ...me, session: ID });
    expect(answer.headers['set-cookie']).toEqual([`rail8-session=${SESSION}; HttpOnly; SameSite=Strict; Path=/`]);
    expect(answer.headers['cache-control']).toBe('no-store');
    expect(answer.text).not.toContain(SESSION);
    expect(sent).toEqual([{ method: 'POST', path: '/admin/sessions', headers: { accept: 'application/json', 'content-type': 'application/json' }, body: '{"name":"anna","password":"twelve chars!","device":""}' }]);
  });

  it('sets __Host-rail8-session with Secure over HTTPS', async () => {
    const { call } = await gate({ kind: 'session', cookie: '__Host-rail8-session', secure: true }, { '/admin/sessions': { status: 201, json: { session: SESSION, expiresAt: me.expiresAt, me } } });
    const answer = await call('POST', '/session', json, '{"name":"anna","password":"p"}');
    expect(answer.headers['set-cookie']).toEqual([`__Host-rail8-session=${SESSION}; HttpOnly; SameSite=Strict; Path=/; Secure`]);
  });

  it("sends the browser's one device cookie of the device's form, and '' for none, for two or for another form", async () => {
    const created = { '/admin/sessions': { status: 201, json: { session: SESSION, expiresAt: me.expiresAt, me } } };
    for (const [cookie, device] of [
      [`rail8-session=old; rail8-device=${DEVICE}`, DEVICE],
      ['rail8-session=old', ''],
      [`rail8-device=${DEVICE}; rail8-device=${DEVICE}`, ''],
      [`rail8-device=${SESSION}`, ''],
      [`__Host-rail8-device=${DEVICE}`, ''],
    ] as const) {
      const { sent, call } = await gate(SESSION_MODE, created);
      await call('POST', '/session', { ...json, cookie }, '{"name":"anna","password":"p"}');
      expect(JSON.parse(sent[0]!.body)).toEqual({ name: 'anna', password: 'p', device });
    }
    expect(heldDevice({ kind: 'session', cookie: '__Host-rail8-session', secure: true }, { cookie: `__Host-rail8-device=${DEVICE}` })).toBe(DEVICE);
  });

  it('keeps a device the node issued for 90 days, each cookie in its own header, the session first; no page sees it', async () => {
    const issued = { session: SESSION, expiresAt: me.expiresAt, idleSeconds: 7200, device: NEW_DEVICE, me };
    const { call } = await gate(SESSION_MODE, { '/admin/sessions': { status: 201, json: issued } });
    const answer = await call('POST', '/session', { ...json, cookie: `rail8-device=${DEVICE}` }, '{"name":"anna","password":"p"}');
    expect(answer.headers['set-cookie']).toEqual([
      `rail8-session=${SESSION}; HttpOnly; SameSite=Strict; Path=/`,
      `rail8-device=${NEW_DEVICE}; HttpOnly; SameSite=Strict; Path=/; Max-Age=7776000`,
    ]);
    expect(answer.json).toEqual({ ...me, session: ID });
    expect(answer.text).not.toContain(NEW_DEVICE);
    const secure = await gate({ kind: 'session', cookie: '__Host-rail8-session', secure: true }, { '/admin/sessions': { status: 201, json: issued } });
    expect((await secure.call('POST', '/session', json, '{"name":"anna","password":"p"}')).headers['set-cookie']?.[1]).toBe(
      `__Host-rail8-device=${NEW_DEVICE}; HttpOnly; SameSite=Strict; Path=/; Max-Age=7776000; Secure`,
    );
  });

  it('sets only the session cookie for a 201 without a device or with one of another form', async () => {
    for (const device of [undefined, SESSION, 'rail8d_short', 5]) {
      const { call } = await gate(SESSION_MODE, { '/admin/sessions': { status: 201, json: { session: SESSION, expiresAt: me.expiresAt, device, me } } });
      const answer = await call('POST', '/session', { ...json, cookie: `rail8-device=${DEVICE}` }, '{"name":"anna","password":"p"}');
      expect(answer.headers['set-cookie']).toEqual([`rail8-session=${SESSION}; HttpOnly; SameSite=Strict; Path=/`]);
    }
  });

  it('refuses a login that passes 1 KiB in UTF-8 only once the device is added, sending nothing', async () => {
    const { sent, call } = await gate(SESSION_MODE);
    const fits = `{"name":"a","password":"${'é'.repeat(480)}"}`;
    expect(Buffer.byteLength(fits)).toBeLessThanOrEqual(1024);
    const answer = await call('POST', '/session', { ...json, cookie: `rail8-device=${DEVICE}` }, fits);
    expect(answer.status).toBe(413);
    expect(answer.json).toMatchObject({ code: 'too-large' });
    expect(sent).toHaveLength(0);
  });

  it('refuses a login it cannot send, sending nothing: 404 in token mode, 415, 413 over 1 KiB, 400', async () => {
    const { sent, call } = await gate(SESSION_MODE);
    expect((await call('POST', '/session', {}, '{"name":"a","password":"b"}')).status).toBe(415);
    expect((await call('POST', '/session', json, `{"name":"a","password":"${'p'.repeat(1024)}"}`)).status).toBe(413);
    expect((await call('POST', '/session', json, '{"name":"a"}')).json).toMatchObject({ code: 'bad-login' });
    expect((await call('POST', '/session', json, 'not json')).status).toBe(400);
    expect(sent).toHaveLength(0);
    const token = await gate(tokenMode('t'));
    expect((await token.call('POST', '/session', json, '{"name":"a","password":"b"}')).json).toMatchObject({ code: 'token-mode' });
    expect(token.sent).toHaveLength(0);
  });

  it("passes the node's 401, 429 and 503 on with Retry-After and leaves the cookie alone; anything else is 502", async () => {
    for (const [status, code] of [[401, 'login-refused'], [429, 'too-many-attempts'], [503, 'busy']] as const) {
      const { call } = await gate(SESSION_MODE, { '/admin/sessions': { status, json: { error: 'no', code }, headers: { 'retry-after': '3' } } });
      const answer = await call('POST', '/session', { ...json, cookie: `rail8-session=${SESSION}; rail8-device=${DEVICE}` }, '{"name":"a","password":"b"}');
      expect(answer.status).toBe(status);
      expect(answer.json).toMatchObject({ code });
      expect(answer.headers['retry-after']).toBe('3');
      expect(answer.headers['set-cookie']).toBeUndefined();
    }
    for (const answer of [{ status: 500, json: {} }, { status: 201, json: { session: 'rail8s_short', expiresAt: 'x', me } }, { status: 201, json: { session: SESSION, me } }, { status: 302 }]) {
      const { call } = await gate(SESSION_MODE, { '/admin/sessions': answer });
      const refused = await call('POST', '/session', json, '{"name":"a","password":"b"}');
      expect(refused.status).toBe(502);
      expect(refused.json).toMatchObject({ code: 'node-unavailable' });
      expect(refused.headers['set-cookie']).toBeUndefined();
      expect(refused.text).not.toContain(SESSION);
    }
  });

  it('ends a call of its own after 10 s on the deadline: 502', async () => {
    const asked: number[] = [];
    const stop = new AbortController();
    const send = ((_: string, init: RequestInit) =>
      new Promise<Response>((_, reject) => init.signal!.addEventListener('abort', () => reject(new Error('aborted'))))) as typeof fetch;
    const middleware = uiSession({ mode: SESSION_MODE, origins: HTTP, target: 'http://127.0.0.1:8081', send, deadline: (ms) => (asked.push(ms), stop.signal) });
    const server = createServer((req, res) => middleware(req, res, () => res.writeHead(299).end()));
    servers.push(server);
    await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
    const answered = fetch(`http://127.0.0.1:${(server.address() as AddressInfo).port}/session`, { headers: { 'sec-fetch-site': 'same-origin', ...holding } });
    while (asked.length === 0) await new Promise((done) => setImmediate(done));
    expect(asked).toEqual([10_000]);
    stop.abort();
    expect((await answered).status).toBe(502);
  });

  it('logs out: the node is sent the session, and the cookie is cleared whatever it answers', async () => {
    for (const [status, expected] of [[204, 204], [401, 204], [500, 502]] as const) {
      const { sent, call } = await gate(SESSION_MODE, { '/admin/me/logout': { status } });
      const answer = await call('POST', '/session/end', naming);
      expect(answer.status).toBe(expected);
      expect(answer.headers['set-cookie']).toEqual([cleared]);
      expect(sent[0]).toMatchObject({ method: 'POST', path: '/admin/me/logout', headers: { authorization: `Bearer ${SESSION}` } });
    }
    const { call: down } = await gate(SESSION_MODE);
    expect((await down('POST', '/session/end', naming)).headers['set-cookie']).toEqual([cleared]);
  });

  it('refuses a logout of another session or none, sending nothing and touching no cookie', async () => {
    const { sent, call } = await gate(SESSION_MODE, { '/admin/me/logout': { status: 204 } });
    const stale = await call('POST', '/session/end', { ...holding, 'rail8-session': 'fedcba9876543210' });
    expect(stale.status).toBe(409);
    expect(stale.headers['set-cookie']).toBeUndefined();
    expect((await call('POST', '/session/end', { 'rail8-session': ID })).status).toBe(409);
    expect(sent).toHaveLength(0);
    expect((await (await gate(tokenMode('t'))).call('POST', '/session/end')).json).toMatchObject({ code: 'token-mode' });
  });

  it('answers GET /session with who the caller is and the id; after a logout it is 401', async () => {
    const { call } = await gate(SESSION_MODE, { '/admin/me': { status: 200, json: { ...me, extra: 1 } } });
    const answer = await call('GET', '/session', holding);
    expect(answer.json).toEqual({ ...me, session: ID });
    expect(answer.headers['cache-control']).toBe('no-store');
    const out = await call('GET', '/session');
    expect(out.status).toBe(401);
    expect(out.json).toMatchObject({ code: 'no-credential' });
  });

  it("answers the node's 401 to GET /session as 401, clearing the cookie only for a session that is gone", async () => {
    for (const [code, clears] of [['session-ended', true], ['unknown-credential', true], ['blocked', true], ['no-credential', false]] as const) {
      const { call } = await gate(SESSION_MODE, { '/admin/me': { status: 401, json: { error: 'x', code } } });
      const answer = await call('GET', '/session', holding);
      expect(answer.status).toBe(401);
      expect(answer.json).toMatchObject({ code });
      expect(answer.headers['set-cookie']).toEqual(clears ? [cleared] : undefined);
    }
  });

  it("is GET /session of the token in token mode, with no id; the node's 401 is 502 naming the variable", async () => {
    const { sent, call } = await gate(tokenMode('the-token'), { '/admin/me': { status: 200, json: me } });
    expect((await call('GET', '/session', holding)).json).toEqual({ ...me, session: null });
    expect(sent[0]?.headers.authorization).toBe('Bearer the-token');
    const refused = await gate(tokenMode('the-token'), { '/admin/me': { status: 401, json: { error: 'x', code: 'unknown-credential' } } });
    const answer = await refused.call('GET', '/session');
    expect(answer.status).toBe(502);
    expect(answer.json.error).toContain('RAIL8_ADMIN_TOKEN');
    expect(answer.text).not.toContain('the-token');
  });

  it('refuses another site and another method on its own routes', async () => {
    const { sent, call } = await gate(SESSION_MODE, { '/admin/me': { status: 200, json: me } });
    expect((await call('GET', '/session', { 'sec-fetch-site': 'cross-site' })).json).toMatchObject({ code: 'foreign-origin' });
    expect((await call('GET', '/session', { origin: 'http://evil.example' })).status).toBe(403);
    expect((await call('PUT', '/session')).status).toBe(405);
    expect((await call('GET', '/session/end')).status).toBe(405);
    expect(sent).toHaveLength(0);
  });

  it.each(['/admin/messages', '/git/packages', '/api/mappings', '/admin', '/git?x', '/x/../git/packages'])(
    'refuses %s without its credential, from another site, or naming another session, and calls nothing',
    async (path) => {
      const { sent, call } = await gate(SESSION_MODE);
      expect((await call('GET', path)).status).toBe(401);
      expect((await call('GET', path, { ...holding, 'sec-fetch-site': 'cross-site' })).status).toBe(403);
      expect((await call('POST', path, holding)).status).toBe(409);
      expect((await call('GET', path, { ...holding, 'rail8-session': 'fedcba9876543210' })).status).toBe(409);
      expect(sent).toHaveLength(0);
    },
  );

  it('refuses under /api as problem+json, elsewhere as {error, code, values}, setting no cookie', async () => {
    const { call } = await gate(SESSION_MODE);
    const api = await call('GET', '/api/mappings');
    expect(api.headers['content-type']).toBe('application/problem+json');
    expect(api.json).toEqual({ type: 'urn:rail8:ui:problem:no-credential', status: 401, title: expect.any(String), detail: expect.any(String), code: 'no-credential', values: {} });
    const git = await call('POST', '/git/packages/x/commit', holding);
    expect(git.headers['content-type']).toBe('application/json');
    expect(git.json).toEqual({ error: expect.any(String), code: 'session-changed', values: {} });
    expect(git.headers['set-cookie']).toBeUndefined();
  });

  it('lets an admitted request on with no-store in either mode, and every other path untouched', async () => {
    const { call } = await gate(SESSION_MODE);
    const admitted = await call('POST', '/git/packages/x/commit', naming);
    expect(admitted.status).toBe(299);
    expect(admitted.headers['cache-control']).toBe('no-store');
    for (const outside of [
      await call('GET', '/src/main.tsx', { 'sec-fetch-site': 'cross-site' }),
      await call('PUT', '/files/endpoints/x', { 'sec-fetch-site': 'cross-site' }),
      await call('GET', '/administration'),
    ]) {
      expect(outside.status).toBe(299);
      expect(outside.headers['cache-control']).toBeUndefined();
    }
    const token = await gate(tokenMode('t'));
    for (const path of ['/admin/messages', '/git/packages', '/api/mappings']) {
      const open = await token.call('GET', path);
      expect(open.status).toBe(299);
      expect(open.headers['cache-control']).toBe('no-store');
    }
    expect((await token.call('GET', '/src/main.tsx')).headers['cache-control']).toBeUndefined();
  });
});

describe('nodeFor', () => {
  const target = 'http://127.0.0.1:8081';

  /** A recording `send` answering `status` with `json`, and what it was sent. */
  function recording(status = 200, json: unknown = {}) {
    const sent: { url: string; init: RequestInit }[] = [];
    const send = (async (input: string | URL, init: RequestInit = {}) => {
      sent.push({ url: String(input), init });
      return new Response(JSON.stringify(json), { status, headers: { 'content-type': 'application/json' } });
    }) as typeof fetch;
    return { sent, send };
  }

  const halfHeaders = { accept: 'application/json', 'content-type': 'application/json', authorization: 'Bearer half', cookie: 'a=b', 'rail8-session': ID, 'x-rail8-actor': 'Ann <ann@example.com>', origin: 'x' };

  it('sends only Accept, Content-Type and the credential in session mode, without the actor', async () => {
    const { sent, send } = recording();
    await nodeFor(gatedRequest(SESSION_MODE, 'POST'), target, send).send(`${target}/admin/deploy`, { method: 'POST', headers: halfHeaders });
    expect(sent[0]!.init.headers).toEqual({ accept: 'application/json', 'content-type': 'application/json', authorization: `Bearer ${SESSION}` });
    expect(sent[0]!.init.redirect).toBe('manual');
  });

  it('keeps the actor in token mode, with the token', async () => {
    const { sent, send } = recording();
    await nodeFor(gatedRequest(tokenMode('t'), 'POST'), target, send).send(`${target}/admin/deploy`, { method: 'POST', headers: halfHeaders });
    expect(sent[0]!.init.headers).toEqual({ accept: 'application/json', 'content-type': 'application/json', 'x-rail8-actor': 'Ann <ann@example.com>', authorization: 'Bearer t' });
  });

  it('rejects a redirect, and a URL of another node', async () => {
    const { send } = recording(307);
    await expect(nodeFor(gatedRequest(tokenMode('t')), target, send).send(`${target}/admin/me`)).rejects.toThrow(/a redirect/);
    await expect(nodeFor(gatedRequest(tokenMode('t')), target, recording().send).send('http://elsewhere.example/admin/me')).rejects.toThrow(/calls only the node/);
  });

  it.each([
    ['unknown-credential', true],
    ['session-ended', true],
    ['blocked', true],
    ['no-credential', false],
    ['token-expired', false],
  ])('rejects the node\'s 401 %s in session mode as the session\'s refusal, clearing the cookie: %s', async (code, clear) => {
    const { send } = recording(401, { error: 'x', code });
    const refused = await nodeFor(gatedRequest(SESSION_MODE), target, send).send(`${target}/admin/me`).catch((e: unknown) => e);
    expect(isSessionRefused(refused)).toBe(true);
    expect(refused).toMatchObject({ code, clear });
  });

  it('reads the code of a problem too, and passes a 401 on in token mode', async () => {
    const problem = { type: 'urn:rail8:admin:problem:unauthenticated', title: 'x', status: 401, detail: 'x', code: 'session-ended' };
    const refused = await nodeFor(gatedRequest(SESSION_MODE), target, recording(401, problem).send).send(`${target}/admin/modules/mapping/catalogue`).catch((e: unknown) => e);
    expect(refused).toMatchObject({ code: 'session-ended', clear: true });
    expect((await nodeFor(gatedRequest(tokenMode('t')), target, recording(401, problem).send).send(`${target}/admin/me`)).status).toBe(401);
  });

  it('throws for a request the gate did not admit, so it never reaches the node', () => {
    const { sent, send } = recording();
    expect(() => nodeFor({ headers: {} } as never, target, send)).toThrow(/did not admit/);
    expect(sent).toHaveLength(0);
  });

  it('hides the secret in a text', () => {
    expect(nodeFor(gatedRequest(SESSION_MODE), target).redact(`it said ${SESSION}.`)).toBe('it said ***.');
    expect(nodeFor(gatedRequest(tokenMode('the-token')), target).redact('the-token leaked')).toBe('*** leaked');
  });
});

describe('the session refusal', () => {
  it('is recognised by its brand across the copies of this file', async () => {
    const other = (await import(/* @vite-ignore */ `./session.ts?copy=${Date.now()}`)) as typeof import('./session');
    expect(other.SessionRefused).not.toBe(SessionRefused);
    const theirs = new other.SessionRefused('session-ended', 'x');
    expect(theirs instanceof SessionRefused).toBe(false);
    expect(isSessionRefused(theirs)).toBe(true);
    expect(other.isSessionRefused(new SessionRefused('blocked', undefined))).toBe(true);
    expect(isSessionRefused(new Error('session-ended'))).toBe(false);
  });
});
