import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readFile, realpath, stat } from 'node:fs/promises';
import { join, sep } from 'node:path';
import { etagOf, Refusal, walk } from '../../../server/workingFolder.ts';
import type { Artifact, Change, ChangeStatus, FileVersion, LogPage, PackageState } from '../api.ts';
import {
  artifactsOf,
  indexFrom,
  inPackage,
  isCommitId,
  isRepositoryName,
  isRevision,
  kindOf,
  LOG_MAX,
  LOG_PAGE,
  MAX_LISTED,
  oncePerPath,
  readForKind,
  READ_LIMIT,
  statusCompared,
  statusOf as changeStatusOf,
  walked,
  type Compared,
} from '../rules.ts';
import { formatted } from './formatter.ts';
import { catFile, GitSaid, holds, LOG_FORMAT, parseDiffTree, parseLog, parseNameStatus, parseTree, read, type Diffed } from './git.ts';
import { dependenciesOf } from './lock.ts';
import { folderOf, rowOf, statusOf, type NodeState } from './packages.ts';
import type { Runner } from './runner.ts';

/** How many bytes of blobs one `cat-file --batch` reads at most, well within a read's 4 MiB of output. */
const BATCH_BYTES = 3 * 1024 * 1024;

const missing = (error: unknown) => (error as NodeJS.ErrnoException).code === 'ENOENT' || (error as NodeJS.ErrnoException).code === 'ENOTDIR';

/**
 * The real path of the clone of the package `repository` directly in the real working folder `root`. Refused with 400
 * when `repository` is no repository name, and 404 when there is no such folder, or it is no package.
 */
export async function cloneOf(git: Runner, root: string, repository: string): Promise<string> {
  if (!isRepositoryName(repository)) throw new Refusal(400, `${JSON.stringify(repository)} is no repository name`);
  const info = await lstat(join(root, repository)).catch((error: unknown) => (missing(error) ? null : Promise.reject(error)));
  if (info === null) throw new Refusal(404, `no package ${repository} in the working folder`);
  const found = await folderOf(git, root, { name: repository, isSymbolicLink: () => info.isSymbolicLink() });
  if ('reason' in found) throw new Refusal(404, `${repository} is no package: ${found.reason}`);
  return found.clone;
}

/** Refused with 400 unless `rev` is a revision, and with 404 when it is a commit the clone at `clone` lacks. */
async function revisionIn(git: Runner, clone: string, rev: string, name: string, commitOnly = false): Promise<void> {
  if (commitOnly ? !isCommitId(rev) : !isRevision(rev)) {
    throw new Refusal(400, `${name} ${JSON.stringify(rev)} is no revision: ${commitOnly ? '' : 'work, or '}a commit of 40 hex digits`);
  }
  if (rev === 'work') return;
  // cat-file -e exits with 1, or 128 for an object it cannot name, when the clone lacks the commit
  if (!(await holds(git, clone, ['cat-file', '-e', '--end-of-options', `${rev}^{commit}`], [1, 128]))) throw new Refusal(404, `the clone holds no commit ${rev}`);
}

/** The path within the package of `path`, relative to the working folder; refused with 400 or 404 as {@link inPackage} says. */
function insideOf(repository: string, path: string): string {
  const found = inPackage(repository, path);
  if ('error' in found) throw new Refusal(found.status, found.error);
  return found.inside;
}

/** The git half's tag of the bytes of the file `file`, read as a stream: the `ETag` of {@link etagOf}. */
async function streamedTagOf(file: string): Promise<string> {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) hash.update(chunk as Buffer);
  return `"sha256-${hash.digest('hex')}"`;
}

/** Whether the real path `real` lies in the clone at `clone` outside its `.git` folder, in any letter case. */
export const inClone = (clone: string, real: string) => real.startsWith(clone + sep) && !real.slice(clone.length + 1).split(sep).some((s) => s.toLowerCase() === '.git');

/**
 * What lies on disk at `inside` of the clone at `clone`: null when nothing does; else its size, the tag of its bytes,
 * and its text when it is read for its kind and within the read limit. The tag is null when its real path leaves the
 * clone, leads into its `.git` folder or is no file.
 */
export async function diskAt(clone: string, inside: string): Promise<{ size: number; tag: string | null; text: string | null } | null> {
  const full = join(clone, ...inside.split('/'));
  const info = await lstat(full).catch((error: unknown) => (missing(error) ? null : Promise.reject(error)));
  if (info === null) return null;
  const real = await realpath(full).catch(() => null);
  const target = real === null || !inClone(clone, real) ? null : await stat(real);
  if (real === null || target === null || !target.isFile()) return { size: info.size, tag: null, text: null };
  if (target.size > READ_LIMIT) return { size: target.size, tag: await streamedTagOf(real), text: null };
  const bytes = await readFile(real);
  return { size: target.size, tag: etagOf(bytes), text: readForKind(inside.slice(inside.lastIndexOf('/') + 1)) ? bytes.toString('utf8') : null };
}

/** The blobs `names` of the clone, read in batches of at most {@link BATCH_BYTES}, given their sizes. */
async function blobsOf(git: Runner, clone: string, names: readonly { name: string; size: number }[]): Promise<Map<string, Buffer>> {
  const read = new Map<string, Buffer>();
  for (let i = 0; i < names.length; ) {
    const batch: string[] = [];
    let bytes = 0;
    while (i < names.length && (batch.length === 0 || bytes + names[i]!.size <= BATCH_BYTES)) {
      bytes += names[i]!.size;
      batch.push(names[i++]!.name);
    }
    (await catFile(git, clone, batch, false)).forEach((blob, n) => {
      if (blob !== null) read.set(batch[n]!, blob.bytes);
    });
  }
  return read;
}

/** Whether the older version holds a path git listed with `letter`, and the path it was renamed from. */
function comparedOf(d: Diffed): Compared {
  if (d.letter === 'R') return { inside: d.path, older: false, renamedFrom: d.from! };
  return { inside: d.path, older: d.letter !== 'A' };
}

/**
 * The changes of `compared` (at most {@link MAX_LISTED}), each path against the files on disk: its tag, its size
 * against the read limit, and its kind, from its text on disk, else from the older version `older` names.
 */
async function workChanges(
  git: Runner,
  clone: string,
  repository: string,
  compared: readonly Compared[],
  index: (inside: string, onDisk: boolean) => Change['index'],
  older: (inside: string) => string | null,
): Promise<Change[]> {
  const disk = await Promise.all(compared.map((c) => diskAt(clone, c.inside)));
  const gone = compared.flatMap((c, n) => {
    const name = older(c.renamedFrom ?? c.inside);
    return disk[n] === null && name !== null && readForKind(c.inside.slice(c.inside.lastIndexOf('/') + 1)) ? [name] : [];
  });
  const sizes = await catFile(git, clone, gone, true);
  const texts = await blobsOf(
    git,
    clone,
    gone.flatMap((name, n) => (sizes[n] !== null && sizes[n]!.type === 'blob' && sizes[n]!.size <= READ_LIMIT ? [{ name, size: sizes[n]!.size }] : [])),
  );
  return compared.map((c, n) => {
    const onDisk = disk[n]!;
    const path = `${repository}/${c.inside}`;
    const name = older(c.renamedFrom ?? c.inside);
    const text = onDisk?.text ?? (name === null ? undefined : texts.get(name)?.toString('utf8')) ?? null;
    return {
      path,
      kind: kindOf(path, text).kind,
      status: changeStatusOf(c, onDisk !== null),
      ...(c.renamedFrom === undefined ? {} : { renamedFrom: `${repository}/${c.renamedFrom}` }),
      index: index(c.inside, onDisk !== null),
      tag: onDisk?.tag ?? null,
      ...(onDisk !== null && onDisk.size > READ_LIMIT ? { large: true as const } : {}),
    };
  });
}

/** The package's artifacts on disk, by the working folder's walk of its folder, each with its change since `HEAD`. */
async function workArtifacts(clone: string, repository: string, changes: readonly Change[]): Promise<{ artifacts: Artifact[]; truncated: boolean }> {
  const { files, large, truncated } = await walk(clone, readForKind);
  const found = [...files.map((f) => ({ inside: f.path, text: f.text })), ...large.map((inside) => ({ inside, text: null }))];
  const present = async (insides: string[]) => {
    const real = await Promise.all(
      insides.map(async (inside) => {
        const full = join(clone, ...inside.split('/'));
        return (await realpath(full).catch(() => null)) === full && (await stat(full)).isFile();
      }),
    );
    return new Set(insides.filter((_, n) => real[n]));
  };
  const statuses = new Map<string, ChangeStatus>(changes.map((c) => [c.path, c.status]));
  return { artifacts: await artifactsOf(repository, found, present, statuses), truncated };
}

/** The package's artifacts at the commit `rev`: the blobs of its tree that the working folder's walk would look at. */
async function commitArtifacts(git: Runner, clone: string, repository: string, rev: string): Promise<{ artifacts: Artifact[]; truncated: boolean }> {
  const tree = parseTree(await read(git, clone, ['ls-tree', '-r', '-z', '--long', '--end-of-options', rev])).filter(
    (e) => e.type === 'blob' && (e.mode === '100644' || e.mode === '100755') && walked(e.path),
  );
  const typed = tree.filter((e) => readForKind(e.path.slice(e.path.lastIndexOf('/') + 1)));
  const truncated = typed.length > MAX_LISTED;
  const listed = typed.slice(0, MAX_LISTED);
  const texts = await blobsOf(git, clone, listed.filter((e) => e.size! <= READ_LIMIT).map((e) => ({ name: e.oid, size: e.size! })));
  const found = listed.map((e) => ({ inside: e.path, text: texts.get(e.oid)?.toString('utf8') ?? null }));
  const blobs = new Set(tree.map((e) => e.path));
  const present = async (insides: string[]) => new Set(insides.filter((inside) => blobs.has(inside)));
  return { artifacts: await artifactsOf(repository, found, present, new Map()), truncated };
}

/**
 * The author identity git gives in the clone at `clone`, from `git var GIT_AUTHOR_IDENT`, which fails, with 128, exactly
 * when a commit would fail for want of one: null then.
 */
export async function identityOf(git: Runner, clone: string): Promise<PackageState['identity']> {
  const ran = await git.run({ args: ['var', 'GIT_AUTHOR_IDENT'], clone, mode: 'read' });
  if (ran.code === 128) return null;
  const said = ran.stdout.toString('utf8');
  if (ran.code !== 0) throw new GitSaid(formatted(ran.stderr.toString('utf8')) || `git var exited with ${String(ran.code)}`);
  const ident = /^(.*) <([^<>\n]*)> \d+ [+-]\d{4}\n?$/.exec(said);
  if (ident === null) throw new GitSaid(`git var GIT_AUTHOR_IDENT answered ${formatted(said)}, which names no identity`);
  return { name: ident[1]!, email: ident[2]! };
}

/**
 * The package's state, as its tab shows it: its row, git's author identity, its changes against `HEAD`, its artifacts
 * on disk, its dependencies with the tags of `package.yaml` and `package.lock`, and the packages the node runs that pin it.
 */
export async function stateOf(git: Runner, clone: string, repository: string, node: NodeState): Promise<PackageState> {
  const status = await statusOf(git, clone);
  const compared = statusCompared(status.entries);
  const head = status.oid;
  const [{ row, usedBy }, identity, changes, dependencies] = await Promise.all([
    rowOf(git, clone, repository, node, status),
    identityOf(git, clone),
    workChanges(git, clone, repository, compared.slice(0, MAX_LISTED), indexFrom(status.entries), (inside) => (head === null ? null : `${head}:${inside}`)),
    dependenciesOf(git, clone, repository, node),
  ]);
  const artifacts = await workArtifacts(clone, repository, changes);
  return {
    ...row,
    identity,
    changes,
    artifacts: artifacts.artifacts,
    dependencies: dependencies.dependencies,
    usedBy,
    manifestTag: dependencies.manifestTag,
    lockTag: dependencies.lockTag,
    truncated: compared.length > MAX_LISTED || artifacts.truncated,
  };
}

/** Whether `bytes` are binary: a NUL among the first 8000 bytes, as git looks for it, or no UTF-8. */
function isBinary(bytes: Buffer): boolean {
  if (bytes.subarray(0, 8000).includes(0)) return true;
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    return false;
  } catch {
    return true;
  }
}

/**
 * One version of the file at `path`, relative to the working folder, at `rev`, with the tag of its bytes. Refused with
 * 400 for a malformed revision or path, or one with a `.git` segment; 404 for a commit the clone lacks, a path outside
 * the package or no file at it; 403 when its real path on disk leaves the package's real folder or leads into its
 * `.git` folder; 413 over the read limit.
 */
export async function fileOf(git: Runner, clone: string, repository: string, rev: string, path: string): Promise<FileVersion> {
  await revisionIn(git, clone, rev, 'rev');
  const inside = insideOf(repository, path);
  let bytes: Buffer;
  if (rev === 'work') {
    const real = await realpath(join(clone, ...inside.split('/'))).catch(() => null);
    if (real === null) throw new Refusal(404, `no file ${path} on disk`);
    if (!inClone(clone, real)) throw new Refusal(403, `${path} leads out of the package's folder or into its .git folder`);
    const info = await stat(real);
    if (!info.isFile()) throw new Refusal(404, `no file ${path} on disk`);
    if (info.size > READ_LIMIT) throw new Refusal(413, `${path} is over 1 MiB, the read limit`);
    bytes = await readFile(real);
  } else {
    const name = `${rev}:${inside}`;
    const [found] = await catFile(git, clone, [name], true);
    if (found === null || found === undefined || found.type !== 'blob') throw new Refusal(404, `no file ${path} in the commit ${rev}`);
    if (found.size > READ_LIMIT) throw new Refusal(413, `${path} is over 1 MiB, the read limit`);
    bytes = (await blobsOf(git, clone, [{ name, size: found.size }])).get(name) ?? Buffer.alloc(0);
  }
  return { ...(isBinary(bytes) ? { binary: true as const, size: bytes.length } : { text: bytes.toString('utf8') }), etag: etagOf(bytes) };
}

/**
 * The files that differ between the commit `from` and `to`, `work` or a commit, sorted by path, at most
 * {@link MAX_LISTED}. Against `work` they come from `diff --name-status -M` and the untracked paths of the status,
 * each with its index state and the tag of its bytes on disk; between two commits from `diff-tree -r -M`, each `clean`
 * and with no tag.
 */
export async function changesOf(git: Runner, clone: string, repository: string, from: string, to: string): Promise<{ changes: Change[]; truncated: boolean }> {
  await revisionIn(git, clone, from, 'from', true);
  await revisionIn(git, clone, to, 'to');
  if (to === 'work') {
    const [diffed, status] = await Promise.all([
      read(git, clone, ['diff', '-z', '--name-status', '-M', '--no-ext-diff', '--no-textconv', '--end-of-options', from, '--', ':(literal).']),
      statusOf(git, clone),
    ]);
    const compared = oncePerPath([
      ...parseNameStatus(diffed).map(comparedOf),
      ...status.entries.filter((e) => e.kind === 'untracked').map((e) => ({ inside: e.path, older: false })),
    ]);
    const changes = await workChanges(git, clone, repository, compared.slice(0, MAX_LISTED), indexFrom(status.entries), (inside) => `${from}:${inside}`);
    return { changes, truncated: compared.length > MAX_LISTED };
  }
  const diffed = parseDiffTree(await read(git, clone, ['diff-tree', '-r', '-z', '-M', '--no-commit-id', '--end-of-options', from, to, '--', ':(literal).']));
  const byPath = new Map(diffed.map((d) => [d.path, d] as const));
  const all = oncePerPath(diffed.map(comparedOf));
  const compared = all.slice(0, MAX_LISTED);
  const blobs = compared.flatMap((c) => byPath.get(c.inside)!.oids!.filter((oid) => !/^0+$/.test(oid)));
  const sizes = new Map((await catFile(git, clone, blobs, true)).map((found, n) => [blobs[n]!, found?.size ?? 0] as const));
  // the kind of each file comes from its newer blob, else its older one
  const kindBlob = (c: Compared) => {
    const [older, newer] = byPath.get(c.inside)!.oids!;
    return /^0+$/.test(newer) ? older : newer;
  };
  const texts = await blobsOf(
    git,
    clone,
    compared.filter((c) => readForKind(c.inside.slice(c.inside.lastIndexOf('/') + 1)) && (sizes.get(kindBlob(c)) ?? 0) <= READ_LIMIT).map((c) => ({ name: kindBlob(c), size: sizes.get(kindBlob(c)) ?? 0 })),
  );
  const changes = compared.map((c): Change => {
    const d = byPath.get(c.inside)!;
    const path = `${repository}/${c.inside}`;
    return {
      path,
      kind: kindOf(path, texts.get(kindBlob(c))?.toString('utf8') ?? null).kind,
      status: changeStatusOf(c, d.letter !== 'D'),
      ...(c.renamedFrom === undefined ? {} : { renamedFrom: `${repository}/${c.renamedFrom}` }),
      index: 'clean',
      tag: null,
      ...(d.oids!.some((oid) => (sizes.get(oid) ?? 0) > READ_LIMIT) ? { large: true as const } : {}),
    };
  });
  return { changes, truncated: all.length > MAX_LISTED };
}

/** The package's artifacts at `rev`, sorted by path: on disk with each one's change since `HEAD`, or at a commit. */
export async function artifactsAt(git: Runner, clone: string, repository: string, rev: string): Promise<{ artifacts: Artifact[]; truncated: boolean }> {
  await revisionIn(git, clone, rev, 'rev');
  if (rev !== 'work') return commitArtifacts(git, clone, repository, rev);
  const status = await statusOf(git, clone);
  const head = status.oid;
  const changes = await workChanges(git, clone, repository, statusCompared(status.entries).slice(0, MAX_LISTED), indexFrom(status.entries), (inside) =>
    head === null ? null : `${head}:${inside}`,
  );
  return workArtifacts(clone, repository, changes);
}

/**
 * One page of the first-parent history of `HEAD`, or of the commit `before`, newest first: `limit` commits (50 by
 * default, at most 100), the commit the node runs marked, and the commit the next page starts at.
 */
export async function logOf(git: Runner, clone: string, repository: string, node: NodeState, before: string | undefined, limit: string | undefined): Promise<LogPage> {
  if (limit !== undefined && (!/^\d{1,3}$/.test(limit) || Number(limit) < 1 || Number(limit) > LOG_MAX)) throw new Refusal(400, `limit ${JSON.stringify(limit)} is no number from 1 to ${LOG_MAX}`);
  const count = limit === undefined ? LOG_PAGE : Number(limit);
  if (before !== undefined) await revisionIn(git, clone, before, 'before', true);
  else if (!(await holds(git, clone, ['rev-parse', '--verify', '--quiet', 'HEAD^{commit}'], [1]))) return { items: [], next: null };
  const listed = parseLog(await read(git, clone, ['log', '-z', '--first-parent', `-n${count + 1}`, LOG_FORMAT, '--end-of-options', before ?? 'HEAD', '--']));
  const answer = await node.read();
  const running = answer.answered ? answer.running.find((r) => r.repository === repository)?.commit : undefined;
  return {
    items: listed.slice(0, count).map((c) => (c.id === running ? { ...c, deployed: true as const } : c)),
    next: listed[count]?.id ?? null,
  };
}
