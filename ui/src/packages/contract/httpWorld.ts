import { execFileSync, spawn } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { appendFile, chmod, mkdir, mkdtemp, readdir, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { admitted, etagOf, Refusal, replaceFile, workingFolderWrites, type WorkingFolderWrites } from '../../../server/workingFolder.ts';
import { httpPackagesApi } from '../api';
import type { FakeSent } from '../fake';
import { manifestOf } from '../rules';
import { FakeTimer } from '../server/fakeTimer.ts';
import { checkGit } from '../server/git.ts';
import { uiSession } from '../../../server/session.ts';
import { gitPackages } from '../server/routes.ts';
import { runner, type Spawn } from '../server/runner.ts';
import { realTimer } from '../server/timer.ts';
import { HIDDEN_PREFIX } from '../server/clone.ts';
import { AUTHOR, DEPLOYED_AT, isId, manifestFor, OLD_GIT, type Build, type CloneSeed, type SourceSeed, type Zone } from './seed';

const ORIGIN = 'http://localhost:5173';
const TOKEN = 'the-admin-token';

/**
 * A fake of `rail8 lock <dir> --deps <dir>`, in Node, as the command line pins: each dependency `package.yaml`
 * declares to the one folder of the candidates whose `package.yaml` has its name and version, at that clone's `HEAD`,
 * when its files are those of `HEAD`, ignored ones too. Its content digest is the SHA-256 of the commit's tree id. It
 * writes `package.lock` through a temporary file and a rename and prints a pin line each, exit 0; else a `problem:`
 * line each, exit 1. `YAML_MODULE` stands for the path of the yaml package.
 */
const FAKE_LOCK = `#!/usr/bin/env node
const { execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { parse } = require(YAML_MODULE);
const [, , command, dir, flag, deps] = process.argv;
if (command !== 'lock' || flag !== '--deps') {
  console.error('usage: rail8 lock <dir> --deps <dir>');
  process.exit(2);
}
const read = (folder) => parse(fs.readFileSync(path.join(folder, 'package.yaml'), 'utf8'));
const declared = new Map();
for (const d of read(dir).dependencies ?? []) if (!declared.has(d.name)) declared.set(d.name, d.version);
const own = fs.realpathSync(dir);
const candidates = [];
for (const name of fs.readdirSync(deps).sort()) {
  try {
    if (fs.realpathSync(path.join(deps, name)) !== own) candidates.push({ name, manifest: read(path.join(deps, name)) });
  } catch {}
}
const git = (folder, ...args) => execFileSync('git', args, { cwd: path.join(deps, folder), encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const problems = [];
const pins = [];
for (const [name, version] of [...declared].sort(([a], [b]) => (a < b ? -1 : 1))) {
  const dependency = 'dependency ' + name + '@' + version + ': ';
  const found = candidates.find((c) => c.manifest?.name === name && c.manifest?.version === version);
  if (found === undefined) problems.push(dependency + 'no candidate in the working folder');
  else if (!fs.existsSync(path.join(deps, found.name, '.git'))) problems.push(dependency + found.name + ' is not at the root of a git working tree');
  else {
    const head = git(found.name, 'rev-parse', 'HEAD');
    if (git(found.name, 'status', '--porcelain', '--untracked-files=all', '--ignored') !== '') problems.push(dependency + found.name + ' differs from its HEAD ' + head + ': commit it first');
    else pins.push({ name, version, repository: found.name, commit: head, content: 'sha256:' + crypto.createHash('sha256').update(git(found.name, 'rev-parse', 'HEAD^{tree}')).digest('hex') });
  }
}
if (problems.length > 0) {
  for (const p of problems) console.log('problem: ' + p);
  process.exit(1);
}
const quoted = (value) => JSON.stringify(value);
let text = '# written by rail8 lock: the direct dependencies, sorted by name, each with its content digest, repository and commit\\nschemaVersion: 1\\nkind: lock\\ndependencies:' + (pins.length === 0 ? ' []\\n' : '\\n');
for (const p of pins) text += '  - name: ' + quoted(p.name) + '\\n    version: ' + quoted(p.version) + '\\n    content: ' + quoted(p.content) + '\\n    repository: ' + quoted(p.repository) + '\\n    commit: ' + quoted(p.commit) + '\\n';
const temp = path.join(dir, '.package.lock.' + crypto.randomUUID() + '.tmp');
fs.writeFileSync(temp, text);
fs.renameSync(temp, path.join(dir, 'package.lock'));
for (const p of pins) console.log(p.name + '@' + p.version + ' ' + p.repository + ' ' + p.commit + ' ' + p.content);
`;

const listen = (server: Pick<Server, 'listen' | 'address'>) => new Promise<number>((done) => server.listen(0, '127.0.0.1', () => done((server.address() as AddressInfo).port)));
const shut = (server: Pick<Server, 'close'>) => new Promise<void>((done) => server.close(() => done()));
const quoted = (text: string) => `'${text.replaceAll("'", "'\\''")}'`;

/**
 * The http world: real git in a temporary folder, behind the git half's routes on a real HTTP server, with a bare
 * `file://` upstream per clone and a fake node. The working folder lies in a repository of its own, so that a folder
 * whose `.git` is no repository is taken by git for one of its folders. Every git the world itself runs to build the
 * seed gets a home of its own, so the machine's git config never reaches it. Its saves share the working folder's
 * writes with the git half, as the editors' saves do.
 *
 * Its git host serves the seed's sources as bare repositories at `https://localhost:<port>/<path>` through Node's https
 * server and `git http-backend`, with a certificate made for the run that only the author's own git config trusts.
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
  // the clones whose upstream's remote asks for credentials, at /sign-in/<folder>.git of the git host
  const signIns: string[] = [];
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
      // another author's clone, which pushes the upstream's own commits
      const side = join(temp, 'pushers', seeded.folder);
      const pushed = async (labels: readonly string[]) => {
        if (labels.length === 0) return;
        await mkdir(dirname(side), { recursive: true });
        if (!existsSync(side)) git(temp, 'clone', '-q', pathToFileURL(bare).href, side);
        for (const label of labels) {
          await write(join(side, `${label}.txt`), `${label}\n`);
          for (const [path, text] of Object.entries(seeded.commitFiles?.[label] ?? {})) {
            if (text === null) await rm(join(side, path));
            else {
              await write(join(side, path), text);
              // a file the clone's .gitignore names is committed all the same, as another author may
              git(side, 'add', '-f', '--', path);
            }
          }
          commit(side, seeded.folder, label);
        }
        git(side, 'push', '-q', 'origin', 'main');
      };
      await pushed(seeded.upstream.more ?? []);
      git(clone, 'fetch', '-q', 'origin');
      git(clone, 'config', 'branch.main.remote', 'origin');
      git(clone, 'config', 'branch.main.merge', 'refs/heads/main');
      await pushed(seeded.upstream.unfetched ?? []);
      if (seeded.upstream.signIn) signIns.push(seeded.folder);
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
    if (seeded.switchedTo !== undefined) git(clone, 'checkout', '-q', '-b', seeded.switchedTo);
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

  // the git host: each source a bare repository, its remote HEAD set to its default branch
  const served = join(temp, 'served');
  const repositoryOf = (source: SourceSeed) => source.path.slice(source.path.lastIndexOf('/') + 1).replace(/\.git$/, '');
  const buildSource = async (source: SourceSeed) => {
    const repository = repositoryOf(source);
    const bare = join(served, ...source.path.split('/'));
    const branch = source.branch ?? 'main';
    const pushed = source.noHead ? (branch === 'main' ? 'develop' : branch) : branch;
    await mkdir(bare, { recursive: true });
    git(bare, 'init', '-q', '--bare', '-b', 'main');
    if (source.large) {
      // over 20 000 files, all of one blob, made at once
      const manifest = `name: ${repository}\nversion: 1.0.0\n`;
      const lines = ['blob', 'mark :1', 'data 2', 'x', `commit refs/heads/${pushed}`, 'committer Seed Author <seed@example.com> 1700000000 +0000', 'data 5', 'large'];
      lines.push(`M 100644 inline package.yaml`, `data ${manifest.length}`, manifest.slice(0, -1));
      for (let n = 0; n <= 20_000; n++) lines.push(`M 100644 :1 files/${String(n).padStart(5, '0')}.txt`);
      execFileSync('git', ['fast-import', '--quiet'], { cwd: bare, env: setup, input: `${lines.join('\n')}\n`, stdio: ['pipe', 'ignore', 'pipe'] });
    } else if ((source.commits ?? ['first']).length > 0) {
      const side = join(temp, 'authors', ...source.path.split('/'));
      await mkdir(side, { recursive: true });
      git(side, 'init', '-q', '-b', branch);
      const manifest = source.manifest === undefined ? `name: ${repository}\nversion: 1.0.0\n` : source.manifest;
      if (manifest !== null) await write(join(side, 'package.yaml'), manifest);
      for (const [path, text] of Object.entries(source.files ?? {})) await write(join(side, path), text);
      for (const path of source.executable ?? []) await chmod(join(side, path), 0o755);
      for (const label of source.commits ?? ['first']) {
        await write(join(side, `${label}.txt`), `${label}\n`);
        commit(side, repository, label);
      }
      git(side, 'push', '-q', bare, `HEAD:refs/heads/${pushed}`);
    }
    git(bare, 'symbolic-ref', 'HEAD', `refs/heads/${branch}`);
  };
  for (const source of seed.sources ?? []) await buildSource(source);

  // the host's certificate, for localhost only, made for this run
  const tls = join(temp, 'tls');
  await mkdir(tls);
  execFileSync(
    'openssl',
    ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-keyout', join(tls, 'host.key'), '-out', join(tls, 'host.crt'), '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost', '-days', '1'],
    { stdio: 'ignore' },
  );
  let releaseHeld = () => {};
  const heldUntil = new Promise<void>((done) => (releaseHeld = done));
  let waited = false;
  const waiters: (() => void)[] = [];
  const appeared = new Set<string>();
  const hostHome = join(temp, 'host-home');
  await mkdir(hostHome);
  /** One request to `git http-backend`, as CGI: its status, its headers and its body, read whole. */
  const backend = (req: IncomingMessage, url: URL) =>
    new Promise<{ status: number; headers: Record<string, string>; body: Buffer }>((done, fail) => {
      const cgi = spawn('git', ['http-backend'], {
        env: {
          PATH: process.env.PATH ?? '',
          HOME: hostHome,
          GIT_CONFIG_NOSYSTEM: '1',
          GIT_PROJECT_ROOT: served,
          GIT_HTTP_EXPORT_ALL: '1',
          PATH_INFO: decodeURIComponent(url.pathname),
          QUERY_STRING: url.search.slice(1),
          REQUEST_METHOD: req.method ?? 'GET',
          CONTENT_TYPE: req.headers['content-type'] ?? '',
          HTTP_CONTENT_ENCODING: String(req.headers['content-encoding'] ?? ''),
          GIT_PROTOCOL: String(req.headers['git-protocol'] ?? ''),
          REMOTE_ADDR: '127.0.0.1',
        },
        stdio: ['pipe', 'pipe', 'ignore'],
      });
      const out: Buffer[] = [];
      cgi.stdout.on('data', (chunk: Buffer) => out.push(chunk));
      cgi.on('error', fail);
      cgi.on('close', () => {
        const all = Buffer.concat(out);
        const end = all.indexOf('\r\n\r\n');
        const headers: Record<string, string> = {};
        let status = 200;
        for (const line of all.subarray(0, end).toString('latin1').split('\r\n')) {
          const colon = line.indexOf(':');
          const [name, value] = [line.slice(0, colon).trim(), line.slice(colon + 1).trim()];
          if (name.toLowerCase() === 'status') status = Number(value.split(' ')[0]);
          else headers[name] = value;
        }
        done({ status, headers, body: all.subarray(end + 4) });
      });
      req.pipe(cgi.stdin);
    });
  let hostRequests = 0;
  const host = createHttpsServer({ cert: readFileSync(join(tls, 'host.crt')), key: readFileSync(join(tls, 'host.key')) }, (req, res) => {
    hostRequests += 1;
    const url = new URL(req.url ?? '', 'https://localhost');
    const source = (seed.sources ?? []).find((s) => decodeURIComponent(url.pathname).startsWith(`/${s.path}/`));
    if (url.pathname.startsWith('/sign-in/')) {
      res.writeHead(401, { 'www-authenticate': 'Basic realm="rail8"' }).end();
      return;
    }
    void (async () => {
      if (source?.held) {
        waited = true;
        for (const done of waiters.splice(0)) done();
        await heldUntil;
      }
      if (source?.fails === 'credentials') {
        res.writeHead(401, { 'www-authenticate': 'Basic realm="rail8"' }).end();
        return;
      }
      if (source?.appears !== undefined && !appeared.has(source.path)) {
        appeared.add(source.path);
        if (source.appears === 'file') await writeFile(join(work, repositoryOf(source)), 'not the clone\n');
        else await mkdir(join(work, repositoryOf(source)));
      }
      const answer = await backend(req, url);
      res.writeHead(answer.status, answer.headers);
      if (source?.fails === 'broken' && answer.body.includes('packfile')) {
        // the connection is cut halfway through the pack
        res.write(answer.body.subarray(0, Math.floor(answer.body.length / 2)), () => res.socket?.destroy());
        return;
      }
      res.end(answer.body);
    })().catch(() => res.socket?.destroy());
  });
  const hostPort = await listen(host);
  // the author's git trusts the host's certificate through their own config, as for a host of their company
  await appendFile(join(home, '.gitconfig'), `[http "https://localhost:${hostPort}"]\n\tsslCAInfo = ${join(tls, 'host.crt')}\n`);
  for (const folder of signIns) git(join(work, folder), 'remote', 'set-url', 'origin', `https://localhost:${hostPort}/sign-in/${folder}.git`);
  if (seed.author?.config !== undefined) await appendFile(join(home, '.gitconfig'), `${seed.author.config.replaceAll('{port}', String(hostPort))}\n`);

  // the node: what it runs, by repository, as a deploy changes it
  const commitOf = (repository: string, commit: string) => (isId(commit) ? commit : ids.get(`${repository}/${commit}`)!);
  const running = new Map<string, { commit: string; state: 'RUNNING' | 'FAILED'; problems: string[]; deployedAt: string; dependencies?: { name: string; commit: string; content: string }[] }>(
    (seed.node === 'silent' ? [] : (seed.node ?? [])).map((r) => [
      r.repository,
      {
        commit: commitOf(r.repository, r.commit),
        state: r.state ?? 'RUNNING',
        problems: r.problems ?? [],
        deployedAt: DEPLOYED_AT,
        dependencies: (r.dependencies ?? []).map((d) => ({ name: d.name, commit: commitOf(d.repository, d.commit), content: d.content })),
      },
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
    if (!check) running.set(asked.repository, { commit: asked.commit, state: 'RUNNING', problems: [], deployedAt: DEPLOYED_AT });
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
      dependencies: (r.dependencies ?? []).map((d) => ({ name: d.name, version: '1.0.0', content: d.content, repository: d.name, commit: d.commit, direct: true })),
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
  // the command while which the runner closes
  let closingWhile: string | undefined;
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
    if (closingWhile !== undefined && args.includes(closingWhile)) {
      closingWhile = undefined;
      setImmediate(() => void runs.close());
    }
    return child;
  };
  const runs = runner({ env: { PATH: path, HOME: home, ...seed.author?.env }, timer: realTimer, spawn: spawned });

  // the working folder's writes, which tell `held` when a write takes the lock
  const all = workingFolderWrites();
  const holding: (() => void)[] = [];
  let lockGate: { before?: () => Promise<void>; after?: () => Promise<void> } | undefined;
  const writes: WorkingFolderWrites = (write) =>
    all((lock) =>
      write((work) =>
        lock(async () => {
          for (const done of holding.splice(0)) done();
          const at = lockGate;
          lockGate = undefined;
          await at?.before?.();
          const done = await work();
          await at?.after?.();
          return done;
        }),
      ),
    );

  // the Rail8 command line a lock runs: a fake of rail8 lock, or a file that is not there
  const cli = join(temp, 'rail8');
  if (seed.cli !== 'missing') {
    await writeFile(cli, FAKE_LOCK.replace('YAML_MODULE', JSON.stringify(createRequire(import.meta.url).resolve('yaml'))));
    await chmod(cli, 0o755);
  }

  // the clock the git half waits on the node by
  const clock = new FakeTimer();
  const middleware = gitPackages({
    workspace: seed.workspace === false ? undefined : work,
    writable: !seed.readOnly,
    writes,
    origins: [ORIGIN],
    node: { target: `http://127.0.0.1:${nodePort}` },
    git: runs,
    ready: checkGit(runs),
    timer: clock,
    cli,
  });
  // the session gate in token mode in front of the half, as the development server mounts it
  const gate = uiSession({ mode: { kind: 'token', token: TOKEN, variable: 'RAIL8_ADMIN_TOKEN' }, origins: [ORIGIN], target: `http://127.0.0.1:${nodePort}` });
  const front = createServer((req, res) => gate(req, res, () => middleware(req, res, () => res.writeHead(418).end('next'))));
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
    closesWhile(command) {
      closingWhile = command;
    },
    closeGit: () => runs.close(),
    atNextLock(at) {
      lockGate = at;
    },
    workingFolder: work,
    hostRequests: () => hostRequests,
    wire: async (route, body) => {
      const res = await fetch(`http://127.0.0.1:${port}${route}`, { method: 'POST', headers: { ...page, 'content-type': 'application/json', authorization: `Bearer ${TOKEN}` }, body: JSON.stringify(body) });
      return { status: res.status, body: (await res.json()) as unknown };
    },
    address: (path) => `https://localhost:${hostPort}/${path}`,
    waiting: () => (waited ? Promise.resolve() : new Promise<void>((done) => waiters.push(done))),
    release: () => releaseHeld(),
    leftovers: async () => (await readdir(work)).filter((name) => name.startsWith(HIDDEN_PREFIX)),
    async close() {
      releaseHeld();
      await shut(front);
      host.closeAllConnections();
      await shut(host);
      for (const res of held) res.destroy();
      if (seed.node !== 'silent') await shut(node);
      await runs.close();
      await rm(temp, { recursive: true, force: true });
    },
  };
};
