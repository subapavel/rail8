import { execFileSync, spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { createServer, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { admitted, etagOf, Refusal, replaceFile, workingFolderWrites, type WorkingFolderWrites } from '../../../server/workingFolder.ts';
import { httpPackagesApi } from '../api';
import type { FakeSent } from '../fake';
import { manifestOf } from '../rules';
import { FakeTimer } from '../server/fakeTimer.ts';
import { checkGit } from '../server/git.ts';
import { gitPackages } from '../server/routes.ts';
import { runner, type Spawn } from '../server/runner.ts';
import { realTimer } from '../server/timer.ts';
import { AUTHOR, DEPLOYED_AT, isId, manifestFor, OLD_GIT, type Build, type CloneSeed, type Zone } from './seed';

const ORIGIN = 'http://localhost:5173';
const TOKEN = 'the-admin-token';

const listen = (server: Server) => new Promise<number>((done) => server.listen(0, '127.0.0.1', () => done((server.address() as AddressInfo).port)));
const shut = (server: Server) => new Promise<void>((done) => server.close(() => done()));
const quoted = (text: string) => `'${text.replaceAll("'", "'\\''")}'`;

/**
 * The http world: real git in a temporary folder, behind the git half's routes on a real HTTP server, with a bare
 * `file://` upstream per clone and a fake node. The working folder lies in a repository of its own, so that a folder
 * whose `.git` is no repository is taken by git for one of its folders. Every git the world itself runs to build the
 * seed gets a home of its own, so the machine's git config never reaches it. Its saves share the working folder's
 * writes with the git half, as the editors' saves do.
 */
export const httpWorld: Build = async (seed) => {
  const temp = await realpath(await mkdtemp(join(tmpdir(), 'rail8-packages-')));
  const home = join(temp, 'home');
  const outer = join(temp, 'outer');
  const work = join(outer, 'work');
  await mkdir(home);
  await mkdir(work, { recursive: true });
  // the author's own git config, which the git half's git reads: their identity, or none, with no guess from the machine
  await writeFile(join(home, '.gitconfig'), seed.identity === null ? '[user]\n\tuseConfigOnly = true\n' : `[user]\n\tname = ${AUTHOR.name}\n\temail = ${AUTHOR.email}\n`);
  // the seed's own git, in UTC whatever the machine's time zone, unless a clone names another
  const setup = { PATH: process.env.PATH ?? '', HOME: home, GIT_CONFIG_NOSYSTEM: '1', LC_ALL: 'C', TZ: 'UTC' };
  const identity = ['-c', 'user.name=Seed Author', '-c', 'user.email=seed@example.com', '-c', 'commit.gpgsign=false', '-c', 'init.defaultBranch=main'];
  const zoned = (zone: Zone, cwd: string, ...args: string[]) =>
    execFileSync('git', [...identity, ...args], { cwd, env: { ...setup, TZ: zone }, stdio: ['ignore', 'pipe', 'pipe'] }).toString('utf8').trim();
  const git = (cwd: string, ...args: string[]) => zoned('UTC', cwd, ...args);
  const zones = new Map((seed.clones ?? []).map((c) => [c.folder, c.zone ?? 'UTC']));
  const write = async (file: string, text: string) => {
    await mkdir(dirname(file), { recursive: true });
    await writeFile(file, text);
  };
  git(outer, 'init', '-q');

  const ids = new Map<string, string>();
  const commit = (clone: string, folder: string, label: string) => {
    git(clone, 'add', '-A');
    zoned(zones.get(folder) ?? 'UTC', clone, 'commit', '-q', '--allow-empty', '-m', label);
    ids.set(`${folder}/${label}`, git(clone, 'rev-parse', 'HEAD'));
  };

  const build = async (seeded: CloneSeed) => {
    const clone = join(work, seeded.folder);
    await mkdir(clone);
    git(clone, 'init', '-q', '-b', 'main');
    const manifest = manifestFor(seeded);
    if (manifest !== null) await write(join(clone, 'package.yaml'), manifest);
    for (const [path, text] of Object.entries(seeded.files ?? {})) await write(join(clone, path), text);
    for (const path of seeded.outside ?? []) {
      await write(join(temp, 'outside', 'secret.txt'), 'not of the package\n');
      await mkdir(dirname(join(clone, path)), { recursive: true });
      await symlink(join(temp, 'outside', 'secret.txt'), join(clone, path));
    }
    for (const [path, target] of Object.entries(seeded.intoGit ?? {})) {
      await mkdir(dirname(join(clone, path)), { recursive: true });
      await symlink(join(clone, '.git', ...target.split('/').filter((s) => s !== '')), join(clone, path));
    }
    for (const label of seeded.commits ?? ['first']) {
      await write(join(clone, `${label}.txt`), `${label}\n`);
      for (const [path, text] of Object.entries(seeded.commitFiles?.[label] ?? {})) {
        if (text === null) await rm(join(clone, path));
        else await write(join(clone, path), text);
      }
      commit(clone, seeded.folder, label);
    }
    if (seeded.upstream !== undefined) {
      const bare = join(temp, 'remotes', `${seeded.folder}.git`);
      await mkdir(bare, { recursive: true });
      git(bare, 'init', '-q', '--bare', '-b', 'main');
      git(clone, 'remote', 'add', 'origin', pathToFileURL(bare).href);
      git(clone, 'push', '-q', 'origin', `${ids.get(`${seeded.folder}/${seeded.upstream.at}`)}:refs/heads/main`);
      if ((seeded.upstream.more ?? []).length > 0) {
        const side = join(temp, 'pushers', seeded.folder);
        await mkdir(dirname(side), { recursive: true });
        git(temp, 'clone', '-q', pathToFileURL(bare).href, side);
        for (const label of seeded.upstream.more ?? []) {
          await write(join(side, `${label}.txt`), `${label}\n`);
          commit(side, seeded.folder, label);
        }
        git(side, 'push', '-q', 'origin', 'main');
      }
      git(clone, 'fetch', '-q', 'origin');
      git(clone, 'config', 'branch.main.remote', 'origin');
      git(clone, 'config', 'branch.main.merge', 'refs/heads/main');
    }
    if (seeded.conflict) {
      const first = ids.get(`${seeded.folder}/${(seeded.commits ?? ['first'])[0]}`)!;
      git(clone, 'checkout', '-q', '-b', 'side', first);
      await write(join(clone, 'conflict.txt'), 'side\n');
      commit(clone, seeded.folder, 'side');
      git(clone, 'checkout', '-q', 'main');
      await write(join(clone, 'conflict.txt'), 'main\n');
      commit(clone, seeded.folder, 'conflict');
      try {
        git(clone, 'merge', '-q', '--no-edit', 'side');
      } catch {
        // the merge stops at its conflict, as it should
      }
      if (seeded.unmerged) for (const file of ['MERGE_HEAD', 'MERGE_MSG', 'MERGE_MODE']) await rm(join(clone, '.git', file), { force: true });
    }
    if (seeded.detached) git(clone, 'checkout', '-q', '--detach');
    for (const [from, to] of Object.entries(seeded.moves ?? {})) {
      await mkdir(dirname(join(clone, to)), { recursive: true });
      git(clone, 'mv', '--', from, to);
    }
    for (const [path, text] of Object.entries(seeded.staged ?? {})) {
      if (text === null) git(clone, 'rm', '-q', '--', path);
      else {
        await write(join(clone, path), text);
        git(clone, 'add', '--', path);
      }
    }
    for (const [path, text] of Object.entries(seeded.edits ?? {})) {
      if (text === null) await rm(join(clone, path));
      else await write(join(clone, path), text);
    }
    if (seeded.hook !== undefined) {
      const lines = ['#!/bin/sh'];
      if (seeded.hook.says !== undefined) lines.push(`printf '%s\\n' ${quoted(seeded.hook.says)}`);
      const written = [...Object.entries(seeded.hook.writes ?? {}).map(([path, text]) => [path, text, true] as const), ...Object.entries(seeded.hook.unstaged ?? {}).map(([path, text]) => [path, text, false] as const)];
      for (const [n, [path, text, staging]] of written.entries()) {
        const from = join(temp, 'hooks', seeded.folder, String(n));
        await write(from, text);
        lines.push(`cp ${quoted(from)} ${quoted(path)}${staging ? ` && git add -- ${quoted(path)}` : ''} || exit 2`);
      }
      lines.push(seeded.hook.refuses ? 'exit 1' : 'exit 0');
      await write(join(clone, '.git', 'hooks', 'pre-commit'), `${lines.join('\n')}\n`);
      await chmod(join(clone, '.git', 'hooks', 'pre-commit'), 0o755);
      if (seeded.hook.stopsGit) {
        await write(join(clone, '.git', 'hooks', 'post-commit'), '#!/bin/sh\nkill -TERM "$PPID"\n');
        await chmod(join(clone, '.git', 'hooks', 'post-commit'), 0o755);
      }
    }
    if (seeded.indexLock) await writeFile(join(clone, '.git', 'index.lock'), '');
  };

  for (const seeded of seed.clones ?? []) await build(seeded);
  for (const name of seed.plain ?? []) await write(join(work, name, 'orders.yaml'), 'kind: endpoint\n');
  for (const { folder, of } of seed.worktrees ?? []) git(join(work, of), 'worktree', 'add', '-q', '--detach', join(work, folder));
  for (const name of seed.nested ?? []) {
    await mkdir(join(work, name, '.git'), { recursive: true });
    await write(join(work, name, 'package.yaml'), `name: ${name}\nversion: 1.0.0\n`);
  }
  for (const { folder, to } of seed.links ?? []) await symlink(join(work, to), join(work, folder));

  // the node: what it runs, by repository, as a deploy changes it
  const running = new Map(
    (seed.node === 'silent' ? [] : (seed.node ?? [])).map((r) => [
      r.repository,
      { commit: isId(r.commit) ? r.commit : ids.get(`${r.repository}/${r.commit}`)!, state: r.state ?? 'RUNNING', problems: r.problems ?? [], deployedAt: DEPLOYED_AT },
    ]),
  );
  // the flows of a commit of the clone `repository`, as the fake node names them: each file under flows/, with its blob
  const flowsAt = (repository: string, commit: string, name: string) => {
    const listed = git(join(work, repository), 'ls-tree', '-z', commit, '--', 'flows/').split('\0').filter((l) => l !== '');
    return new Map(
      listed
        .map((line) => [line.slice(line.indexOf('\t') + 1), line.split(' ')[2]!.split('\t')[0]!] as const)
        .filter(([path]) => /^flows\/[^/]+\.yaml$/.test(path))
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
        .map(([path, blob]) => [`${name}/${path.slice('flows/'.length, -'.yaml'.length)}`, blob]),
    );
  };
  const sent: FakeSent[] = [];
  const held: ServerResponse[] = [];
  const hearing: (() => void)[] = [];
  const answer = (res: ServerResponse, status: number, body: unknown) => res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body));
  const deploy = (check: boolean, asked: { repository: string; commit: string; expect?: string }, actor: string, res: ServerResponse) => {
    sent.push({ check, body: asked, actor });
    if (typeof seed.deploys === 'object') {
      answer(res, seed.deploys.status, { error: seed.deploys.error, ...(seed.deploys.problems === undefined ? {} : { problems: seed.deploys.problems }) });
      return;
    }
    const record = running.get(asked.repository);
    const replaces = record?.commit ?? 'none';
    if (!check && asked.expect !== replaces) {
      answer(res, 412, { error: `the pool records ${replaces}, not ${asked.expect}`, replaces });
      return;
    }
    if (!check && seed.deploys === 'silent') {
      held.push(res);
      for (const done of hearing.splice(0)) done();
      return;
    }
    const shown = manifestOf(git(join(work, asked.repository), 'show', `${asked.commit}:package.yaml`)).package!;
    const before = record === undefined ? new Map<string, string>() : flowsAt(asked.repository, record.commit, shown.name);
    const after = flowsAt(asked.repository, asked.commit, shown.name);
    if (!check) running.set(asked.repository, { commit: asked.commit, state: 'RUNNING', problems: [], deployedAt: new Date().toISOString() });
    answer(res, 200, {
      package: shown.name,
      version: shown.version,
      repository: asked.repository,
      commit: asked.commit,
      flows: [...after.keys()],
      added: [...after.keys()].filter((f) => !before.has(f)),
      swapped: [...after.keys()].filter((f) => before.has(f) && before.get(f) !== after.get(f)),
      removed: [...before.keys()].filter((f) => !after.has(f)),
      replaces,
    });
  };

  // the node: it answers /admin/packages and /admin/deploy with the token, or nothing at all on a port no one listens on
  const node = createServer((req, res) => {
    if (seed.tokenRefused || req.headers.authorization !== `Bearer ${TOKEN}`) {
      answer(res, 401, { error: 'unauthorized' });
      return;
    }
    const url = new URL(req.url ?? '', 'http://node');
    if (req.method === 'POST' && url.pathname === '/admin/deploy') {
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => chunks.push(chunk));
      req.on('end', () => deploy(url.searchParams.get('check') === 'true', JSON.parse(Buffer.concat(chunks).toString('utf8')) as FakeSent['body'], String(req.headers['x-rail8-actor']), res));
      return;
    }
    const items = [...running].map(([repository, r]) => ({
      name: repository,
      version: '1.0.0',
      repository,
      commit: r.commit,
      deployedAt: r.deployedAt,
      flows: [],
      files: [],
      folders: [],
      artifacts: [],
      state: r.state,
      problems: r.problems,
      content: 'sha256:00',
      dependencies: [],
    }));
    answer(res, 200, { items });
  });
  const nodePort = await listen(node);
  if (seed.node === 'silent') await shut(node);

  // the development server's PATH: the real one, an empty folder, or a folder whose git is too old
  let path = process.env.PATH ?? '';
  if (seed.git !== undefined) {
    path = join(temp, 'bin');
    await mkdir(path);
    if (seed.git === 'old') {
      await writeFile(join(path, 'git'), `#!/bin/sh\necho "git version ${OLD_GIT}"\n`);
      await chmod(join(path, 'git'), 0o755);
    }
  }
  // a commit made in a terminal just before the git half's own add or commit starts
  const meanwhile = new Map((seed.clones ?? []).flatMap((c) => (c.meanwhile === undefined ? [] : [[join(work, c.folder), c] as const])));
  // what a terminal does just after the git half's own commit ends, before the runner hears of its end
  const afterwards = new Map((seed.clones ?? []).flatMap((c) => (c.afterwards === undefined ? [] : [[join(work, c.folder), c] as const])));
  // the command after whose end the runner closes, as the development server does when it ends
  let closing: string | undefined;
  const spawned: Spawn = (command, args, options) => {
    const seeded = args[1] === '-C' && (args[3] === 'add' || args[3] === 'commit') ? meanwhile.get(args[2]!) : undefined;
    if (seeded !== undefined) {
      meanwhile.delete(args[2]!);
      writeFileSync(join(args[2]!, `${seeded.meanwhile!}.txt`), `${seeded.meanwhile!}\n`);
      git(args[2]!, 'add', '--', `${seeded.meanwhile!}.txt`);
      git(args[2]!, 'commit', '-q', '-m', seeded.meanwhile!);
      ids.set(`${seeded.folder}/${seeded.meanwhile!}`, git(args[2]!, 'rev-parse', 'HEAD'));
    }
    const child = spawn(command, args, options);
    const after = args[1] === '-C' && args[3] === 'commit' ? afterwards.get(args[2]!) : undefined;
    if (after !== undefined) {
      afterwards.delete(args[2]!);
      const clone = args[2]!;
      const { commits, stages, amends } = after.afterwards!;
      child.once('exit', () => {
        for (const [path, text] of Object.entries(stages ?? {})) {
          writeFileSync(join(clone, path), text);
          git(clone, 'add', '--', path);
        }
        if (commits !== undefined) {
          writeFileSync(join(clone, `${commits}.txt`), `${commits}\n`);
          git(clone, 'add', '--', `${commits}.txt`);
          git(clone, 'commit', '-q', '-m', commits);
          ids.set(`${after.folder}/${commits}`, git(clone, 'rev-parse', 'HEAD'));
        }
        if (amends !== undefined) {
          writeFileSync(join(clone, `${amends}.txt`), `${amends}\n`);
          git(clone, 'add', '--', `${amends}.txt`);
          git(clone, 'commit', '-q', '--amend', '-m', amends);
          ids.set(`${after.folder}/${amends}`, git(clone, 'rev-parse', 'HEAD'));
        }
      });
    }
    if (args[1] === '-C' && closing !== undefined && args[3] === closing) {
      closing = undefined;
      child.once('exit', () => void runs.close());
    }
    return child;
  };
  const runs = runner({ env: { PATH: path, HOME: home }, timer: realTimer, spawn: spawned });

  // the working folder's writes, which tell `held` when a write takes the lock
  const all = workingFolderWrites();
  const holding: (() => void)[] = [];
  const writes: WorkingFolderWrites = (write) =>
    all((lock) =>
      write((work) =>
        lock(() => {
          for (const done of holding.splice(0)) done();
          return work();
        }),
      ),
    );

  // the clock the git half waits on the node by
  const clock = new FakeTimer();
  const middleware = gitPackages({
    workspace: seed.workspace === false ? undefined : work,
    writable: !seed.readOnly,
    writes,
    origins: [ORIGIN],
    node: { target: `http://127.0.0.1:${nodePort}`, token: TOKEN },
    git: runs,
    ready: checkGit(runs),
    timer: clock,
  });
  const front = createServer((req, res) => middleware(req, res, () => res.writeHead(418).end('next')));
  const port = await listen(front);
  const page = seed.foreignPage ? { 'sec-fetch-site': 'cross-site', origin: 'https://elsewhere.example' } : { 'sec-fetch-site': 'same-origin', origin: ORIGIN };
  const api = httpPackagesApi((input, init) => fetch(`http://127.0.0.1:${port}${String(input)}`, { ...init, headers: { ...(init?.headers as Record<string, string>), ...page } }));

  return {
    api,
    id: (folder, label) => ids.get(`${folder}/${label}`)!,
    async save(path, text, tag) {
      const file = join(work, ...path.split('/'));
      try {
        await admitted(writes, (lock) =>
          replaceFile(lock, file, text, async () => {
            if (etagOf(await readFile(file)) !== tag) throw new Refusal(412, `${path} changed since it was read`);
          }),
        );
        return 200;
      } catch (error) {
        if (error instanceof Refusal && error.status === 412) return 412;
        throw error;
      }
    },
    held: () => new Promise<void>((done) => holding.push(done)),
    sent: () => sent,
    heard: () => (held.length > 0 ? Promise.resolve() : new Promise<void>((done) => hearing.push(done))),
    elapse: (ms) => clock.advance(ms),
    deployedElsewhere(folder, label) {
      running.set(folder, { commit: ids.get(`${folder}/${label}`)!, state: 'RUNNING', problems: [], deployedAt: DEPLOYED_AT });
    },
    terminal: (folder, args) => git(join(work, folder), ...args),
    link: (folder, path, target) => symlink(target, join(work, folder, path)),
    closesAfter(command) {
      closing = command;
    },
    async close() {
      await shut(front);
      for (const res of held) res.destroy();
      if (seed.node !== 'silent') await shut(node);
      await runs.close();
      await rm(temp, { recursive: true, force: true });
    },
  };
};
