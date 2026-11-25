import { lstat, readdir, readFile, realpath, stat } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { etagOf, Refusal, type WorkingFolderLock } from '../../../server/workingFolder.ts';
import type { Dependency, LockBody, LockedPin } from '../api.ts';
import { BUILD_CLI, candidateOf, declaredOf, dependencyOf, LOCK_LIMITS, LOCK_REFUSALS, lockBodyOf as lockBodyRules, lockedPinsOf, lockStillHolds, manifestOf, PACKAGE_REFUSALS, pinsOf, READ_LIMIT, type Refused } from '../rules.ts';
import { formatted } from './formatter.ts';
import { read } from './git.ts';
import type { NodeState } from './packages.ts';
import { refusal } from './refused.ts';
import type { Runner } from './runner.ts';

/** A lock that `rail8 lock` refused or failed: its status, its refusal, its `problem:` lines, and what it said otherwise. */
export class LockRefused extends Refusal {
  readonly problems: string[] | undefined;
  readonly output: string | undefined;

  constructor(status: number, refused: Refused, problems?: string[], output?: string) {
    super(status, refused.code, refused.error, refused.values);
    this.problems = problems;
    this.output = output;
  }
}

/** `body` as a lock takes it, refused as {@link lockBodyRules} refuses it. */
export function lockBodyOf(body: unknown): LockBody {
  const found = lockBodyRules(body);
  if ('error' in found) throw refusal(found.status, found);
  return found;
}

const missing = (error: unknown) => (error as NodeJS.ErrnoException).code === 'ENOENT' || (error as NodeJS.ErrnoException).code === 'ENOTDIR';

/** The bytes of the file `file`, null when it is not there; refused with 413 over the read limit. */
async function bytesOf(file: string): Promise<Buffer | null> {
  const info = await stat(file).catch((error: unknown) => (missing(error) ? null : Promise.reject(error)));
  if (info === null || !info.isFile()) return null;
  if (info.size > READ_LIMIT) throw refusal(413, PACKAGE_REFUSALS.overReadLimit({ path: file.slice(file.lastIndexOf('/') + 1) }));
  return readFile(file);
}

/**
 * The folders directly in the real working folder `root` whose `package.yaml` gives a name and a version, as
 * `rail8 lock` finds its candidates: every entry whose `package.yaml` is a file, a link followed, the clone `clone`
 * aside. A folder whose `package.yaml` cannot be read, or gives no name and version, is none.
 */
async function foldersIn(root: string, clone: string): Promise<{ folder: string; name: string; version: string }[]> {
  const found: { folder: string; name: string; version: string }[] = [];
  for (const entry of (await readdir(root)).sort()) {
    const folder = join(root, entry);
    try {
      if ((await realpath(folder)) === clone) continue;
      const bytes = await bytesOf(join(folder, 'package.yaml'));
      const manifest = bytes === null ? null : manifestOf(bytes.toString('utf8')).package;
      if (manifest) found.push({ folder: entry, ...manifest });
    } catch {
      // a folder that cannot be read is no candidate, as for rail8 lock
    }
  }
  return found;
}

/**
 * The commit `HEAD` names in the candidate at `folder`, and whether its files differ from it, as `rail8 lock` compares
 * them: every file on disk but `.git`'s, ignored ones too. With no clone at its root, or no commit, `head` is null.
 */
async function candidateHeadOf(git: Runner, folder: string): Promise<{ head: string | null; changed: boolean }> {
  const real = await realpath(folder);
  const dotGit = await lstat(join(real, '.git')).catch(() => null);
  if (dotGit === null) return { head: null, changed: true };
  try {
    const top = (await read(git, real, ['rev-parse', '--show-toplevel'])).replace(/\r?\n$/, '');
    if ((await realpath(top).catch(() => top)) !== real) return { head: null, changed: true };
    const said = await git.run({ args: ['rev-parse', '--verify', '--quiet', 'HEAD^{commit}'], clone: real, mode: 'read' });
    if (said.code !== 0) return { head: null, changed: true };
    const status = await read(git, real, ['status', '--porcelain=v2', '-z', '--untracked-files=all', '--ignored=traditional']);
    return { head: said.stdout.toString('utf8').trim(), changed: status !== '' };
  } catch {
    return { head: null, changed: true };
  }
}

/**
 * The dependencies of the package `repository`, whose clone is at the real path `clone` directly in the real working
 * folder, sorted by name, with the tags of its `package.yaml` and `package.lock` on disk; each with its candidate
 * among the folders of the working folder, never looked up by its pin, and what the node runs for it when it runs the
 * package, unless `node` is null. Each folder of the working folder is read only when the package declares a dependency.
 */
export async function dependenciesOf(
  git: Runner,
  clone: string,
  repository: string,
  node: NodeState | null,
): Promise<{ dependencies: Dependency[]; manifestTag: string | null; lockTag: string | null; several: Map<string, string[]> }> {
  const [manifest, lock] = await Promise.all([bytesOf(join(clone, 'package.yaml')), bytesOf(join(clone, 'package.lock'))]);
  const declared = manifest === null ? [] : declaredOf(manifest.toString('utf8'));
  const pins = pinsOf(lock === null ? null : lock.toString('utf8'));
  const tags = { manifestTag: manifest === null ? null : etagOf(manifest), lockTag: lock === null ? null : etagOf(lock) };
  const several = new Map<string, string[]>();
  if (declared.length === 0) return { dependencies: [], ...tags, several };
  const root = dirname(clone);
  const folders = await foldersIn(root, clone);
  const answer = node === null ? null : await node.read();
  const runs = answer?.answered ? answer.running.find((r) => r.repository === repository) : undefined;
  const dependencies = await Promise.all(
    declared.map(async (d) => {
      const found = candidateOf(d.name, d.version, folders);
      if (found !== null && typeof found !== 'string') several.set(d.name, found.several);
      const candidate = typeof found === 'string' ? { folder: found, ...(await candidateHeadOf(git, join(root, found))) } : null;
      const running = runs?.dependencies.find((r) => r.name === d.name);
      return dependencyOf(d, pins.get(d.name), candidate, running === undefined ? undefined : { commit: running.commit, content: running.content });
    }),
  );
  return { dependencies, ...tags, several };
}

/**
 * Locks the dependencies of the package `repository`, whose clone is at the real path `clone`, under the working
 * folder's lock, which it holds to its end, with the Rail8 command line at `cli`: it checks that `body` states the tags of `package.yaml` and `package.lock`
 * and each dependency's candidate and `HEAD` as they are now (412), and that no dependency has several candidates
 * (409), then runs `<cli> lock <clone> --deps <working folder>` through the runner, at most 120 s and 1 MiB on each
 * stream. It answers the pins the command printed. Its `problem:` lines are a 422 with them as `problems`; a command
 * line that is missing or cannot start its jar is a 503 that says how to build it; any other end is a 500 with what it
 * said.
 */
export function lockPackage(git: Runner, lock: WorkingFolderLock, cli: string, clone: string, repository: string, body: LockBody): Promise<LockedPin[]> {
  return lock(async () => {
    // what the node runs is no part of the check, so the node is not read while the lock is held
    const now = await dependenciesOf(git, clone, repository, null);
    if (!lockStillHolds(body, now)) throw refusal(412, LOCK_REFUSALS.stale);
    for (const d of now.dependencies) {
      const several = now.several.get(d.name);
      if (several !== undefined) throw refusal(409, LOCK_REFUSALS.several({ dependency: `${d.name}@${d.version}`, folders: several.join(', ') }));
    }
    const missingCli = LOCK_REFUSALS.missing({ cli, build: BUILD_CLI });
    const ran = await git.run({
      args: ['lock', clone, '--deps', dirname(clone)],
      mode: 'write',
      limits: LOCK_LIMITS,
      program: { file: cli, name: 'rail8 lock', missing: missingCli },
    });
    const stdout = ran.stdout.toString('utf8');
    const said = formatted([stdout, ran.stderr.toString('utf8')].filter((t) => t.trim() !== '').join('\n'));
    if (ran.code === 0) {
      const pins = lockedPinsOf(stdout);
      if (pins !== null) return pins;
      throw new LockRefused(500, LOCK_REFUSALS.noPins, undefined, said);
    }
    const problems = stdout.split('\n').flatMap((line) => (line.startsWith('problem: ') ? [formatted(line.slice('problem: '.length))] : []));
    if (ran.code === 1 && problems.length > 0) throw new LockRefused(422, LOCK_REFUSALS.problems, problems);
    // bin/rail8 exits with 2 and says so when it finds no jar, and the shell with 126 or 127 when java cannot be started
    const noJar = ran.code === 2 && ran.stderr.toString('utf8').includes('no cli jar');
    if (noJar || ran.code === 126 || ran.code === 127) throw new LockRefused(503, missingCli, undefined, said);
    throw new LockRefused(500, ran.code === null ? LOCK_REFUSALS.killed : LOCK_REFUSALS.failed({ exitCode: ran.code }), undefined, said);
  });
}
