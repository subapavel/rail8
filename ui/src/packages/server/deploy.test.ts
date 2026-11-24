import { describe, expect, test } from 'vitest';
import { actorOf } from '../rules.ts';
import { deployCommit } from './deploy.ts';
import { FakeTimer } from './fakeTimer.ts';
import type { Runner } from './runner.ts';

const COMMIT = 'a'.repeat(40);
const TOKEN = 'the-admin-token';

/** git in a clone that holds every commit, with git's identity `ident` (null: none). */
function gitWith(ident: string | null = 'Jiří Novák <jiri@example.com> 1700000000 +0100'): Runner {
  return {
    run: async ({ args }) => {
      if (args[0] === 'var') return ident === null ? { code: 128, stdout: Buffer.alloc(0), stderr: Buffer.from('no identity') } : { code: 0, stdout: Buffer.from(`${ident}\n`), stderr: Buffer.alloc(0) };
      return { code: 0, stdout: Buffer.from(`${COMMIT}\n`), stderr: Buffer.alloc(0) };
    },
  } as Runner;
}

/** A node that answers every call with `status` and `body`. */
const answering = (status: number, body: string) => (async () => new Response(body, { status, headers: { 'content-type': 'application/json' } })) as typeof fetch;

const node = { target: 'http://127.0.0.1:8081', token: TOKEN };
const deploy = (send: typeof fetch, timer = new FakeTimer(), check = false) =>
  deployCommit(gitWith(), '/clone', 'orders', check ? { commit: COMMIT, check } : { commit: COMMIT, check, expect: 'none' }, node, timer, send);

describe('actorOf', () => {
  test('is the identity as Name <email>, each character outside printable ASCII as ?, cut to 128 characters, or ui', () => {
    expect(actorOf({ name: 'Jiří Novák', email: 'jiri@example.com' })).toBe('Ji?? Nov?k <jiri@example.com>');
    expect(actorOf({ name: 'Ann 🚀\tTab', email: 'ann@example.com' })).toBe('Ann ??Tab <ann@example.com>');
    expect(actorOf({ name: 'n'.repeat(200), email: 'e@example.com' })).toBe('n'.repeat(128));
    expect(actorOf(null)).toBe('ui');
  });
});

describe('deployCommit', () => {
  test('sends the body, the token and the actor, and answers the node’s deploy', async () => {
    let asked: { url: string; init: RequestInit } | undefined;
    const send = (async (url: string, init: RequestInit) => {
      asked = { url, init };
      return new Response(JSON.stringify({ package: 'orders', version: '1.0.0', repository: 'orders', commit: COMMIT, flows: ['orders/a'], added: ['orders/a'], swapped: [], removed: [], replaces: 'none' }));
    }) as typeof fetch;

    expect(await deploy(send)).toEqual({ deploy: { package: 'orders', version: '1.0.0', repository: 'orders', commit: COMMIT, flows: ['orders/a'], added: ['orders/a'], swapped: [], removed: [] }, replaces: 'none' });
    expect(asked!.url).toBe('http://127.0.0.1:8081/admin/deploy');
    expect(JSON.parse(asked!.init.body as string)).toEqual({ repository: 'orders', commit: COMMIT, expect: 'none' });
    expect(asked!.init.headers).toMatchObject({ authorization: `Bearer ${TOKEN}`, 'x-rail8-actor': 'Ji?? Nov?k <jiri@example.com>' });
  });

  test('removes the token from what the node says', async () => {
    const refused = deploy(answering(500, JSON.stringify({ error: `the record write failed for ${TOKEN}`, problems: [`a ${TOKEN} b`] })));
    await expect(refused).rejects.toMatchObject({ status: 500, message: 'the record write failed for ***', problems: ['a *** b'] });
  });

  test('a 200 that is no deploy, and an answer over 8 MiB, are 502', async () => {
    await expect(deploy(answering(200, '{"package": "orders"}'))).rejects.toMatchObject({ status: 502, message: expect.stringContaining('no deploy the UI can read') });
    await expect(deploy(answering(200, `"${'x'.repeat(8 * 1024 * 1024)}"`))).rejects.toMatchObject({ status: 502, message: expect.stringContaining('more than 8 MiB') });
  });

  test('a check the node does not answer within 5 min is 504, and changes nothing', async () => {
    const timer = new FakeTimer();
    const never = ((_: string, init: RequestInit) =>
      new Promise<Response>((_, reject) => init.signal!.addEventListener('abort', () => reject(new Error('aborted'))))) as typeof fetch;
    const checking = deploy(never, timer, true);
    await timer.waitFor(1);
    timer.advance(5 * 60_000 - 1);
    expect(timer.pending).toBe(1);
    timer.advance(1);
    await expect(checking).rejects.toMatchObject({ status: 504, message: expect.stringContaining('the check changes nothing') });
  });
});
