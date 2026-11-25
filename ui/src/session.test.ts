import { describe, expect, it } from 'vitest';
import { msg, say, textsIn } from './language';
import { HELD_MAX, HELD_MAX_BYTES, PasswordRefused, reasonOf, sessionClient, WAIT_MS, type Me } from './session';

const ME: Me = {
  name: 'anna',
  kind: 'person',
  roles: ['operator'],
  actions: ['message.read'],
  refused: { 'package.deploy': { code: 'not-allowed', needs: ['developer', 'administrator'] } },
  via: 'session',
  firstPassword: false,
  expiresAt: '2026-11-24T23:00:00Z',
  emergencyKey: false,
  session: '0123456789abcdef',
};
const NEXT = 'fedcba9876543210';

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

/** One call the page made: what it asked, and what answers it. */
interface Call {
  url: string;
  init: RequestInit;
  session: string | null;
  answer: (response: Response) => void;
  fail: (error: unknown) => void;
}

/** A client over a `send` that answers nothing until the test says so, a counted `reload` and timers the test fires. */
function world() {
  const calls: Call[] = [];
  const timers: { ms: number; fire: () => void }[] = [];
  let reloads = 0;
  const send = ((input: string | URL, init: RequestInit = {}) =>
    new Promise<Response>((answer, fail) => {
      calls.push({ url: String(input), init, session: new Headers(init.headers).get('rail8-session'), answer, fail });
    })) as typeof fetch;
  const client = sessionClient({
    send,
    reload: () => void reloads++,
    timer: (ms) => new Promise((fire) => timers.push({ ms, fire: () => fire() })),
  });
  /** Lets every promise that can settle settle. */
  const settle = async () => {
    for (let i = 0; i < 20; i++) await Promise.resolve();
  };
  /** The calls not yet answered to `url`, oldest first. */
  const open = (url: string) => calls.filter((call) => call.url === url && !(call as { done?: true }).done);
  /** Answers the oldest open call to `url`. */
  const answer = async (url: string, response: Response) => {
    await settle();
    const call = open(url)[0];
    if (call === undefined) throw new Error(`no open call to ${url}; calls: ${calls.map((c) => c.url).join(', ')}`);
    (call as { done?: true }).done = true;
    call.answer(response);
    await settle();
  };
  /** A started client in session mode, logged in as `me`. */
  const started = async (me: Me = ME) => {
    const start = client.start();
    await answer('/session', json(200, me));
    await start;
  };
  return { client, calls, timers, open, answer, settle, started, reloads: () => reloads };
}

describe('the start', () => {
  it('is in with who GET /session names, and out on its 401', async () => {
    const w = world();
    expect(w.client.state()).toEqual({ kind: 'starting' });
    await w.started();
    expect(w.client.state()).toEqual({ kind: 'in', me: ME });

    const out = world();
    const start = out.client.start();
    await out.answer('/session', json(401, { error: 'x', code: 'no-credential' }));
    await start;
    expect(out.client.state()).toEqual({ kind: 'out' });

    const gone = world();
    const ended = gone.client.start();
    await gone.answer('/session', json(401, { error: 'x', code: 'session-ended' }));
    await ended;
    expect(gone.client.state()).toEqual({ kind: 'out', refusal: { code: 'session-ended' } });
  });

  it('fails on a 5xx with its reason and Retry-After, on no answer, and after 10 s without one, aborting the call', async () => {
    const w = world();
    let start = w.client.start();
    await w.answer('/session', json(503, { error: 'the node is busy', code: 'busy' }, { 'retry-after': '1' }));
    await start;
    expect(w.client.state()).toEqual({ kind: 'failed', error: 'the node is busy', retryAfter: '1' });

    start = w.client.start();
    w.open('/session')[0]!.fail(new TypeError('offline'));
    await start;
    expect(w.client.state()).toMatchObject({ kind: 'failed' });

    start = w.client.start();
    await w.settle();
    const late = w.timers.at(-1)!;
    expect(late.ms).toBe(WAIT_MS);
    late.fire();
    await start;
    const failed = w.client.state();
    expect(failed).toEqual({ kind: 'failed', error: msg('frame.session.late', { seconds: 10 }) });
    expect(failed.kind === 'failed' && say(textsIn('en'), failed.error)).toBe('the UI server did not answer within 10 s');
    expect(w.calls.at(-1)!.init.signal?.aborted).toBe(true);
  });
});

describe('uiFetch', () => {
  it('is the plain send in token mode and before the start: no session header, a 401 given back', async () => {
    const w = world();
    const before = w.client.fetch('/admin/messages');
    await w.answer('/admin/messages', json(401, {}));
    expect((await before).status).toBe(401);
    expect(w.calls[0]!.session).toBeNull();

    await w.started({ ...ME, via: 'token', session: null });
    const call = w.client.fetch('/admin/messages', { headers: { 'Rail8-Session': 'x' } });
    await w.answer('/admin/messages', json(401, {}));
    expect((await call).status).toBe(401);
    expect(w.client.state()).toMatchObject({ kind: 'in' });
  });

  it('names the session on every call, reads included, keeping the call\'s own headers', async () => {
    const w = world();
    await w.started();
    const read = w.client.fetch('/admin/messages', { headers: { accept: 'application/json', 'Rail8-Session': 'stale' } });
    await w.answer('/admin/messages', json(200, []));
    expect((await read).status).toBe(200);
    expect(w.calls.at(-1)!.session).toBe(ME.session);
    expect(new Headers(w.calls.at(-1)!.init.headers).get('accept')).toBe('application/json');
  });

  it('holds a call answered 401, and every new one after it, sending nothing new, until the same user logs in again', async () => {
    const w = world();
    await w.started();
    const save = w.client.fetch('/files/x', { method: 'PUT', headers: { 'If-Match': '"t1"' }, body: 'edited' });
    await w.answer('/files/x', json(401, { code: 'session-ended' }));
    expect(w.client.state()).toEqual({ kind: 'asking', me: ME });

    const read = w.client.fetch('/admin/messages');
    await w.settle();
    expect(w.calls.filter((c) => c.url === '/admin/messages')).toHaveLength(0);

    const login = w.client.login('anna', 'twelve chars!');
    await w.settle();
    expect(JSON.parse(String(w.open('/session')[0]!.init.body))).toEqual({ name: 'anna', password: 'twelve chars!' });
    await w.answer('/session', json(200, { ...ME, session: NEXT }));
    await login;
    expect(w.client.state()).toEqual({ kind: 'in', me: { ...ME, session: NEXT } });

    const resent = w.open('/files/x')[0]!;
    expect(resent.session).toBe(NEXT);
    expect(resent.init.method).toBe('PUT');
    expect(new Headers(resent.init.headers).get('if-match')).toBe('"t1"');
    expect(resent.init.body).toBe('edited');
    await w.answer('/files/x', json(200, { saved: true }));
    await w.answer('/admin/messages', json(200, []));
    expect((await save).status).toBe(200);
    expect((await read).status).toBe(200);
  });

  it('sends a held call once more only: a second 401 is given back', async () => {
    const w = world();
    await w.started();
    const call = w.client.fetch('/admin/messages');
    await w.answer('/admin/messages', json(401, {}));
    const login = w.client.login('anna', 'p');
    await w.answer('/session', json(200, ME));
    await login;
    await w.answer('/admin/messages', json(401, { code: 'session-ended' }));
    expect((await call).status).toBe(401);
    expect(w.client.state()).toMatchObject({ kind: 'asking' });
  });

  it('reloads when another user logs in over the tabs, settling no held call', async () => {
    const w = world();
    await w.started();
    let settled = false;
    void w.client.fetch('/files/x', { method: 'PUT', body: 'edited' }).finally(() => (settled = true));
    await w.answer('/files/x', json(401, {}));
    const login = w.client.login('bob', 'p');
    await w.answer('/session', json(200, { ...ME, name: 'bob', session: NEXT }));
    await login;
    expect(w.reloads()).toBe(1);
    expect(w.open('/files/x')).toHaveLength(0);
    expect(settled).toBe(false);
  });

  it('puts a refused login over the tabs into the state with its Retry-After, and holds on', async () => {
    const w = world();
    await w.started();
    void w.client.fetch('/admin/messages');
    await w.answer('/admin/messages', json(401, {}));
    const login = w.client.login('anna', 'wrong');
    await w.answer('/session', json(429, { error: 'x', code: 'too-many-attempts' }, { 'retry-after': '30' }));
    await login;
    expect(w.client.state()).toEqual({ kind: 'asking', me: ME, refusal: { code: 'too-many-attempts', retryAfter: '30' } });
  });

  it('asks for a first password\'s change over the tabs, and sends the held calls only after it and a GET /session naming the same id', async () => {
    const w = world();
    await w.started();
    const call = w.client.fetch('/admin/messages');
    await w.answer('/admin/messages', json(401, {}));
    const login = w.client.login('anna', 'first');
    await w.answer('/session', json(200, { ...ME, firstPassword: true, session: NEXT }));
    await login;
    expect(w.client.state()).toEqual({ kind: 'asking', me: { ...ME, firstPassword: true, session: NEXT } });
    expect(w.open('/admin/messages')).toHaveLength(0);

    const wrong = w.client.changePassword('nope', 'a new long password');
    await w.answer('/admin/me/password', json(403, { error: 'the current password is wrong', code: 'wrong-password' }));
    await expect(wrong).rejects.toEqual(new PasswordRefused('wrong-password', 'the current password is wrong'));
    expect(w.client.state()).toMatchObject({ kind: 'asking' });

    const change = w.client.changePassword('first', 'a new long password');
    await w.settle();
    expect(w.open('/admin/me/password')[0]!.session).toBe(NEXT);
    await w.answer('/admin/me/password', new Response(null, { status: 204 }));
    expect(w.open('/admin/messages')).toHaveLength(0);
    await w.answer('/session', json(200, { ...ME, session: NEXT }));
    await change;
    expect(w.client.state()).toEqual({ kind: 'in', me: { ...ME, session: NEXT } });
    await w.answer('/admin/messages', json(200, []));
    expect((await call).status).toBe(200);
  });

  it('reloads after a password change when GET /session names another session or still the first password', async () => {
    for (const after of [{ ...ME, session: NEXT }, { ...ME, firstPassword: true }]) {
      const w = world();
      await w.started({ ...ME, firstPassword: true });
      const change = w.client.changePassword('first', 'a new long password');
      await w.answer('/admin/me/password', new Response(null, { status: 204 }));
      await w.answer('/session', json(200, after));
      await change;
      expect(w.reloads()).toBe(1);
    }
  });

  it('waits for the calls of the ended session before it sends the login: answered first', async () => {
    const w = world();
    await w.started();
    void w.client.fetch('/admin/a');
    void w.client.fetch('/admin/b');
    await w.answer('/admin/a', json(401, {}));
    const login = w.client.login('anna', 'p');
    await w.settle();
    expect(w.open('/session')).toHaveLength(0);
    await w.answer('/admin/b', json(401, {}));
    expect(w.open('/session')).toHaveLength(1);
    await w.answer('/session', json(200, ME));
    await login;
  });

  it('waits for the calls of the ended session before it sends the login: 10 s first', async () => {
    const w = world();
    await w.started();
    void w.client.fetch('/admin/a');
    void w.client.fetch('/admin/b');
    await w.answer('/admin/a', json(401, {}));
    const login = w.client.login('anna', 'p');
    await w.settle();
    expect(w.open('/session')).toHaveLength(0);
    const wait = w.timers.at(-1)!;
    expect(wait.ms).toBe(WAIT_MS);
    wait.fire();
    await w.settle();
    expect(w.open('/session')).toHaveLength(1);
    await w.answer('/session', json(200, ME));
    await login;
    // the late answer of the ended session is held too, and the login asked again
    await w.answer('/admin/b', json(401, {}));
    expect(w.client.state()).toMatchObject({ kind: 'asking' });
  });

  it('holds at most 100 calls: the next gets its 401 back', async () => {
    const w = world();
    await w.started();
    const first = w.client.fetch('/admin/0');
    await w.answer('/admin/0', json(401, {}));
    const more = Array.from({ length: HELD_MAX }, (_, i) => w.client.fetch(`/admin/${i + 1}`));
    const last = await more.at(-1)!;
    expect(last.status).toBe(401);
    expect(await last.json()).toMatchObject({ code: 'no-credential' });
    const login = w.client.login('anna', 'p');
    await w.answer('/session', json(200, ME));
    await login;
    await w.settle();
    expect(w.open('/admin/0')).toHaveLength(1);
    expect(w.calls.filter((c) => c.url.startsWith('/admin/')).length).toBe(1 + HELD_MAX);
    void first;
  });

  it('holds at most 32 MiB of bodies: beyond that, and for a body it cannot send twice, the call gets its own 401', async () => {
    const w = world();
    await w.started();
    const big = w.client.fetch('/files/a', { method: 'PUT', body: new Uint8Array(HELD_MAX_BYTES) });
    await w.answer('/files/a', json(401, { code: 'session-ended' }));
    const over = w.client.fetch('/files/b', { method: 'PUT', body: 'x' });
    expect((await over).status).toBe(401);
    void big;

    const s = world();
    await s.started();
    const stream = s.client.fetch('/files/c', { method: 'PUT', body: new ReadableStream(), duplex: 'half' } as RequestInit);
    await s.answer('/files/c', json(401, { code: 'session-ended', marker: 1 }));
    expect(await (await stream).json()).toMatchObject({ marker: 1 });
  });

  it('drops a held call whose signal aborts, rejecting it with the reason, and does not send it again', async () => {
    const w = world();
    await w.started();
    const stop = new AbortController();
    const call = w.client.fetch('/admin/messages', { signal: stop.signal });
    await w.answer('/admin/messages', json(401, {}));
    stop.abort(new Error('closed'));
    await expect(call).rejects.toThrow('closed');
    const login = w.client.login('anna', 'p');
    await w.answer('/session', json(200, ME));
    await login;
    expect(w.open('/admin/messages')).toHaveLength(0);
  });

  it('adopts the new id after a 409 when GET /session names the same user, and sends the call again once', async () => {
    const w = world();
    await w.started();
    const call = w.client.fetch('/git/packages/x/commit', { method: 'POST', body: '{}' });
    await w.answer('/git/packages/x/commit', json(409, { error: 'x', code: 'session-changed' }));
    await w.answer('/session', json(200, { ...ME, session: NEXT }));
    expect(w.open('/git/packages/x/commit')[0]!.session).toBe(NEXT);
    await w.answer('/git/packages/x/commit', json(200, {}));
    expect((await call).status).toBe(200);
    expect(w.reloads()).toBe(0);
  });

  it('reads the code of a problem too, and reloads on a second 409', async () => {
    const w = world();
    await w.started();
    const problem = { type: 'urn:rail8:ui:problem:session-changed', title: 'x', status: 409, detail: 'x' };
    const call = w.client.fetch('/api/mappings');
    await w.answer('/api/mappings', json(409, problem));
    await w.answer('/session', json(200, { ...ME, session: NEXT }));
    await w.answer('/api/mappings', json(409, problem));
    expect((await call).status).toBe(409);
    expect(w.reloads()).toBe(1);
  });

  it('reloads after a 409 when GET /session names another user, and holds the call when no session is left', async () => {
    const w = world();
    await w.started();
    void w.client.fetch('/admin/messages');
    await w.answer('/admin/messages', json(409, { code: 'session-changed' }));
    await w.answer('/session', json(200, { ...ME, name: 'bob' }));
    expect(w.reloads()).toBe(1);

    const h = world();
    await h.started();
    let settled = false;
    void h.client.fetch('/admin/messages').finally(() => (settled = true));
    await h.answer('/admin/messages', json(409, { code: 'session-changed' }));
    await h.answer('/session', json(401, { code: 'no-credential' }));
    expect(h.client.state()).toMatchObject({ kind: 'asking' });
    expect(settled).toBe(false);
  });

  it('gives back a 409 of another code untouched', async () => {
    const w = world();
    await w.started();
    const call = w.client.fetch('/files/x', { method: 'PUT' });
    await w.answer('/files/x', json(409, { code: 'conflict' }));
    expect((await call).status).toBe(409);
    expect(w.open('/session')).toHaveLength(0);
  });
});

describe('the session\'s own calls', () => {
  it('logs in from out, sending the name and password once', async () => {
    const w = world();
    const start = w.client.start();
    await w.answer('/session', json(401, { code: 'no-credential' }));
    await start;
    const login = w.client.login('anna', 'p');
    await w.answer('/session', json(200, ME));
    await login;
    expect(w.client.state()).toEqual({ kind: 'in', me: ME });
    expect(w.calls.filter((c) => c.init.method === 'POST')).toHaveLength(1);
  });

  it('shows a refused login at once, out', async () => {
    const w = world();
    const login = w.client.login('anna', 'p');
    await w.answer('/session', json(401, { error: 'x', code: 'login-refused' }));
    await login;
    expect(w.client.state()).toEqual({ kind: 'out', refusal: { code: 'login-refused' } });
  });

  it('reads the session again after a change of its own roles: new roles for the same user, a reload for another', async () => {
    const w = world();
    await w.started();
    const changed: Me = { ...ME, roles: ['operator', 'developer'], actions: ['message.read', 'package.deploy'], refused: {} };
    const refresh = w.client.refresh();
    await w.answer('/session', json(200, changed));
    await refresh;
    expect(w.client.state()).toEqual({ kind: 'in', me: changed });
    expect(w.client.may('package.deploy')).toBe(true);

    const other = world();
    await other.started();
    const read = other.client.refresh();
    await other.answer('/session', json(200, { ...ME, name: 'bob' }));
    await read;
    expect(other.reloads()).toBe(1);
  });

  it('keeps the state when the session read again gives no answer, and asks for the login on its 401', async () => {
    const w = world();
    await w.started();
    const refresh = w.client.refresh();
    await w.settle();
    const lost = w.open('/session')[0]!;
    (lost as { done?: true }).done = true;
    lost.fail(new TypeError('offline'));
    await refresh;
    expect(w.client.state()).toEqual({ kind: 'in', me: ME });

    const ended = w.client.refresh();
    await w.answer('/session', json(401, { code: 'session-ended' }));
    await ended;
    expect(w.client.state()).toEqual({ kind: 'asking', me: ME });
  });

  it('reads nothing again before the session is known', async () => {
    const w = world();
    await w.client.refresh();
    expect(w.calls).toHaveLength(0);
  });

  it('logs out naming the session and reloads, whatever the answer', async () => {
    const w = world();
    await w.started();
    const out = w.client.logout();
    await w.settle();
    expect(w.open('/session/end')[0]!.session).toBe(ME.session);
    w.open('/session/end')[0]!.fail(new TypeError('offline'));
    await out;
    expect(w.reloads()).toBe(1);
  });

  it('is never held while the session is asked for', async () => {
    const w = world();
    await w.started();
    void w.client.fetch('/admin/messages');
    await w.answer('/admin/messages', json(401, {}));
    void w.client.logout();
    await w.settle();
    expect(w.open('/session/end')).toHaveLength(1);
  });
});

describe('may', () => {
  it('answers from the actions and the refusals, and refuses before anyone is in', async () => {
    const w = world();
    expect(w.client.may('message.read')).toEqual({ code: 'not-allowed', needs: [] });
    await w.started();
    expect(w.client.may('message.read')).toBe(true);
    expect(w.client.may('package.deploy')).toEqual({ code: 'not-allowed', needs: ['developer', 'administrator'] });
    expect(w.client.may('unknown')).toEqual({ code: 'not-allowed', needs: [] });
  });

  it.each([
    [{ code: 'not-allowed', needs: ['developer', 'administrator'] }, 'Needs the role developer or administrator'],
    [{ code: 'not-allowed', needs: [] }, 'Not allowed for this account'],
    [{ code: 'session-required', needs: ['operator'] }, 'Needs a person logged in'],
    [{ code: 'password-change-required', needs: [] }, 'Change the first password first'],
  ])('words %j as %s', (refusal, words) => {
    expect(say(textsIn('en'), reasonOf(refusal))).toBe(words);
  });
});
