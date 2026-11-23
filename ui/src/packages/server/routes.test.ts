import { mkdtemp, rm } from 'node:fs/promises';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { FakeTimer } from './fakeTimer.ts';
import { gitPackages, type GitPackagesOptions } from './routes.ts';
import type { Runner } from './runner.ts';

const ORIGIN = 'http://localhost:5173';
const idle: Runner = { run: () => Promise.reject(new Error('no git in this test')), close: async () => {} };

let server: Server | undefined;
let temp: string | undefined;

afterEach(async () => {
  await new Promise<void>((done) => (server === undefined ? done() : server.close(() => done())));
  server = undefined;
  if (temp !== undefined) await rm(temp, { recursive: true, force: true });
  temp = undefined;
});

/** The routes over `options` on a real HTTP server; a call that is not theirs is answered 418 by `next`. */
async function serve(options: Partial<GitPackagesOptions> = {}) {
  const middleware = gitPackages({
    writable: true,
    origins: [ORIGIN],
    node: { target: 'http://127.0.0.1:9' },
    git: idle,
    ready: Promise.resolve(null),
    timer: new FakeTimer(),
    ...options,
  });
  server = createServer((req, res) => middleware(req, res, () => res.writeHead(418).end()));
  await new Promise<void>((done) => server!.listen(0, '127.0.0.1', () => done()));
  const port = (server.address() as AddressInfo).port;
  return async (path: string, method = 'GET') => {
    const res = await fetch(`http://127.0.0.1:${port}${path}`, { method, headers: { 'sec-fetch-site': 'same-origin', origin: ORIGIN } });
    return { status: res.status, body: res.status === 418 ? null : ((await res.json()) as unknown) };
  };
}

describe('the routes of the git half', () => {
  test('pass every path outside /git to the next middleware', async () => {
    const call = await serve();
    expect((await call('/gitx/packages')).status).toBe(418);
    expect((await call('/api/files')).status).toBe(418);
  });

  test('answer 404 for a path under /git they do not serve, and 405 for another method', async () => {
    const call = await serve();
    expect(await call('/git/nothing')).toEqual({ status: 404, body: { error: 'the git half has no route /git/nothing' } });
    expect(await call('/git/packages', 'POST')).toEqual({ status: 405, body: { error: 'GET /git/packages is the only way to call this route' } });
  });

  test('answer 404 when the working folder that is set does not exist', async () => {
    temp = await mkdtemp(join(tmpdir(), 'rail8-routes-'));
    const call = await serve({ workspace: join(temp, 'gone') });
    expect(await call('/git/packages')).toEqual({ status: 404, body: { error: `the working folder ${join(temp, 'gone')} does not exist` } });
  });

  test('answer 503 with why on every path while git does not serve', async () => {
    const call = await serve({ ready: Promise.resolve('git is too old') });
    expect(await call('/git/packages')).toEqual({ status: 503, body: { error: 'git is too old' } });
    expect(await call('/git/nothing')).toEqual({ status: 503, body: { error: 'git is too old' } });
  });
});
