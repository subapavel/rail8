import { parse } from 'yaml';
import type { Artifact, Change, ChangeStatus, CommitBody, DeployBody, DiscardBody, Dependency, Kind, LockBody, LockedPin, PackageRow, Pin, RemoteBody } from './api';

/** At most this many packages are listed, and at most this many folders that are no package. */
export const MAX_PACKAGES = 100;

/** The oldest git the git half runs: older ones lack what it relies on. */
export const OLDEST_GIT = [2, 30] as const;

/** Why a folder directly in the working folder is no package. */
export const REASONS = {
  name: "its name is no repository name: at most 100 letters, digits, '.', '_' and '-', starting with neither '.' nor '-'",
  link: 'a symbolic link: a package is a folder itself',
  noClone: 'it holds no clone of its own: it has no .git folder',
  worktree: 'its .git is a file: a worktree or a submodule is not supported',
  nested: 'git takes it for a folder of another repository, not for a clone of its own',
  noManifest: 'its clone has no package.yaml at its root',
} as const;

/** What every route answers, with 503, when git is not on the development server's PATH, which is checked when the server starts. */
export const GIT_MISSING =
  'git is not installed or not on the PATH of the development server: the Packages screen needs git 2.30 or later; install it, then restart the development server';

/** What every route answers, with 503, when the development server's git is older than 2.30. */
export function gitTooOld(version: string): string {
  return `git ${version} is older than 2.30, which the Packages screen needs: install a newer git, then restart the development server`;
}

/** Whether `name` is a repository name: one segment of at most 100 letters, digits, `.`, `_` and `-`, starting with neither `.` nor `-`. */
export function isRepositoryName(name: string): boolean {
  return /^[A-Za-z0-9_][A-Za-z0-9._-]{0,99}$/.test(name);
}

/** Whether the file at `path` is the temporary file of a save (`.<name>.<uuid>.tmp`), which no list shows. */
export function isSaveTemporary(path: string): boolean {
  const name = path.slice(path.lastIndexOf('/') + 1);
  return /^\..+\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp$/.test(name);
}

/** The name and version a `package.yaml` of `text` gives, or why it gives none. */
export function manifestOf(text: string): Pick<PackageRow, 'package' | 'problem'> {
  let doc: unknown;
  try {
    doc = parse(text);
  } catch (error) {
    const said = error instanceof Error ? (error.message.split('\n', 1)[0] ?? '') : String(error);
    return { package: null, problem: `package.yaml is no YAML: ${said}` };
  }
  if (typeof doc !== 'object' || doc === null || !('name' in doc) || !('version' in doc) || typeof doc.name !== 'string' || typeof doc.version !== 'string') {
    return { package: null, problem: 'package.yaml gives no name and version as text' };
  }
  return { package: { name: doc.name, version: doc.version } };
}

/** At most this many changed paths of one package, of one comparison, or artifacts of one package are listed. */
export const MAX_LISTED = 1000;

/** One version of one file is read up to 1 MiB; a change over it is listed and not compared. */
export const READ_LIMIT = 1024 * 1024;

/** History comes 50 commits to a page by default, and at most 100. */
export const LOG_PAGE = 50;
export const LOG_MAX = 100;

/** The walk of a package's artifacts goes this deep, as the working folder's walk does, and passes over these folders. */
const MAX_DEPTH = 16;
const SKIPPED = new Set(['node_modules', 'target']);

/** Whether `rev` is a revision: `work`, the files on disk, or a commit of 40 hex digits; never a name such as `HEAD`. */
export function isRevision(rev: string): boolean {
  return rev === 'work' || isCommitId(rev);
}

export const isCommitId = (rev: string) => /^[0-9a-f]{40}$/.test(rev);

/** At most this many paths are chosen for one commit, and its message holds at most this many bytes. */
export const MAX_CHOSEN = 1000;
export const MAX_MESSAGE_BYTES = 8 * 1024;

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * `body` as a commit of the package `repository` takes it, or its refusal: 400 when it is malformed (a message that is
 * blank, over 8 KiB or holds a NUL; a branch that is no text; a head that is neither null nor 40 hex digits; more than
 * 1000 paths, a path twice, or a path that is malformed or has a `.git` segment), 404 for a path outside the package,
 * and 422 for no path at all, since there is nothing to commit.
 */
export function commitBodyOf(repository: string, body: unknown): CommitBody | { status: 400 | 404 | 422; error: string } {
  if (!isRecord(body)) return { status: 400, error: 'the request is no JSON object' };
  const { message, branch, head, paths } = body;
  if (typeof message !== 'string' || message.trim() === '') return { status: 400, error: 'the commit needs a message' };
  if (new TextEncoder().encode(message).length > MAX_MESSAGE_BYTES) return { status: 400, error: 'the message is over 8 KiB' };
  if (message.includes('\0')) return { status: 400, error: 'the message holds a NUL character' };
  if (typeof branch !== 'string' || branch === '') return { status: 400, error: 'the request names no branch' };
  if (head !== null && (typeof head !== 'string' || !isCommitId(head))) return { status: 400, error: 'head is neither null nor a commit of 40 hex digits' };
  if (!Array.isArray(paths)) return { status: 400, error: 'paths is no list' };
  if (paths.length > MAX_CHOSEN) return { status: 400, error: `a commit takes at most ${MAX_CHOSEN} paths` };
  const chosen: CommitBody['paths'] = [];
  for (const entry of paths as unknown[]) {
    if (!isRecord(entry) || typeof entry.path !== 'string' || (entry.tag !== null && typeof entry.tag !== 'string')) {
      return { status: 400, error: 'each path is {path, tag}, its tag a text or null' };
    }
    const found = inPackage(repository, entry.path);
    if ('error' in found) return found;
    if (chosen.some((c) => c.path === entry.path)) return { status: 400, error: `${entry.path} is chosen twice` };
    chosen.push({ path: entry.path, tag: entry.tag });
  }
  if (chosen.length === 0) return { status: 422, error: 'nothing to commit: no path is chosen' };
  return { message, branch, head, paths: chosen };
}

/**
 * `body` as a discard of the package `repository` takes it, or its refusal: 400 when it is malformed (a head that is
 * neither null nor 40 hex digits; an index other than `clean`, `staged` or null; more than 1000 paths, a path twice, or a
 * path that is malformed or has a `.git` segment), 404 for a path outside the package, and 422 for no path at all.
 */
export function discardBodyOf(repository: string, body: unknown): DiscardBody | { status: 400 | 404 | 422; error: string } {
  if (!isRecord(body)) return { status: 400, error: 'the request is no JSON object' };
  const { head, paths } = body;
  if (head !== null && (typeof head !== 'string' || !isCommitId(head))) return { status: 400, error: 'head is neither null nor a commit of 40 hex digits' };
  if (!Array.isArray(paths)) return { status: 400, error: 'paths is no list' };
  if (paths.length > MAX_CHOSEN) return { status: 400, error: `a discard takes at most ${MAX_CHOSEN} paths` };
  const chosen: DiscardBody['paths'] = [];
  for (const entry of paths as unknown[]) {
    if (
      !isRecord(entry) ||
      typeof entry.path !== 'string' ||
      (entry.tag !== null && typeof entry.tag !== 'string') ||
      (entry.index !== null && entry.index !== 'clean' && entry.index !== 'staged')
    ) {
      return { status: 400, error: 'each path is {path, tag, index}, its tag a text or null, its index clean, staged or null' };
    }
    const found = inPackage(repository, entry.path);
    if ('error' in found) return found;
    if (chosen.some((c) => c.path === entry.path)) return { status: 400, error: `${entry.path} is chosen twice` };
    chosen.push({ path: entry.path, tag: entry.tag, index: entry.index });
  }
  if (chosen.length === 0) return { status: 422, error: 'nothing to discard: no path is chosen' };
  return { head, paths: chosen };
}

/**
 * The paths of a discard of the changes `picked`, taken from `changes`, the package's state the dialog shows, in the
 * order picked: each with its tag and index; a renamed change brings the path it was renamed from right after it, with
 * the tag and index of that path's own row when `changes` lists one, else tag null and index null. Each path once. A
 * row whose index `differs`, which a discard refuses whatever the request says of it, is named `clean`.
 */
export function chosenPaths(picked: readonly Change[], changes: readonly Change[]): DiscardBody['paths'] {
  const paths: DiscardBody['paths'] = [];
  const add = (path: string, tag: string | null, index: Change['index'] | null) => {
    if (paths.some((p) => p.path === path)) return;
    paths.push({ path, tag, index: index === 'differs' ? 'clean' : index });
  };
  for (const change of picked) {
    add(change.path, change.tag, change.index);
    if (change.renamedFrom === undefined) continue;
    const own = changes.find((c) => c.path === change.renamedFrom);
    add(change.renamedFrom, own?.tag ?? null, own?.index ?? null);
  }
  return paths;
}

/** One chosen path of a commit or a discard: as the request names it, within the package, its tag as the author saw it, and whether it is on disk now. */
export interface Chosen {
  path: string;
  inside: string;
  tag: string | null;
  onDisk: boolean;
}

/**
 * Why a commit or a discard of `chosen` is stale (412), as `compared`, the paths that differ from `HEAD` now, shows it;
 * null when it is not: a path no longer among the changes; a renamed change named without the path it was renamed from;
 * or that path named as not on disk while it is on disk. Each reason ends with `nothing`, such as "nothing was committed".
 */
export function staleChoice(repository: string, chosen: readonly Chosen[], compared: readonly Compared[], nothing: string): string | null {
  const changed = new Set(compared.flatMap((c) => [c.inside, ...(c.renamedFrom === undefined ? [] : [c.renamedFrom])]));
  for (const c of chosen) if (!changed.has(c.inside)) return `${c.path} is no longer among the package's changes; ${nothing}`;
  // a renamed change is chosen with the path it was renamed from, as the author saw it: not on disk, unless the request
  // names that path with a tag of its own
  const renamedFrom = new Map(compared.flatMap((c) => (c.renamedFrom === undefined ? [] : [[c.inside, c.renamedFrom] as const])));
  for (const c of chosen) {
    const from = renamedFrom.get(c.inside);
    if (from === undefined) continue;
    const source = chosen.find((s) => s.inside === from);
    if (source === undefined) return `${c.path} is renamed from ${repository}/${from}, which is not among the paths chosen with it; ${nothing}`;
    if (source.tag === null && source.onDisk) return `${repository}/${from}, which ${c.path} was renamed from, is on disk: choose it too; ${nothing}`;
  }
  return null;
}

/** A deploy and its check wait this long for the node's answer: the wait of `rail8 deploy`. */
export const DEPLOY_MS = 5 * 60_000;

/**
 * `body` as a deploy or its check takes it, or its refusal (400): the commit is 40 hex digits; a deploy names the state
 * it expects, a commit of 40 hex digits or `none`, and never `any`, the blind overwrite; a check names none.
 */
export function deployBodyOf(body: unknown): DeployBody | { status: 400; error: string } {
  if (!isRecord(body)) return { status: 400, error: 'the request is no JSON object' };
  const { commit, check, expect } = body;
  if (typeof commit !== 'string' || !isCommitId(commit)) return { status: 400, error: 'commit is no commit of 40 hex digits' };
  if (typeof check !== 'boolean') return { status: 400, error: 'check is neither true nor false' };
  if (check) return expect === undefined ? { commit, check } : { status: 400, error: 'a check names no expect' };
  if (typeof expect !== 'string' || (expect !== 'none' && !isCommitId(expect))) {
    return { status: 400, error: 'a deploy names the state it expects to replace, as its check answered it: a commit of 40 hex digits, or none' };
  }
  return { commit, check, expect };
}

/**
 * `body` as a pull or a push takes it, or its refusal (400): the branch is text; `head` is 40 hex digits; `upstream` is
 * the upstream's name as the package's state gave it, such as `origin/main`, or null when it gave none.
 */
export function remoteBodyOf(body: unknown): RemoteBody | { status: 400; error: string } {
  if (!isRecord(body)) return { status: 400, error: 'the request is no JSON object' };
  const { branch, head, upstream } = body;
  if (typeof branch !== 'string' || branch === '') return { status: 400, error: 'the request names no branch' };
  if (typeof head !== 'string' || !isCommitId(head)) return { status: 400, error: 'head is no commit of 40 hex digits' };
  if (upstream !== null && (typeof upstream !== 'string' || upstream === '')) return { status: 400, error: 'upstream is neither null nor a name' };
  return { branch, head, upstream };
}

/** What a fetch, a pull and a push answer when they are refused, in both halves. */
export const REMOTE_REFUSALS = {
  readOnly: (what: 'fetched' | 'pulled' | 'pushed') => `this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was ${what}`,
  detached: 'HEAD is detached: fetch, pull and push work on a branch, which a terminal can switch to',
  stale: 'the clone is no longer at the branch, HEAD and upstream you saw; nothing was done',
  noUpstream: 'the branch has no upstream: push it once in a terminal with git push -u <remote> <branch>, then fetch, pull and push here',
  diverged: 'the branch and its upstream have diverged: merge or rebase in a terminal; nothing was pulled',
  overwrites: 'git would not fast-forward, as git says, as when it would overwrite a file on disk: commit, move or remove that file, then pull again; nothing was pulled',
  rejected: 'the remote rejected the push, as git says; nothing was pushed',
  failed: (what: 'fetch' | 'push') =>
    `git could not ${what}, as git says: if git could not sign in or does not know the host's key, run git ${what} in a terminal, where it can ask, then try again`,
} as const;

/** The longest actor a header names, as the node takes it. */
export const MAX_ACTOR = 128;

/**
 * Who a deploy names as its actor: git's identity in the clone as `Name <email>`, or `ui` when it gives none; each
 * character outside printable ASCII as `?`, since a header carries none, and cut to {@link MAX_ACTOR} characters.
 */
export function actorOf(identity: { name: string; email: string } | null): string {
  const said = identity === null ? 'ui' : `${identity.name} <${identity.email}>`;
  return Array.from(said, (c) => (c >= ' ' && c <= '~' ? c : '?')).slice(0, MAX_ACTOR).join('');
}

/**
 * The command that takes `insides`, paths of a clone that `HEAD` lacks, out of its index in a terminal in the clone, its
 * files staying on disk, whatever the index holds of them: `git restore --staged`, or `git rm --cached -f` on a branch
 * with no commit yet (`unborn`), which has no `HEAD` to restore from. A path the shell would read otherwise is quoted.
 */
export function unstageCommand(insides: readonly string[], unborn: boolean): string {
  const words = insides.map((inside) => (/^[\w./@%+=:,-]+$/.test(inside) ? inside : `'${inside.replaceAll("'", `'\\''`)}'`));
  return `${unborn ? 'git rm --cached -f --' : 'git restore --staged --'} ${words.join(' ')}`;
}

/**
 * The path within the package `repository` of `path`, a path relative to the working folder with `/`; or the refusal
 * of it: 400 when it is malformed or has a `.git` segment, 404 when it lies outside the package's folder.
 */
export function inPackage(repository: string, path: string): { inside: string } | { status: 400 | 404; error: string } {
  const segments = path.split('/');
  // eslint-disable-next-line no-control-regex
  if (segments.some((s) => s === '' || s === '.' || s === '..' || /[\\\u0000-\u001f\u007f]/.test(s))) return { status: 400, error: `the path ${JSON.stringify(path)} is malformed` };
  if (segments.some((s) => s.toLowerCase() === '.git')) return { status: 400, error: 'a path with a .git segment names git’s own files, never a file of the package' };
  if (segments[0] !== repository || segments.length < 2) return { status: 404, error: `${path} lies outside the package ${repository}` };
  return { inside: segments.slice(1).join('/') };
}

/** Whether the walk of a package's artifacts looks at the file at `inside`, a path within the package: no hidden or skipped segment, at most 16 folders deep. */
export function walked(inside: string): boolean {
  const segments = inside.split('/');
  return segments.length <= MAX_DEPTH + 1 && segments.every((s) => !s.startsWith('.') && !SKIPPED.has(s));
}

/** Whether a file of this name is read to find its kind: a YAML file, a mapping or `package.lock`. */
export function readForKind(name: string): boolean {
  return /\.ya?ml$/.test(name) || name.endsWith('.mapping.json') || name === 'package.lock';
}

/** The parsed value of a file of `name` holding `text`: JSON for a mapping, YAML otherwise; undefined when it cannot be parsed. */
function valueOf(name: string, text: string): unknown {
  try {
    return name.endsWith('.json') ? JSON.parse(text) : parse(text);
  } catch {
    return undefined;
  }
}

/**
 * The kind of the file at `path` holding `text` (null when it was not read), and the id it gives: by its name for
 * `*.mapping.json`, `package.yaml`, `package.lock` and `folder.yaml`; by the top-level `kind` key of a YAML file, a flow,
 * an endpoint or a test, whose id is its top-level `id`; else `file`.
 */
export function kindOf(path: string, text: string | null): { kind: Kind; id?: string } {
  const name = path.slice(path.lastIndexOf('/') + 1);
  if (name.endsWith('.mapping.json')) return { kind: 'mapping', id: name.slice(0, -'.mapping.json'.length) };
  if (name === 'package.yaml') return { kind: 'package' };
  if (name === 'package.lock') return { kind: 'lock' };
  if (name === 'folder.yaml') return { kind: 'folder' };
  if (text === null || !/\.ya?ml$/.test(name)) return { kind: 'file' };
  const doc = valueOf(name, text);
  if (typeof doc !== 'object' || doc === null || !('kind' in doc)) return { kind: 'file' };
  if (doc.kind !== 'flow' && doc.kind !== 'endpoint' && doc.kind !== 'test') return { kind: 'file' };
  return 'id' in doc && typeof doc.id === 'string' ? { kind: doc.kind, id: doc.id } : { kind: doc.kind };
}

/** The texts of a file of `name` holding `text` that may name another file of its package from the package's root. */
function namedPaths(name: string, text: string): string[] {
  const named: string[] = [];
  const visit = (value: unknown) => {
    if (typeof value === 'string') {
      if (/^[^\s/:]+(?:\/[^\s/:]+)*\.[A-Za-z0-9]+$/.test(value) && walked(value)) named.push(value);
    } else if (Array.isArray(value)) value.forEach(visit);
    else if (typeof value === 'object' && value !== null) Object.values(value).forEach(visit);
  };
  visit(valueOf(name, text));
  return named;
}

/**
 * The artifacts of the package `repository` among `files`, the files its walk found with their paths within the
 * package and their texts (null when not read), sorted by path: every file of a kind other than `file`, and every
 * other file one of them names from the package's root, with the artifacts that name it. `present` answers which of
 * the paths it is given are files of the package the walk looks at. Each artifact's change is `changes`' status of it.
 */
export async function artifactsOf(
  repository: string,
  files: readonly { inside: string; text: string | null }[],
  present: (insides: string[]) => Promise<Set<string>>,
  changes: ReadonlyMap<string, ChangeStatus>,
): Promise<Artifact[]> {
  const artifacts = new Map<string, Artifact>();
  const named = new Map<string, Set<string>>();
  for (const { inside, text } of files) {
    const path = `${repository}/${inside}`;
    const { kind, id } = kindOf(path, text);
    if (kind === 'file') continue;
    artifacts.set(inside, { path, kind, ...(id === undefined ? {} : { id }) });
    if (text === null) continue;
    for (const other of namedPaths(inside, text)) {
      if (other === inside) continue;
      if (!named.has(other)) named.set(other, new Set());
      named.get(other)!.add(path);
    }
  }
  const candidates = [...named.keys()].filter((inside) => !artifacts.has(inside)).slice(0, MAX_LISTED);
  for (const inside of await present(candidates)) {
    artifacts.set(inside, { path: `${repository}/${inside}`, kind: 'file', usedBy: [...named.get(inside)!].sort() });
  }
  return [...artifacts.values()]
    .map((a) => {
      const change = changes.get(a.path);
      return change === undefined ? a : { ...a, change };
    })
    .sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
}

/**
 * Whether the index holds the older version of a path (`clean`), its version on disk (`staged`), or neither
 * (`differs`), from what `git status --porcelain=v2` says of it: its record's kind and its two status letters, and
 * whether the path is on disk.
 */
export function indexOf(kind: 'ordinary' | 'renamed' | 'unmerged' | 'untracked', xy: string, onDisk: boolean): Change['index'] {
  if (kind === 'untracked') return 'clean';
  if (kind === 'unmerged') return 'differs';
  if (xy[0] === '.') return 'clean';
  if (xy[1] === '.' && !(xy[0] === 'D' && onDisk)) return 'staged';
  return 'differs';
}

/** One path two versions differ in, as git names it: whether the older version holds it, and the path it was renamed from. */
export interface Compared {
  inside: string;
  older: boolean;
  renamedFrom?: string;
}

/** How a path differs, from whether the older version holds it, whether the newer one does, and the path it was renamed from. */
export function statusOf(compared: Compared, newer: boolean): ChangeStatus {
  if (compared.renamedFrom !== undefined) return 'renamed';
  if (compared.older && newer) return 'modified';
  return compared.older ? 'deleted' : 'added';
}

/** `compared` with each path once, the first time it is named, sorted by path, without the temporary files of a save. */
export function oncePerPath(compared: readonly Compared[]): Compared[] {
  const seen = new Map<string, Compared>();
  for (const c of compared) if (!seen.has(c.inside) && !isSaveTemporary(c.inside)) seen.set(c.inside, c);
  return [...seen.values()].sort((a, b) => (a.inside < b.inside ? -1 : a.inside > b.inside ? 1 : 0));
}

/** One path as `git status --porcelain=v2` lists it: its record's kind, its two status letters, and the path it was renamed from. */
export interface StatusEntry {
  kind: 'ordinary' | 'renamed' | 'unmerged' | 'untracked';
  /** The index's and the work tree's status letters, such as `.M`; `??` for an untracked path. */
  xy: string;
  path: string;
  from?: string;
}

/** The paths that differ from `HEAD`, from the status `entries`, untracked ones included, each once, sorted. */
export function statusCompared(entries: readonly StatusEntry[]): Compared[] {
  return oncePerPath(
    entries.map((e) => {
      if (e.kind === 'renamed') return { inside: e.path, older: false, renamedFrom: e.from! };
      return { inside: e.path, older: e.kind === 'unmerged' || (e.kind === 'ordinary' && e.xy[0] !== 'A') };
    }),
  );
}

/**
 * Each path's index state as the status `entries` give it, from the first record of the path, so that a path the index
 * no longer holds but the disk does (a `D.` record, then an untracked one) reads `differs`; `clean` for a path they do
 * not list.
 */
export function indexFrom(entries: readonly StatusEntry[]): (inside: string, onDisk: boolean) => Change['index'] {
  const byPath = new Map<string, StatusEntry>();
  for (const e of entries) if (!byPath.has(e.path)) byPath.set(e.path, e);
  return (inside, onDisk) => {
    const entry = byPath.get(inside);
    return entry === undefined ? 'clean' : indexOf(entry.kind, entry.xy, onDisk);
  };
}

/** An address of a repository is at most this many characters. */
export const MAX_ADDRESS = 1000;

/** Why an address is not taken, one line each, saying what to do. None repeats the address or any part of it. */
export const ADDRESS_REFUSALS = {
  notText: 'the request is no {address} of text',
  empty: "paste the address of a repository, as its git host's Clone button gives it",
  space: 'paste only the address: it holds white space',
  long: `the address is over ${MAX_ADDRESS} characters: paste only the address`,
  character: "the address holds a character outside printable ASCII: paste it as its git host's Clone button gives it",
  option: "an address never starts with '-': paste the address of the repository",
  transport: "an address with '::' names a transport of its own, which is not taken: use the repository's https or ssh address",
  scheme: "only https:// and ssh:// addresses are taken: use the repository's https or ssh address",
  local: "a repository on this machine is not taken: clone it in a terminal, or use its git host's address",
  httpsUser: "remove the part before '@': your credential helper chooses the account",
  password: "the address holds a password: remove it; your credential helper or your ssh key signs in",
  user: "the user name before '@' is malformed: letters, digits, '.', '_' and '-', starting with a letter, a digit or '_'",
  host: "the host is malformed: labels of letters, digits, '-' and '_' joined by '.'; an IPv6 address is not taken, use the host's name",
  driveLetter: "a host of one letter is a drive letter to git on Windows: use the host's full name, or an ssh:// address",
  port: 'the port is no number from 1 to 65535',
  path: "the path of the address is malformed: segments of letters, digits, '.', '_', '~', '+' and '-' joined by '/', none of them '.' or '..' or starting with '-'",
  noRepository: 'the address names no repository: paste the address its git host gives',
  folderCharacter: "the repository's name holds a character a package's folder cannot have, such as a space: a package's folder takes letters, digits, '.', '_' and '-'",
  name: `the repository's name is no name of a package's folder: ${REASONS.name.slice('its name is no repository name: '.length)}`,
  trailingDot: "the repository's name ends with '.', which a folder on Windows cannot keep",
  device: "the repository's name is a device name on Windows, such as con or nul, which no folder can have",
} as const;

/** A label of a host: letters, digits, `-` and `_`, not starting with `-`, 1 to 63 characters. */
const HOST_LABEL = /^[A-Za-z0-9_][A-Za-z0-9_-]{0,62}$/;
/** A user of an ssh address. */
const SSH_USER = /^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}$/;
/** A segment of a path as it is written: in the URL forms `%XX` too. */
const LITERAL_SEGMENT = /^[A-Za-z0-9._~+-]+$/;
const ENCODED_SEGMENT = /^(?:[A-Za-z0-9._~+-]|%[0-9A-Fa-f]{2})+$/;
/** The names Windows keeps for its devices, with or without an extension. */
const DEVICE = /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?$/i;

type Refused = { status: 400; error: string };
const refused = (error: string): Refused => ({ status: 400, error });

/** Whether `host` is a host name or a dotted IPv4 address: labels joined by `.`, at most 253 characters in all. */
function isHost(host: string): boolean {
  return host.length >= 1 && host.length <= 253 && host.split('.').every((label) => HOST_LABEL.test(label));
}

/**
 * The decoded segments of `path` (a URL form's when `encoded`, else the scp form's literal path), with one trailing `/`
 * allowed; null when it is malformed. Each decoded byte is 0x20 to 0x7E and neither `/` nor `\`; no segment is `.` or
 * `..`, or starts with `-`.
 */
function segmentsOf(path: string, encoded: boolean): string[] | null {
  const parts = (path.endsWith('/') ? path.slice(0, -1) : path).split('/');
  const decoded: string[] = [];
  for (const part of parts) {
    if (!(encoded ? ENCODED_SEGMENT : LITERAL_SEGMENT).test(part)) return null;
    const bytes = part.replace(/%([0-9A-Fa-f]{2})/g, (_, hex: string) => String.fromCharCode(parseInt(hex, 16)));
    if (Array.from(bytes).some((c) => c < ' ' || c > '~' || c === '/' || c === '\\')) return null;
    if (bytes === '.' || bytes === '..' || bytes.startsWith('-')) return null;
    decoded.push(bytes);
  }
  return decoded;
}

/**
 * The address a person pasted to get a package, as the clone takes it, and the folder it clones into; or the refusal
 * (400) of the first rule it fails, in this order:
 *
 * 1. White space at both ends is trimmed, and a leading `git clone` with the white space after it dropped; any other
 *    white space is refused.
 * 2. 1 to 1000 characters, each printable ASCII.
 * 3. No leading `-`, so git never reads it as an option.
 * 4. No `::`, the form that names a transport of its own.
 * 5. With a scheme, only lowercase `https://` and `ssh://`.
 * 6. Without one, the scp-like ssh form `[user@]host:path` when a `:` comes before any `/`; anything else is a local
 *    path, refused.
 * 7. No user information in an https address; in an ssh one an optional user, never a password. A host of labels or a
 *    dotted IPv4 address, never an IPv6 literal, of at least 2 characters in the scp form. A port of 1 to 65535, in the
 *    URL forms only. A path of segments, decoded once in the URL forms and literal in the scp form.
 * 8. The folder: the last segment less one trailing `.git`, a repository name that does not end in `.` and is no
 *    Windows device name.
 *
 * The address answered is the text as it stands after step 1, so that `origin` is what a terminal would store.
 */
export function cloneSourceOf(text: unknown): { address: string; repository: string } | Refused {
  if (typeof text !== 'string') return refused(ADDRESS_REFUSALS.notText);
  const address = text.trim().replace(/^git\s+clone\s+/, '');
  if (/\s/.test(address)) return refused(ADDRESS_REFUSALS.space);
  if (address === '') return refused(ADDRESS_REFUSALS.empty);
  if (address.length > MAX_ADDRESS) return refused(ADDRESS_REFUSALS.long);
  if (!/^[\x21-\x7e]+$/.test(address)) return refused(ADDRESS_REFUSALS.character);
  if (address.startsWith('-')) return refused(ADDRESS_REFUSALS.option);
  if (address.includes('::')) return refused(ADDRESS_REFUSALS.transport);

  let segments: string[] | null;
  const scheme = /^([A-Za-z][A-Za-z0-9+.-]*):\/\//.exec(address);
  if (scheme !== null) {
    if (scheme[1] !== 'https' && scheme[1] !== 'ssh') return refused(ADDRESS_REFUSALS.scheme);
    const rest = address.slice(scheme[0].length);
    const slash = rest.indexOf('/');
    const authority = slash < 0 ? rest : rest.slice(0, slash);
    const at = authority.lastIndexOf('@');
    if (at >= 0) {
      if (scheme[1] === 'https') return refused(ADDRESS_REFUSALS.httpsUser);
      const user = authority.slice(0, at);
      if (user.includes(':')) return refused(ADDRESS_REFUSALS.password);
      if (!SSH_USER.test(user)) return refused(ADDRESS_REFUSALS.user);
    }
    const hostPort = authority.slice(at + 1);
    const colon = hostPort.indexOf(':');
    const host = colon < 0 ? hostPort : hostPort.slice(0, colon);
    if (!isHost(host)) return refused(ADDRESS_REFUSALS.host);
    if (colon >= 0) {
      const port = hostPort.slice(colon + 1);
      if (!/^\d{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535) return refused(ADDRESS_REFUSALS.port);
    }
    if (slash < 0 || rest.slice(slash) === '/') return refused(ADDRESS_REFUSALS.noRepository);
    segments = segmentsOf(rest.slice(slash + 1), true);
  } else {
    const colon = address.indexOf(':');
    const slash = address.indexOf('/');
    if (colon < 0 || (slash >= 0 && slash < colon)) return refused(ADDRESS_REFUSALS.local);
    const authority = address.slice(0, colon);
    const at = authority.lastIndexOf('@');
    if (at >= 0) {
      const user = authority.slice(0, at);
      if (!SSH_USER.test(user)) return refused(ADDRESS_REFUSALS.user);
    }
    const host = authority.slice(at + 1);
    if (!isHost(host)) return refused(ADDRESS_REFUSALS.host);
    if (host.length < 2) return refused(ADDRESS_REFUSALS.driveLetter);
    const path = address.slice(colon + 1);
    const relative = path.startsWith('/') ? path.slice(1) : path;
    if (relative === '' || relative === '/') return refused(ADDRESS_REFUSALS.noRepository);
    segments = segmentsOf(relative, false);
  }
  if (segments === null) return refused(ADDRESS_REFUSALS.path);

  const last = segments.at(-1)!;
  const repository = last.endsWith('.git') ? last.slice(0, -'.git'.length) : last;
  if (repository === '') return refused(ADDRESS_REFUSALS.noRepository);
  if (!/^[A-Za-z0-9._-]+$/.test(repository)) return refused(ADDRESS_REFUSALS.folderCharacter);
  if (!isRepositoryName(repository)) return refused(ADDRESS_REFUSALS.name);
  if (repository.endsWith('.')) return refused(ADDRESS_REFUSALS.trailingDot);
  if (DEVICE.test(repository)) return refused(ADDRESS_REFUSALS.device);
  return { address, repository };
}

/** A clone of Get a package may grow to this many bytes and this many files and folders on disk. */
export const CLONE_MAX_BYTES = 1024 * 1024 * 1024;
export const CLONE_MAX_ENTRIES = 20_000;

/** What Get a package answers, besides the address rule's own lines. */
export const CLONE_REFUSALS = {
  another: 'another package is being got: wait for it to end',
  exists: "the working folder holds an entry of the repository's name already: nothing was written; rename or remove it, or get the package in another working folder",
  readOnly: 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was got',
  gitFailed:
    'git could not clone the repository, and nothing was written: if git could not sign in or does not know the host’s key, run git ls-remote with this address in a terminal, where it can ask, then try again',
  tooLarge: `the repository is over ${CLONE_MAX_BYTES / (1024 * 1024 * 1024)} GiB or ${CLONE_MAX_ENTRIES.toLocaleString('en-US')} files and folders on disk, so nothing was written: clone it in a terminal`,
} as const;

/** What New package answers when it is refused, in both halves. */
export const NEW_PACKAGE_REFUSALS = {
  body: 'the request is no {repository, name} of two texts',
  repository: "the repository name must be one segment of at most 100 letters, digits, '.', '_' and '-', starting with neither '.' nor '-'",
  name: "the package name must be at most 64 lowercase letters, digits and '-', starting with a letter",
  exists: "the working folder holds an entry of the repository's name already: nothing was written; choose another name, or rename or remove that entry",
  readOnly: 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was made',
  noIdentity: 'git gives no author identity: set it in a terminal with git config user.name and git config user.email; nothing was made',
  gitFailed: 'git could not make the first commit, as git and the hooks say; nothing was made',
} as const;

/** Whether `name` is a package name, as `package.yaml`'s schema takes it: lowercase letters, digits and `-`, starting with a letter, at most 64 characters. */
export function isPackageName(name: string): boolean {
  return /^[a-z][a-z0-9-]{0,63}$/.test(name);
}

/**
 * `body` as New package takes it, or its refusal: 400 when it is no `{repository, name}` of two texts, or the
 * repository name is no name of a package's folder; 422 when `package.yaml`'s schema refuses the package name.
 */
export function newPackageBodyOf(body: unknown): { repository: string; name: string } | { status: 400 | 422; error: string } {
  if (!isRecord(body) || typeof body.repository !== 'string' || typeof body.name !== 'string') return { status: 400, error: NEW_PACKAGE_REFUSALS.body };
  if (!isRepositoryName(body.repository)) return { status: 400, error: NEW_PACKAGE_REFUSALS.repository };
  if (!isPackageName(body.name)) return { status: 422, error: NEW_PACKAGE_REFUSALS.name };
  return { repository: body.repository, name: body.name };
}

/**
 * The files of a new package named `name`, by path: `package.yaml` at version 0.1.0 with no dependency, `package.lock`
 * as `rail8 lock` writes it with no dependency, and `.gitattributes`, which keeps every file's bytes as they are.
 */
export function newPackageFiles(name: string): Record<string, string> {
  return {
    'package.yaml': `schemaVersion: 1\nkind: package\nname: ${name}\nversion: 0.1.0\ndependencies: []\n`,
    'package.lock':
      '# written by rail8 lock: the direct dependencies, sorted by name, each with its content digest, repository and commit\nschemaVersion: 1\nkind: lock\ndependencies: []\n',
    '.gitattributes': '* -text\n',
  };
}

/** The message of a new package's first commit. */
export const newPackageMessage = (name: string) => `Create package ${name}`;

/** `rail8 lock` runs at most 120 s, and writes at most 1 MiB on each stream. */
export const LOCK_LIMITS = { ms: 120_000, bytes: 1024 * 1024 } as const;

/** How the Rail8 command line is built, from the root of the Rail8 checkout: JDK 25 and its jar, as the quickstart builds them. */
export const BUILD_CLI = './mvnw -B -ntp -q package -DskipTests -pl :rail8-cli -am';

/** What a lock answers when it is refused, in both halves. */
export const LOCK_REFUSALS = {
  readOnly: 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was locked',
  stale: 'package.yaml, package.lock or a dependency’s folder is no longer what you saw: look at the dependencies again; nothing was locked',
  several: (dependency: string, folders: readonly string[]) =>
    `several folders of the working folder hold ${dependency}: ${folders.join(', ')}; rail8 lock could pin any of them, so remove or rename all but one; nothing was locked`,
  problems: 'rail8 lock refused the lock, for the problems it names; nothing was written',
  missing: (cli: string) =>
    `the Rail8 command line ${cli} is missing or cannot run: build it with ${BUILD_CLI} from the root of the Rail8 checkout (it needs JDK 25), or set RAIL8_CLI to it, then lock again`,
  failed: (code: number | null) => `rail8 lock ended with ${code === null ? 'a signal' : `exit code ${code}`}, as it says; nothing may have been written`,
} as const;

/** The dependencies a `package.yaml` of `text` declares, the first declaration of each name, sorted by name; none when it cannot be read. */
export function declaredOf(text: string): { name: string; version: string }[] {
  let doc: unknown;
  try {
    doc = parse(text);
  } catch {
    return [];
  }
  const list = isRecord(doc) && Array.isArray(doc.dependencies) ? (doc.dependencies as unknown[]) : [];
  const declared = new Map<string, string>();
  for (const d of list) {
    if (isRecord(d) && typeof d.name === 'string' && typeof d.version === 'string' && !declared.has(d.name)) declared.set(d.name, d.version);
  }
  return [...declared].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([name, version]) => ({ name, version }));
}

/** The pins of a `package.lock` of `text`, by name, each with its repository, commit and content as text; none for null or a lock that cannot be read. */
export function pinsOf(text: string | null): Map<string, Pin> {
  const pins = new Map<string, Pin>();
  if (text === null) return pins;
  let doc: unknown;
  try {
    doc = parse(text);
  } catch {
    return pins;
  }
  const list = isRecord(doc) && Array.isArray(doc.dependencies) ? (doc.dependencies as unknown[]) : [];
  for (const d of list) {
    if (!isRecord(d) || typeof d.name !== 'string' || pins.has(d.name)) continue;
    const { repository, commit, content } = d;
    if (typeof repository === 'string' && typeof commit === 'string' && typeof content === 'string') pins.set(d.name, { repository, commit, content });
  }
  return pins;
}

/**
 * The candidate of `name` at `version` among `folders`, the folders directly in the working folder with the name and
 * version their `package.yaml` gives, the package's own aside: the one that holds them, null when none does, or the
 * names of the folders when several do.
 */
export function candidateOf(name: string, version: string, folders: readonly { folder: string; name: string; version: string }[]): string | null | { several: string[] } {
  const matching = folders.filter((f) => f.name === name && f.version === version).map((f) => f.folder);
  if (matching.length === 0) return null;
  return matching.length === 1 ? matching[0]! : { several: matching };
}

/**
 * The dependency `declared`, pinned by `pin`, with its candidate `candidate`: its folder, the commit its `HEAD` names
 * (null: none) and whether its files differ from that commit; null when it has no candidate, or several. `running` is
 * what the node resolved for it.
 */
export function dependencyOf(
  declared: { name: string; version: string },
  pin: Pin | undefined,
  candidate: { folder: string; head: string | null; changed: boolean } | null,
  running?: { commit: string; content: string },
): Dependency {
  let state: Dependency['state'];
  if (candidate === null) state = 'missing';
  else if (candidate.changed || candidate.head === null) state = 'changed';
  else if (pin === undefined) state = 'unpinned';
  else state = pin.repository === candidate.folder && pin.commit === candidate.head ? 'pinned' : 'moved';
  return {
    ...declared,
    pin: pin ?? null,
    candidate: candidate === null ? null : { folder: candidate.folder, head: candidate.head },
    state,
    ...(running === undefined ? {} : { running }),
  };
}

/**
 * `body` as a lock takes it, or its refusal (400): each tag is text or null, and each dependency is
 * `{name, folder, head}`, its name text, its folder a repository name or null, and its head 40 hex digits or null, each
 * name once.
 */
export function lockBodyOf(body: unknown): LockBody | { status: 400; error: string } {
  if (!isRecord(body)) return { status: 400, error: 'the request is no JSON object' };
  const { manifestTag, lockTag, dependencies } = body;
  const tag = (value: unknown) => value === null || typeof value === 'string';
  if (!tag(manifestTag) || !tag(lockTag)) return { status: 400, error: 'manifestTag and lockTag are each a tag or null' };
  if (!Array.isArray(dependencies)) return { status: 400, error: 'dependencies is no list' };
  if (dependencies.length > MAX_LISTED) return { status: 400, error: `a lock names at most ${MAX_LISTED} dependencies` };
  const named: LockBody['dependencies'] = [];
  for (const d of dependencies as unknown[]) {
    if (
      !isRecord(d) ||
      typeof d.name !== 'string' ||
      (d.folder !== null && (typeof d.folder !== 'string' || !isRepositoryName(d.folder))) ||
      (d.head !== null && (typeof d.head !== 'string' || !isCommitId(d.head)))
    ) {
      return { status: 400, error: 'each dependency is {name, folder, head}: its folder a repository name or null, its head a commit of 40 hex digits or null' };
    }
    if (named.some((n) => n.name === d.name)) return { status: 400, error: `the dependency ${d.name} is named twice` };
    named.push({ name: d.name, folder: d.folder, head: d.head });
  }
  return { manifestTag: manifestTag as string | null, lockTag: lockTag as string | null, dependencies: named };
}

/**
 * Whether `body` states what `dependencies`, `manifestTag` and `lockTag` are now: the same two tags, and for each
 * dependency, and only those, its candidate's folder and `HEAD`.
 */
export function lockStillHolds(body: LockBody, now: { manifestTag: string | null; lockTag: string | null; dependencies: readonly Dependency[] }): boolean {
  if (body.manifestTag !== now.manifestTag || body.lockTag !== now.lockTag || body.dependencies.length !== now.dependencies.length) return false;
  return now.dependencies.every((d) => {
    const said = body.dependencies.find((b) => b.name === d.name);
    return said !== undefined && said.folder === (d.candidate?.folder ?? null) && said.head === (d.candidate?.head ?? null);
  });
}

/** The pins of the lines `rail8 lock` printed, `<name>@<version> <repository> <commit> <content>`; null when a line is none of them. */
export function lockedPinsOf(said: string): LockedPin[] | null {
  const pins: LockedPin[] = [];
  for (const line of said.split('\n')) {
    if (line.trim() === '') continue;
    const words = line.split(' ');
    const at = words[0]?.indexOf('@') ?? -1;
    if (words.length !== 4 || at <= 0) return null;
    pins.push({ name: words[0]!.slice(0, at), version: words[0]!.slice(at + 1), repository: words[1]!, commit: words[2]!, content: words[3]! });
  }
  return pins;
}
