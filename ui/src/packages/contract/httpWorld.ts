import { execFileSync } from 'node:child_process';
import { chmod, mkdir, mkdtemp, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { httpPackagesApi } from '../api';
import { checkGit } from '../server/git.ts';
import { gitPackages } from '../server/routes.ts';
import { runner } from '../server/runner.ts';
import { realTimer } from '../server/timer.ts';
import { DEPLOYED_AT, isId, manifestFor, OLD_GIT, type Build, type CloneSeed } from './seed';

const ORIGIN = 'http://localhost:5173';
const TOKEN = 'the-admin-token';

const listen = (server: Server) => new Promise<number>((done) => server.listen(0, '127.0.0.1', () => done((server.address() as AddressInfo).port)));
const shut = (server: Server) => new Promise<void>((done) => server.close(() => done()));

/**
 * The http world: real git in a temporary folder, behind the git half's routes on a real HTTP server, with a bare
 * `file://` upstream per clone and a fake node. The working folder lies in a repository of its own, so that a folder
 * whose `.git` is no repository is taken by git for one of its folders. Every git the world itself runs to build the
 * seed gets a home of its own, so the machine's git config never reaches it.
 */
export const httpWorld: Build = async (seed) => {
  const temp = await realpath(await mkdtemp(join(tmpdir(), 'rail8-packages-')));
  const home = join(temp, 'home');
  const outer = join(temp, 'outer');
  const work = join(outer, 'work');
  await mkdir(home);
  await mkdir(work, { recursive: true });
  const setup = { PATH: process.env.PATH ?? '', HOME: home, GIT_CONFIG_NOSYSTEM: '1', LC_ALL: 'C' };
  const identity = ['-c', 'user.name=Seed Author', '-c', 'user.email=seed@example.com', '-c', 'commit.gpgsign=false', '-c', 'init.defaultBranch=main'];
  const git = (cwd: string, ...args: string[]) => execFileSync('git', [...identity, ...args], { cwd, env: setup, stdio: ['ignore', 'pipe', 'pipe'] }).toString('utf8').trim();
  const write = async (file: string, text: string) => {
    await mkdir(dirname(file), { recursive: true });
    await writeFile(file, text);
  };
  git(outer, 'init', '-q');

  const ids = new Map<string, string>();
  const commit = (clone: string, folder: string, label: string) => {
    git(clone, 'add', '-A');
    git(clone, 'commit', '-q', '--allow-empty', '-m', label);
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
  };

  for (const seeded of seed.clones ?? []) await build(seeded);
  for (const name of seed.plain ?? []) await write(join(work, name, 'orders.yaml'), 'kind: endpoint\n');
  for (const { folder, of } of seed.worktrees ?? []) git(join(work, of), 'worktree', 'add', '-q', '--detach', join(work, folder));
  for (const name of seed.nested ?? []) {
    await mkdir(join(work, name, '.git'), { recursive: true });
    await write(join(work, name, 'package.yaml'), `name: ${name}\nversion: 1.0.0\n`);
  }
  for (const { folder, to } of seed.links ?? []) await symlink(join(work, to), join(work, folder));

  // the node: it answers /admin/packages with the token, or nothing at all on a port no one listens on
  const node = createServer((req, res) => {
    if (req.headers.authorization !== `Bearer ${TOKEN}`) {
      res.writeHead(401, { 'content-type': 'application/json' }).end(JSON.stringify({ error: 'unauthorized' }));
      return;
    }
    const items = (seed.node === 'silent' ? [] : (seed.node ?? [])).map((r) => ({
      name: r.repository,
      version: '1.0.0',
      repository: r.repository,
      commit: isId(r.commit) ? r.commit : ids.get(`${r.repository}/${r.commit}`),
      deployedAt: DEPLOYED_AT,
      flows: [],
      files: [],
      folders: [],
      artifacts: [],
      state: r.state ?? 'RUNNING',
      problems: r.problems ?? [],
      content: 'sha256:00',
      dependencies: [],
    }));
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ items }));
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
  const runs = runner({ env: { PATH: path, HOME: home }, timer: realTimer });
  const middleware = gitPackages({
    workspace: seed.workspace === false ? undefined : work,
    writable: !seed.readOnly,
    origins: [ORIGIN],
    node: { target: `http://127.0.0.1:${nodePort}`, token: TOKEN },
    git: runs,
    ready: checkGit(runs),
    timer: realTimer,
  });
  const front = createServer((req, res) => middleware(req, res, () => res.writeHead(418).end('next')));
  const port = await listen(front);
  const page = seed.foreignPage ? { 'sec-fetch-site': 'cross-site', origin: 'https://elsewhere.example' } : { 'sec-fetch-site': 'same-origin', origin: ORIGIN };
  const api = httpPackagesApi((input, init) => fetch(`http://127.0.0.1:${port}${String(input)}`, { ...init, headers: { ...(init?.headers as Record<string, string>), ...page } }));

  return {
    api,
    id: (folder, label) => ids.get(`${folder}/${label}`)!,
    async close() {
      await shut(front);
      if (seed.node !== 'silent') await shut(node);
      await runs.close();
      await rm(temp, { recursive: true, force: true });
    },
  };
};
