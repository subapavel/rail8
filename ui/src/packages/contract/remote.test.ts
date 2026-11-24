import { existsSync } from 'node:fs';
import { chmod, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type PackageState, type RemoteBody } from '../api';
import { REMOTE_REFUSALS } from '../rules';
import { httpWorld } from './httpWorld';
import { fakeWorld, type Build, type CloneSeed, type Seed, type World } from './seed';

// The fetch, pull and push cases on both implementations of PackagesApi: the real git half over real git, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('the %s git half fetches, pulls and pushes', (_, build) => {
  let world: World | undefined;
  afterEach(async () => {
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed) => (world = await build(seed));
  const one = (clone: Omit<CloneSeed, 'folder'>, seed: Seed = {}) => open({ ...seed, clones: [{ folder: 'orders', ...clone }] });
  const refusalOf = (call: Promise<unknown>) =>
    call.then(
      () => expect.unreachable('the call was not refused'),
      (error: unknown) => {
        expect(error).toBeInstanceOf(PackagesError);
        return error as PackagesError;
      },
    );
  const bodyOf = (state: PackageState): RemoteBody => ({ branch: state.branch!, head: state.head!.id, upstream: state.upstream?.name ?? null });
  const id = (label: string) => world!.id('orders', label);

  test('a fetch brings the commits of the upstream, and changes no file', async () => {
    const { api } = await one({ upstream: { at: 'first', unfetched: ['second'] } });
    expect((await api.get('orders')).upstream).toMatchObject({ name: 'origin/main', ahead: 0, behind: 0 });

    const answer = await api.fetch('orders');

    expect(answer.package).toEqual(await api.get('orders'));
    expect(answer.package!.upstream).toMatchObject({ name: 'origin/main', ahead: 0, behind: 1, fetchedAt: expect.any(String) });
    expect(answer.package!.head!.id).toBe(id('first'));
    expect(answer.package!.changes).toEqual([]);
  });

  test('a pull fast-forwards to the upstream and names the files it changed', async () => {
    const { api } = await one({
      files: { 'a.txt': 'a\n', 'gone.txt': 'gone\n' },
      upstream: { at: 'first', unfetched: ['second'] },
      commitFiles: { second: { 'a.txt': 'a, upstream\n', 'gone.txt': null } },
    });

    const answer = await api.pull('orders', bodyOf(await api.get('orders')));

    expect(answer).toMatchObject({ head: id('second'), changed: ['orders/a.txt', 'orders/gone.txt', 'orders/second.txt'] });
    expect(answer.package).toEqual(await api.get('orders'));
    expect(answer.package).toMatchObject({ head: { id: id('second') }, upstream: { ahead: 0, behind: 0 }, changes: [] });
    expect(await api.file('orders', 'work', 'orders/a.txt')).toMatchObject({ text: 'a, upstream\n' });
  });

  test('a pull with nothing to bring, as when the branch is ahead, moves nothing', async () => {
    const { api } = await one({ commits: ['first', 'local'], upstream: { at: 'first' } });

    const answer = await api.pull('orders', bodyOf(await api.get('orders')));

    expect(answer).toMatchObject({ head: id('local'), changed: [], package: { head: { id: id('local') }, upstream: { ahead: 1, behind: 0 } } });
  });

  test('a pull of a branch that diverged from its upstream is refused with 409, and HEAD stays', async () => {
    const { api } = await one({ commits: ['first', 'local'], upstream: { at: 'first', more: ['second'] } });

    const refused = await refusalOf(api.pull('orders', bodyOf(await api.get('orders'))));

    expect(refused).toMatchObject({ status: 409, message: REMOTE_REFUSALS.diverged });
    expect((await api.get('orders')).head!.id).toBe(id('local'));
  });

  test('a pull that would overwrite a change on disk is refused with 409 and git lines, and the change stays', async () => {
    const { api } = await one({
      files: { 'a.txt': 'a\n' },
      upstream: { at: 'first', unfetched: ['second'] },
      commitFiles: { second: { 'a.txt': 'a, upstream\n' } },
      edits: { 'a.txt': 'a, local\n' },
    });

    const refused = await refusalOf(api.pull('orders', bodyOf(await api.get('orders'))));

    expect(refused).toMatchObject({ status: 409, message: REMOTE_REFUSALS.overwrites, output: expect.stringContaining('a.txt') });
    const after = await api.get('orders');
    expect(after).toMatchObject({ head: { id: id('first') }, upstream: { behind: 1 } });
    expect(await api.file('orders', 'work', 'orders/a.txt')).toMatchObject({ text: 'a, local\n' });
  });

  test('a pull that would overwrite an ignored file is refused with 409 and git lines, and the file stays', async () => {
    const { api } = await one({
      files: { '.gitignore': 'build.txt\n' },
      upstream: { at: 'first', unfetched: ['second'] },
      commitFiles: { second: { 'build.txt': 'built upstream\n' } },
      edits: { 'build.txt': 'built here\n' },
    });

    const refused = await refusalOf(api.pull('orders', bodyOf(await api.get('orders'))));

    expect(refused).toMatchObject({ status: 409, message: REMOTE_REFUSALS.overwrites, output: expect.stringContaining('build.txt') });
    expect((await api.get('orders')).head!.id).toBe(id('first'));
    expect(await api.file('orders', 'work', 'orders/build.txt')).toMatchObject({ text: 'built here\n' });
  });

  test('a save that waits for the pull is refused by its recheck when the pull changed its file', async () => {
    const { api, save, held } = await one({ files: { 'a.txt': 'a\n' }, upstream: { at: 'first', unfetched: ['second'] }, commitFiles: { second: { 'a.txt': 'a, upstream\n' } } });
    const before = await api.get('orders');
    const tag = (await api.file('orders', 'work', 'orders/a.txt')).etag;
    const holding = held();

    const pulling = api.pull('orders', bodyOf(before));
    await holding;
    const saved = save('orders/a.txt', 'a, saved\n', tag);

    expect((await pulling).head).toBe(id('second'));
    expect(await saved).toBe(412);
    expect(await api.file('orders', 'work', 'orders/a.txt')).toMatchObject({ text: 'a, upstream\n' });
  });

  test('a push sends HEAD to the upstream branch', async () => {
    const { api } = await one({ commits: ['first', 'local'], upstream: { at: 'first' } });

    const answer = await api.push('orders', bodyOf(await api.get('orders')));

    expect(answer.pushed).toEqual({ ref: 'refs/heads/main', from: id('first'), to: id('local') });
    expect(answer.package).toEqual(await api.get('orders'));
    expect(answer.package!.upstream).toMatchObject({ ahead: 0, behind: 0 });
  });

  test('a push the remote rejects is refused with 409 and git lines', async () => {
    const { api } = await one({ commits: ['first', 'local'], upstream: { at: 'first', unfetched: ['second'] } });

    const refused = await refusalOf(api.push('orders', bodyOf(await api.get('orders'))));

    expect(refused).toMatchObject({ status: 409, message: REMOTE_REFUSALS.rejected, output: expect.stringContaining('[rejected]') });
    expect((await api.get('orders')).upstream).toMatchObject({ ahead: 1, behind: 0 });
  });

  test('without an upstream, a fetch, a pull and a push are refused with 409, naming git push -u', async () => {
    const { api } = await one({});
    const body = bodyOf(await api.get('orders'));
    expect(body.upstream).toBeNull();

    for (const call of [api.fetch('orders'), api.pull('orders', body), api.push('orders', body)]) {
      expect(await refusalOf(call)).toMatchObject({ status: 409, message: REMOTE_REFUSALS.noUpstream });
    }
    expect(REMOTE_REFUSALS.noUpstream).toContain('git push -u');
  });

  test('a pull or a push after the branch was switched at the same commit is refused with 412', async () => {
    const { api } = await one({ upstream: { at: 'first' }, switchedTo: 'other' });
    const seen: RemoteBody = { branch: 'main', head: id('first'), upstream: 'origin/main' };
    expect((await api.get('orders')).branch).toBe('other');

    expect(await refusalOf(api.pull('orders', seen))).toMatchObject({ status: 412, message: REMOTE_REFUSALS.stale });
    expect(await refusalOf(api.push('orders', seen))).toMatchObject({ status: 412, message: REMOTE_REFUSALS.stale });
  });

  test('a busy clone or a detached HEAD is refused with 409', async () => {
    for (const clone of [{ detached: true }, { conflict: true }, { indexLock: true }] satisfies Omit<CloneSeed, 'folder'>[]) {
      const { api } = await one({ ...clone, upstream: { at: 'first' } });
      const state = await api.get('orders');
      const body: RemoteBody = { branch: state.branch ?? 'main', head: state.head!.id, upstream: state.upstream?.name ?? 'origin/main' };

      for (const call of [api.fetch('orders'), api.pull('orders', body), api.push('orders', body)]) {
        expect((await refusalOf(call)).status, JSON.stringify(clone)).toBe(409);
      }
      await world!.close();
      world = undefined;
    }
  });

  test('a malformed body of a pull or a push is refused with 400', async () => {
    const { api } = await one({ upstream: { at: 'first' } });
    const malformed = [{ branch: '', head: id('first'), upstream: null }, { branch: 'main', head: 'HEAD', upstream: null }, { branch: 'main', head: id('first'), upstream: 7 }];
    for (const body of malformed as unknown as RemoteBody[]) {
      expect((await refusalOf(api.pull('orders', body))).status).toBe(400);
      expect((await refusalOf(api.push('orders', body))).status).toBe(400);
    }
  });

  test('under RAIL8_READ_ONLY=true, and for a foreign page, a fetch, a pull and a push are refused with 403', async () => {
    const { api } = await one({ upstream: { at: 'first' } }, { readOnly: true });
    const body = bodyOf(await api.get('orders'));
    expect(await refusalOf(api.fetch('orders'))).toMatchObject({ status: 403, message: REMOTE_REFUSALS.readOnly('fetched') });
    expect(await refusalOf(api.pull('orders', body))).toMatchObject({ status: 403, message: REMOTE_REFUSALS.readOnly('pulled') });
    expect(await refusalOf(api.push('orders', body))).toMatchObject({ status: 403, message: REMOTE_REFUSALS.readOnly('pushed') });
    await world!.close();

    const foreign = await one({ upstream: { at: 'first' } }, { foreignPage: true });
    for (const call of [foreign.api.fetch('orders'), foreign.api.pull('orders', body), foreign.api.push('orders', body)]) expect((await refusalOf(call)).status).toBe(403);
  });

  test('a remote that asks for a password no credential helper gives fails at once, and opens no askpass window', async () => {
    const asked = await mkdtemp(join(tmpdir(), 'rail8-askpass-'));
    try {
      // the author's askpass program, which would open a window: it leaves a marker when git runs it
      const marker = join(asked, 'asked');
      const askpass = join(asked, 'askpass.sh');
      await writeFile(askpass, `#!/bin/sh\ntouch '${marker}'\necho nobody\n`);
      await chmod(askpass, 0o755);
      const { api } = await one({ commits: ['first', 'local'], upstream: { at: 'first', signIn: true } }, { author: { env: { SSH_ASKPASS: askpass, DISPLAY: ':0' } } });
      const body = bodyOf(await api.get('orders'));

      expect(await refusalOf(api.fetch('orders'))).toMatchObject({ status: 422, message: REMOTE_REFUSALS.failed('fetch'), output: expect.stringContaining('terminal prompts disabled') });
      expect(await refusalOf(api.pull('orders', body))).toMatchObject({ status: 422, message: REMOTE_REFUSALS.failed('fetch'), output: expect.stringContaining('terminal prompts disabled') });
      expect(await refusalOf(api.push('orders', body))).toMatchObject({ status: 422, message: REMOTE_REFUSALS.failed('push'), output: expect.stringContaining('terminal prompts disabled') });
      expect(existsSync(marker)).toBe(false);
      expect((await api.get('orders')).head!.id).toBe(id('local'));
    } finally {
      await rm(asked, { recursive: true, force: true });
    }
  });
});
