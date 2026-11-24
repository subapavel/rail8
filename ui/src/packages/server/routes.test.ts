import { execFileSync } from 'node:child_process';
import { mkdir, mkdtemp, realpath, rm, writeFile } from 'node:fs/promises';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { SESSION, SESSION_MODE, tokenMode } from '../../../server/gatedRequest.ts';
import { uiSession, type Mode } from '../../../server/session.ts';
import { etagOf, workingFolderWrites, type WorkingFolderWrites } from '../../../server/workingFolder.ts';
import { FakeTimer } from './fakeTimer.ts';
import { gitPackages, type GitPackagesOptions } from './routes.ts';
import { runner, type Runner } from './runner.ts';
import { realTimer } from './timer.ts';

const ORIGIN = 'http://localhost:5173';
const idle: Runner = { run: () => Promise.reject(new Error('no git in this test')), undoable: () => Promise.reject(new Error('no git in this test')), close: async () => {} };

let server: Server | undefined;
let temp: string | undefined;

afterEach(async () => {
  await new Promise<void>((done) => (server === undefined ? done() : server.close(() => done())));
  server = undefined;
  if (temp !== undefined) await rm(temp, { recursive: true, force: true });
  temp = undefined;
});

/**
 * The routes over `options` behind the session gate in `mode`, on a real HTTP server; a call that is not theirs is
 * answered 418 by `next`. In session mode every call holds and names a session.
 */
async function serve(options: Partial<GitPackagesOptions> = {}, mode: Mode = tokenMode('the-admin-token')) {
  const gate = uiSession({ mode, origins: [ORIGIN], target: 'http://127.0.0.1:9' });
  const middleware = gitPackages({
    writable: true,
    writes: workingFolderWrites(),
    origins: [ORIGIN],
    node: { target: 'http://127.0.0.1:9' },
    git: idle,
    ready: Promise.resolve(null),
    timer: new FakeTimer(),
    ...options,
  });
  server = createServer((req, res) => gate(req, res, () => middleware(req, res, () => res.writeHead(418).end())));
  await new Promise<void>((done) => server!.listen(0, '127.0.0.1', () => done()));
  const port = (server.address() as AddressInfo).port;
  const session: Record<string, string> = mode.kind === 'session' ? { cookie: `${mode.cookie}=${SESSION}`, 'rail8-session': '0123456789abcdef' } : {};
  return async (path: string, method = 'GET', body?: string) => {
    const res = await fetch(`http://127.0.0.1:${port}${path}`, { method, body, headers: { 'sec-fetch-site': 'same-origin', origin: ORIGIN, ...session } });
    return { status: res.status, body: res.status === 418 ? null : ((await res.json()) as unknown), cookie: res.headers.get('set-cookie') };
  };
}

/** A working folder in a new temporary folder, with the package `orders` of one commit, and git that serves it. */
async function orders() {
  temp = await realpath(await mkdtemp(join(tmpdir(), 'rail8-routes-')));
  const home = join(temp, 'home');
  const clone = join(temp, 'work', 'orders');
  await mkdir(home);
  await mkdir(clone, { recursive: true });
  const env = { PATH: process.env.PATH ?? '', HOME: home, GIT_CONFIG_NOSYSTEM: '1' };
  const run = (...args: string[]) => execFileSync('git', ['-c', 'user.name=Seed Author', '-c', 'user.email=seed@example.com', ...args], { cwd: clone, env }).toString('utf8').trim();
  run('init', '-q', '-b', 'main');
  await writeFile(join(clone, 'package.yaml'), 'name: orders\nversion: 1.0.0\n');
  run('add', '-A');
  run('commit', '-q', '-m', 'first');
  run('config', 'user.name', 'Seed Author');
  run('config', 'user.email', 'seed@example.com');
  return { workspace: join(temp, 'work'), clone, env, commit: run('rev-parse', 'HEAD') };
}

describe('the routes of the git half', () => {
  test('pass every path outside /git to the next middleware', async () => {
    const call = await serve();
    expect((await call('/gitx/packages')).status).toBe(418);
    expect((await call('/api/files')).status).toBe(418);
  });

  test('answer 404 for a path under /git they do not serve, and 405 for another method', async () => {
    const call = await serve();
    expect(await call('/git/nothing')).toMatchObject({ status: 404, body: { error: 'the git half has no route /git/nothing' } });
    expect(await call('/git/packages', 'POST')).toMatchObject({ status: 405, body: { error: 'GET is the only way to call /git/packages' } });
  });

  test('take a commit by POST alone, and every other route of a package by GET alone', async () => {
    const call = await serve();
    expect(await call('/git/packages/orders/commit')).toMatchObject({ status: 405, body: { error: 'POST is the only way to call /git/packages/orders/commit' } });
    expect(await call('/git/packages/orders', 'POST')).toMatchObject({ status: 405, body: { error: 'GET is the only way to call /git/packages/orders' } });
    expect(await call('/git/packages/orders/log', 'POST')).toMatchObject({ status: 405, body: { error: 'GET is the only way to call /git/packages/orders/log' } });
  });

  test('take a fetch, a pull and a push by POST alone; a pull waits among the writes of the working folder, a fetch and a push do not', async () => {
    const busy: WorkingFolderWrites = () => undefined;
    const call = await serve({ writes: busy });
    for (const route of ['fetch', 'pull', 'push']) {
      expect(await call(`/git/packages/orders/${route}`)).toMatchObject({ status: 405, body: { error: `POST is the only way to call /git/packages/orders/${route}` } });
    }
    expect(await call('/git/packages/orders/pull', 'POST', '{}')).toMatchObject({ status: 503, body: { error: 'the working folder is busy: 8 writes wait already; nothing was written, try again' } });
    expect(await call('/git/packages/orders/push', 'POST', '{}')).toMatchObject({ status: 400, body: { error: 'the request names no branch' } });
  });

  test('refuse a commit with 403 while the working folder is read-only, before its body is read', async () => {
    const call = await serve({ writable: false });
    expect(await call('/git/packages/orders/commit', 'POST', 'not json')).toMatchObject({
      status: 403,
      body: { error: 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was committed' },
    });
  });

  test('refuse a commit with 503 while 9 writes of the working folder are under way, and one whose body is over 1 MiB with 413', async () => {
    const busy: WorkingFolderWrites = () => undefined;
    expect(await (await serve({ writes: busy }))('/git/packages/orders/commit', 'POST', '{}')).toMatchObject({
      status: 503,
      body: { error: 'the working folder is busy: 8 writes wait already; nothing was written, try again' },
    });
    await new Promise<void>((done) => server!.close(() => done()));
    server = undefined;

    const call = await serve();
    expect(await call('/git/packages/orders/commit', 'POST', 'x'.repeat(1024 * 1024 + 1))).toMatchObject({ status: 413, body: { error: 'the request is over 1 MiB' } });
    expect(await call('/git/packages/orders/commit', 'POST', 'not json')).toMatchObject({ status: 400, body: { error: 'the request is not JSON' } });
  });

  test('answer 404 when the working folder that is set does not exist', async () => {
    temp = await mkdtemp(join(tmpdir(), 'rail8-routes-'));
    const call = await serve({ workspace: join(temp, 'gone') });
    expect(await call('/git/packages')).toMatchObject({ status: 404, body: { error: `the working folder ${join(temp, 'gone')} does not exist` } });
  });

  test('answer 503 with why on every path while git does not serve', async () => {
    const call = await serve({ ready: Promise.resolve('git is too old') });
    expect(await call('/git/packages')).toMatchObject({ status: 503, body: { error: 'git is too old' } });
    expect(await call('/git/nothing')).toMatchObject({ status: 503, body: { error: 'git is too old' } });
  });

  test('answer a deploy the node made as made, with package null and why, when the package cannot be read after it', async () => {
    temp = await realpath(await mkdtemp(join(tmpdir(), 'rail8-routes-')));
    const home = join(temp, 'home');
    const clone = join(temp, 'work', 'orders');
    await mkdir(home);
    await mkdir(clone, { recursive: true });
    const env = { PATH: process.env.PATH ?? '', HOME: home, GIT_CONFIG_NOSYSTEM: '1' };
    const run = (...args: string[]) => execFileSync('git', ['-c', 'user.name=Seed Author', '-c', 'user.email=seed@example.com', ...args], { cwd: clone, env }).toString('utf8').trim();
    run('init', '-q', '-b', 'main');
    await writeFile(join(clone, 'package.yaml'), 'name: orders\nversion: 1.0.0\n');
    run('add', '-A');
    run('commit', '-q', '-m', 'first');
    const commit = run('rev-parse', 'HEAD');
    // git that serves until the node has deployed, and refuses every call after it
    const real = runner({ env, timer: realTimer });
    let deployed = false;
    const git: Runner = { run: (call) => (deployed ? Promise.reject(new Error('git stopped')) : real.run(call)), undoable: (work, undo) => real.undoable(work, undo), close: () => real.close() };
    const node = (async () => {
      deployed = true;
      return new Response(JSON.stringify({ package: 'orders', version: '1.0.0', repository: 'orders', commit, flows: [], added: [], swapped: [], removed: [], replaces: 'none' }));
    }) as typeof fetch;
    const call = await serve({ workspace: join(temp, 'work'), git, fetch: node });

    try {
      expect(await call('/git/packages/orders/deploy', 'POST', JSON.stringify({ commit, check: false, expect: 'none' }))).toMatchObject({
        status: 200,
        body: {
          deploy: { package: 'orders', version: '1.0.0', repository: 'orders', commit, flows: [], added: [], swapped: [], removed: [] },
          replaces: 'none',
          package: null,
          problem: "the package's state could not be read after the deploy: git stopped",
        },
      });
    } finally {
      await real.close();
    }
  });

  test('in session mode answer the node\'s end of the session as 401 for the list and a package, and keep a commit\'s 200', async () => {
    const { workspace, clone, env } = await orders();
    const git = runner({ env, timer: realTimer });
    const ended = (async () => new Response(JSON.stringify({ error: 'the session ended', code: 'session-ended' }), { status: 401 })) as typeof fetch;
    const call = await serve({ workspace, git, fetch: ended }, SESSION_MODE);
    try {
      const cleared = 'rail8-session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0';
      expect(await call('/git/packages')).toMatchObject({ status: 401, body: { code: 'session-ended' }, cookie: cleared });
      expect(await call('/git/packages/orders')).toMatchObject({ status: 401, body: { code: 'session-ended' }, cookie: cleared });
      await writeFile(join(clone, 'orders.txt'), 'one\n');
      const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: clone, env }).toString('utf8').trim();
      const committed = await call('/git/packages/orders/commit', 'POST', JSON.stringify({ message: 'more', branch: 'main', head, paths: [{ path: 'orders/orders.txt', tag: etagOf(Buffer.from('one\n')) }] }));
      expect(committed).toMatchObject({ status: 200, body: { package: null, problem: expect.stringContaining('session-ended') } });
    } finally {
      await git.close();
    }
  });

  test('in session mode refuse a write that names no session before anything is done', async () => {
    const call = await serve({}, SESSION_MODE);
    const res = await fetch(`http://127.0.0.1:${(server!.address() as AddressInfo).port}/git/packages/orders/commit`, {
      method: 'POST',
      body: '{}',
      headers: { 'sec-fetch-site': 'same-origin', cookie: `rail8-session=${SESSION}` },
    });
    expect(res.status).toBe(409);
    expect(await res.json()).toMatchObject({ code: 'session-changed' });
    expect((await call('/git/packages')).status).toBe(200);
  });
});
