import { execFileSync } from 'node:child_process';
import { chmod, lstat, mkdir, mkdtemp, readdir, readFile, realpath, rename, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type CloneAnswer } from '../api';
import { ADDRESS_REFUSALS, CLONE_REFUSALS, REASONS } from '../rules';
import { httpWorld } from './httpWorld';
import { fakeWorld, type Build, type Seed, type World } from './seed';

// Get a package, the same cases on both implementations of PackagesApi: the real git half over real git and a git host
// on https, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('Get a package on the %s git half', (_, build) => {
  let world: World | undefined;
  afterEach(async () => {
    // nothing hidden is left behind by any clone, before the world deletes its folder
    if (world !== undefined) expect(await world.leftovers()).toEqual([]);
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed) => (world = await build(seed));
  const refusalOf = (answer: Promise<unknown>) =>
    answer.then(
      () => expect.unreachable('the clone was not refused'),
      (error: unknown) => {
        expect(error).toBeInstanceOf(PackagesError);
        return error as PackagesError;
      },
    );

  test('a clone that succeeds is a package on main, level with origin/main, with nothing changed, and is listed', async () => {
    const { api, address, id } = await open({ sources: [{ path: 'acme/orders.git', commits: ['first', 'second'] }] });

    const answer = await api.clone(address('acme/orders.git'));

    expect(answer).toMatchObject({
      repository: 'orders',
      package: {
        repository: 'orders',
        package: { name: 'orders', version: '1.0.0' },
        branch: 'main',
        head: { id: id('orders', 'second'), subject: 'second' },
        upstream: { name: 'origin/main', ahead: 0, behind: 0, fetchedAt: null },
        busy: null,
        changed: 0,
        changes: [],
      },
    });
    expect((await api.list()).packages.map((p) => p.repository)).toEqual(['orders']);
    expect(await api.get('orders')).toEqual((answer as Extract<CloneAnswer, { package: object }>).package);
  });

  test('a repository whose default branch is not main is cloned on that branch', async () => {
    const { api, address } = await open({ sources: [{ path: 'acme/orders.git', branch: 'develop' }] });

    expect(await api.clone(address('acme/orders.git'))).toMatchObject({ package: { branch: 'develop', upstream: { name: 'origin/develop', ahead: 0, behind: 0 } } });
  });

  test.each([
    ['an empty repository', { path: 'acme/orders.git', commits: [] }],
    ['a repository whose remote HEAD names a branch it lacks', { path: 'acme/orders.git', noHead: true as const }],
    ['a repository without package.yaml', { path: 'acme/orders.git', manifest: null }],
  ])('%s is cloned, and is listed as a folder that is no package, with the reason', async (_, source) => {
    const { api, address } = await open({ sources: [source] });

    expect(await api.clone(address('acme/orders.git'))).toEqual({ repository: 'orders', package: null, reason: REASONS.noManifest });
    expect((await api.list()).others).toEqual([{ folder: 'orders', reason: REASONS.noManifest }]);
  });

  test.each([
    ['file:///srv/git/orders.git', ADDRESS_REFUSALS.scheme],
    ['/srv/git/orders.git', ADDRESS_REFUSALS.local],
    ['../orders', ADDRESS_REFUSALS.local],
    ['ext::ssh', ADDRESS_REFUSALS.transport],
    ['fd::17', ADDRESS_REFUSALS.transport],
    ['http://localhost/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['git://localhost/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['git+ssh://localhost/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['HTTPS://localhost/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['ssh://jiri:secret@localhost/acme/orders.git', ADDRESS_REFUSALS.password],
    ['https://jiri@localhost/acme/orders.git', ADDRESS_REFUSALS.httpsUser],
    ['https://[2001:db8:0:0:1:0:0:1]/orders.git', ADDRESS_REFUSALS.host],
    ['-oProxyCommand=touch:o', ADDRESS_REFUSALS.option],
    ['--upload-pack=touch', ADDRESS_REFUSALS.option],
    ['ssh://-oProxyCommand=touch/o.git', ADDRESS_REFUSALS.host],
    ['-x@host:o.git', ADDRESS_REFUSALS.option],
    ['host:-o.git', ADDRESS_REFUSALS.path],
    ['ssh://host:-1/o.git', ADDRESS_REFUSALS.port],
    ['https://localhost/acme/con.git', ADDRESS_REFUSALS.device],
    ['https://localhost/acme/orders.', ADDRESS_REFUSALS.trailingDot],
    ['https://localhost/acme/my%20orders.git', ADDRESS_REFUSALS.folderCharacter],
  ])('%j is refused with 400 before git runs, and nothing is written', async (address, error) => {
    const { api } = await open({});

    const refused = await refusalOf(api.clone(address));

    expect(refused).toMatchObject({ status: 400, message: error });
    expect(await api.list()).toMatchObject({ packages: [], others: [] });
    expect(world!.hostRequests?.() ?? 0).toBe(0);
  });

  test('an ssh:// address of a one-letter host passes the rule, and git that cannot reach it is a 422', async () => {
    // an ssh that fails at once, so the test never waits for a host named g
    const { api } = await open({ author: { config: '[core]\n\tsshCommand = false' } });

    expect(await refusalOf(api.clone('ssh://g/team/orders.git'))).toMatchObject({ status: 422, message: CLONE_REFUSALS.gitFailed });
  });

  test.each([
    ['a package', { clones: [{ folder: 'orders' }] }, { packages: ['orders'], others: [] }],
    ['a folder that is no package', { plain: ['orders'] }, { packages: [], others: [{ folder: 'orders', reason: REASONS.noClone }] }],
  ])('an entry of that name in the working folder, %s, refuses it with 409, and stays as it was', async (_, seed, listed) => {
    const { api, address } = await open({ ...seed, sources: [{ path: 'acme/orders.git' }] });
    const before = await api.list();

    expect(await refusalOf(api.clone(address('acme/orders.git')))).toMatchObject({ status: 409, message: CLONE_REFUSALS.exists });
    expect(world!.hostRequests?.() ?? 0).toBe(0);
    const after = await api.list();
    expect(after).toEqual(before);
    expect({ packages: after.packages.map((p) => p.repository), others: after.others }).toEqual(listed);
  });

  test('an entry of that name that appears while git clones refuses it with 409 at the move, and is kept', async () => {
    const { api, address } = await open({ sources: [{ path: 'acme/orders.git', appears: 'file' }] });

    expect(await refusalOf(api.clone(address('acme/orders.git')))).toMatchObject({ status: 409, message: CLONE_REFUSALS.exists });
    expect((await api.list()).packages).toEqual([]);
  });

  test('a second clone while one is under way is refused with 409, and the first one ends', async () => {
    const { api, address, waiting, release } = await open({ sources: [{ path: 'acme/orders.git', held: true }, { path: 'acme/billing.git' }] });

    const first = api.clone(address('acme/orders.git'));
    await waiting();
    expect(await refusalOf(api.clone(address('acme/billing.git')))).toMatchObject({ status: 409, message: CLONE_REFUSALS.another });
    release();

    expect(await first).toMatchObject({ repository: 'orders', package: { branch: 'main' } });
    expect((await api.list()).packages.map((p) => p.repository)).toEqual(['orders']);
  });

  test.each([
    ['a clone whose connection is cut halfway', 'broken' as const],
    ['a clone without the credentials the host asks for', 'credentials' as const],
  ])('%s is refused with 422 at once, with what git said, and leaves nothing', async (_, fails) => {
    const { api, address } = await open({ sources: [{ path: 'acme/orders.git', fails, files: { 'big.txt': 'x'.repeat(256 * 1024) } }] });

    const refused = await refusalOf(api.clone(address('acme/orders.git')));

    expect(refused).toMatchObject({ status: 422, message: CLONE_REFUSALS.gitFailed });
    expect(refused.output).toMatch(/\S/);
    expect(await api.list()).toMatchObject({ packages: [], others: [] });
  });

  test('a repository over the size limit is refused with 422 and leaves nothing', async () => {
    const { api, address } = await open({ sources: [{ path: 'acme/orders.git', large: true }] });

    expect(await refusalOf(api.clone(address('acme/orders.git')))).toMatchObject({ status: 422, message: CLONE_REFUSALS.tooLarge });
    expect(await api.list()).toMatchObject({ packages: [], others: [] });
  }, 60_000);

  test('a read-only server refuses it with 403, and so does the page of another site', async () => {
    const readOnly = await open({ readOnly: true, sources: [{ path: 'acme/orders.git' }] });
    expect(await refusalOf(readOnly.api.clone(readOnly.address('acme/orders.git')))).toMatchObject({ status: 403, message: CLONE_REFUSALS.readOnly });
    expect((await readOnly.api.list()).others).toEqual([]);
    await readOnly.close();

    const foreign = await open({ foreignPage: true, sources: [{ path: 'acme/orders.git' }] });
    expect((await refusalOf(foreign.api.clone(foreign.address('acme/orders.git')))).status).toBe(403);
  });
});

describe('Get a package on the real git half only', () => {
  let world: World | undefined;
  let dir: string | undefined;
  afterEach(async () => {
    if (world !== undefined) expect(await world.leftovers()).toEqual([]);
    await world?.close();
    world = undefined;
    if (dir !== undefined) await rm(dir, { recursive: true, force: true });
    dir = undefined;
  });

  const open = async (seed: Seed) => (world = await httpWorld(seed));
  /** A folder of the test's own, for scripts and the markers they write when they run. */
  const scratch = async () => (dir = await realpath(await mkdtemp(join(tmpdir(), 'rail8-clone-'))));
  /** A script at `name` in the test's folder that writes the marker `marker` there when it runs, and exits with `code`. */
  const script = async (name: string, marker: string, code = 0, body = '') => {
    const file = join(dir!, name);
    await writeFile(file, `#!/bin/sh\n${body}touch ${JSON.stringify(join(dir!, marker))}\nexit ${code}\n`);
    await chmod(file, 0o755);
    return file;
  };
  const exists = (path: string) => lstat(path).then(() => true, () => false);
  const refusalOf = (answer: Promise<unknown>) =>
    answer.then(
      () => expect.unreachable('the clone was not refused'),
      (error: unknown) => error as PackagesError,
    );

  test('answers 201 on the wire, with the new folder and its state', async () => {
    const { wire, address } = await open({ sources: [{ path: 'acme/orders.git' }] });

    const answered = await wire!('/git/clone', { address: address('acme/orders.git') });

    expect(answered).toMatchObject({ status: 201, body: { repository: 'orders', package: { repository: 'orders', branch: 'main' } } });
  });

  test('a server closed before the clone refuses it with 503 and makes nothing', async () => {
    const { api, address, closeGit, workingFolder } = await open({ sources: [{ path: 'acme/orders.git' }] });

    await closeGit!();

    expect((await refusalOf(api.clone(address('acme/orders.git')))).status).toBe(503);
    expect(await readdir(workingFolder!)).toEqual([]);
  });

  test('a server closed while git clones stops git and leaves nothing', async () => {
    const { api, address, closesWhile, workingFolder } = await open({ sources: [{ path: 'acme/orders.git' }] });
    closesWhile!('clone');

    expect((await refusalOf(api.clone(address('acme/orders.git')))).status).toBe(503);
    expect(await readdir(workingFolder!)).toEqual([]);
  });

  test.each(['before', 'after'] as const)('a server closed %s the move into place leaves the clone in place whole, and waits for it', async (when) => {
    const { api, address, closeGit, atNextLock, workingFolder } = await open({ sources: [{ path: 'acme/orders.git' }] });
    let closed: Promise<void> | undefined;
    atNextLock!({
      [when]: async () => {
        closed = closeGit!();
      },
    });

    const answer = await api.clone(address('acme/orders.git'));
    await closed;

    expect(answer).toMatchObject({ repository: 'orders', package: null, problem: expect.stringContaining('closing') });
    expect(await readdir(workingFolder!)).toEqual(['orders']);
    expect(await readFile(join(workingFolder!, 'orders', 'package.yaml'), 'utf8')).toBe('name: orders\nversion: 1.0.0\n');
    expect(await exists(join(workingFolder!, 'orders', '.git', 'HEAD'))).toBe(true);
  });

  test('an empty folder of that name is refused with 409, and is the same folder after it', async () => {
    const { api, address, workingFolder } = await open({ sources: [{ path: 'acme/orders.git' }] });
    await mkdir(join(workingFolder!, 'orders'));
    const before = await lstat(join(workingFolder!, 'orders'));

    expect((await refusalOf(api.clone(address('acme/orders.git')))).status).toBe(409);
    expect(world!.hostRequests!()).toBe(0);
    const after = await lstat(join(workingFolder!, 'orders'));
    expect([after.dev, after.ino]).toEqual([before.dev, before.ino]);
    expect(await readdir(join(workingFolder!, 'orders'))).toEqual([]);
  });

  test.each(['file', 'folder'] as const)('a %s of that name that appears while git clones is kept as it is', async (appears) => {
    const { api, address, workingFolder } = await open({ sources: [{ path: 'acme/orders.git', appears }] });

    expect((await refusalOf(api.clone(address('acme/orders.git')))).status).toBe(409);
    const kept = join(workingFolder!, 'orders');
    if (appears === 'file') expect(await readFile(kept, 'utf8')).toBe('not the clone\n');
    else expect(await readdir(kept)).toEqual([]);
  });

  test('a rename that fails for another reason answers 500, removes the claim and leaves nothing hidden', async () => {
    const { api, address, atNextLock, workingFolder } = await open({ sources: [{ path: 'acme/orders.git' }] });
    atNextLock!({
      before: async () => {
        const [hidden] = (await readdir(workingFolder!)).filter((n) => n.startsWith('.rail8-clone-'));
        await rm(join(workingFolder!, hidden!, 'orders'), { recursive: true });
      },
    });

    expect((await refusalOf(api.clone(address('acme/orders.git')))).status).toBe(500);
    expect(await readdir(workingFolder!)).toEqual([]);
  });

  test('a hook the repository ships at the author’s relative core.hooksPath does not run', async () => {
    await scratch();
    const marker = join(dir!, 'hook-ran');
    const { api, address } = await open({
      author: { config: '[core]\n\thooksPath = hooks' },
      sources: [{ path: 'acme/orders.git', files: { 'hooks/post-checkout': `#!/bin/sh\ntouch ${JSON.stringify(marker)}\n` }, executable: ['hooks/post-checkout'] }],
    });

    expect(await api.clone(address('acme/orders.git'))).toMatchObject({ package: { branch: 'main' } });
    expect(await exists(marker)).toBe(false);
  });

  test('a post-checkout hook of the author’s template does not run', async () => {
    await scratch();
    await mkdir(join(dir!, 'template', 'hooks'), { recursive: true });
    await rename(await script('post-checkout', 'template-ran'), join(dir!, 'template', 'hooks', 'post-checkout'));
    const { api, address } = await open({ author: { config: `[init]\n\ttemplateDir = ${dir!}/template` }, sources: [{ path: 'acme/orders.git' }] });

    expect(await api.clone(address('acme/orders.git'))).toMatchObject({ package: { branch: 'main' } });
    expect(await exists(join(dir!, 'template-ran'))).toBe(false);
  });

  test('the author’s LFS filter, required, does not run: the LFS file keeps its pointer', async () => {
    await scratch();
    const smudge = await script('smudge', 'lfs-ran', 0, 'cat\n');
    const pointer = 'version https://git-lfs.github.com/spec/v1\noid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\nsize 12345\n';
    const { api, address } = await open({
      author: { config: `[filter "lfs"]\n\tsmudge = ${smudge}\n\tclean = cat\n\trequired = true` },
      sources: [{ path: 'acme/orders.git', files: { '.gitattributes': '*.bin filter=lfs diff=lfs merge=lfs -text\n', 'data/x.bin': pointer } }],
    });

    expect(await api.clone(address('acme/orders.git'))).toMatchObject({ package: { branch: 'main' } });
    expect(await exists(join(dir!, 'lfs-ran'))).toBe(false);
    expect(await api.file('orders', 'work', 'orders/data/x.bin')).toMatchObject({ text: pointer });
  });

  test('the author’s protocol.file.allow=always and an insteadOf that rewrites the address to file:// are refused', async () => {
    await scratch();
    const bare = join(dir!, 'orders.git');
    const env = { ...process.env, HOME: dir!, GIT_CONFIG_NOSYSTEM: '1' };
    execFileSync('git', ['init', '-q', '--bare', '-b', 'main', bare], { env });
    execFileSync('git', ['init', '-q', '-b', 'main', join(dir!, 'side')], { env });
    await writeFile(join(dir!, 'side', 'package.yaml'), 'name: orders\nversion: 1.0.0\n');
    execFileSync('git', ['-C', join(dir!, 'side'), 'add', '-A'], { env });
    execFileSync('git', ['-C', join(dir!, 'side'), '-c', 'user.name=a', '-c', 'user.email=a@example.com', 'commit', '-q', '-m', 'first'], { env });
    execFileSync('git', ['-C', join(dir!, 'side'), 'push', '-q', bare, 'HEAD:refs/heads/main'], { env });
    const { api, workingFolder } = await open({ author: { config: `[protocol "file"]\n\tallow = always\n[url "file://${dir!}/"]\n\tinsteadOf = https://files.example.com/` } });

    const refused = await refusalOf(api.clone('https://files.example.com/orders.git'));

    expect(refused).toMatchObject({ status: 422, message: CLONE_REFUSALS.gitFailed, output: expect.stringContaining("transport 'file' not allowed") });
    expect(await readdir(workingFolder!)).toEqual([]);
  });

  test('the author’s SSH_ASKPASS is not run when the host asks for credentials', async () => {
    await scratch();
    const askpass = await script('askpass', 'askpass-ran');
    const { api, address } = await open({ author: { env: { SSH_ASKPASS: askpass, DISPLAY: ':0' } }, sources: [{ path: 'acme/orders.git', fails: 'credentials' }] });

    expect((await refusalOf(api.clone(address('acme/orders.git')))).status).toBe(422);
    expect(await exists(join(dir!, 'askpass-ran'))).toBe(false);
  });

  test('a save and a commit end while a clone transfers', async () => {
    const { api, address, waiting, release, save } = await open({
      clones: [{ folder: 'billing', edits: { 'first.txt': 'edited\n' } }],
      sources: [{ path: 'acme/orders.git', held: true }],
    });

    const cloning = api.clone(address('acme/orders.git'));
    await waiting();
    const manifest = await api.file('billing', 'work', 'billing/package.yaml');
    expect(await save('billing/package.yaml', 'name: billing\nversion: 1.0.1\n', manifest.etag)).toBe(200);
    const state = await api.get('billing');
    const committed = await api.commit('billing', { message: 'edited', branch: 'main', head: state.head!.id, paths: state.changes.map((c) => ({ path: c.path, tag: c.tag })) });
    expect(committed.commit).toMatchObject({ subject: 'edited' });
    release();

    expect(await cloning).toMatchObject({ repository: 'orders', package: { branch: 'main' } });
  });

  test.each([
    ['ssh://git@localhost:2222/acme/orders.git', ['-p', '2222', 'git@localhost', "git-upload-pack '/acme/orders.git'"]],
    ['git@localhost:acme/orders.git', ['git@localhost', "git-upload-pack 'acme/orders.git'"]],
  ])('%s reaches ssh with the host and the path as arguments, never as options', async (address, ends) => {
    await scratch();
    const argv = join(dir!, 'argv');
    const ssh = await script('ssh', 'ssh-ran', 1, `printf '%s\\n' "$@" > ${JSON.stringify(argv)}\n`);
    const { api } = await open({ author: { config: `[core]\n\tsshCommand = ${ssh}\n[ssh]\n\tvariant = ssh` } });

    expect((await refusalOf(api.clone(address))).status).toBe(422);
    const args = (await readFile(argv, 'utf8')).trimEnd().split('\n');
    expect(args.slice(-ends.length)).toEqual(ends);
    expect(args.filter((a) => a.startsWith('-'))).toEqual(args.filter((a) => a === '-o' || a === '-p'));
  });
});
