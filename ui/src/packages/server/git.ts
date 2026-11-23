import { Refusal } from '../../../server/workingFolder.ts';
import type { Commit } from '../api.ts';
import { GIT_MISSING, gitTooOld, OLDEST_GIT, type StatusEntry } from '../rules.ts';
import { formatted } from './formatter.ts';
import type { Runner } from './runner.ts';

/** A git read that exited with an error: what git said, through the formatter. */
export class GitSaid extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GitSaid';
  }
}

/** The stdout of the read `args` in the clone at `clone`, as text; a {@link GitSaid} when git exits with an error. */
export async function read(git: Runner, clone: string, args: readonly string[]): Promise<string> {
  const ran = await git.run({ args, clone, mode: 'read' });
  if (ran.code !== 0) throw new GitSaid(formatted(ran.stderr.toString('utf8')) || `git ${args[0]} exited with ${String(ran.code)}`);
  return ran.stdout.toString('utf8');
}

/**
 * Whether the read `args` in the clone at `clone`, a read whose exit code is its answer, exits with 0, or with one of
 * `no`; a {@link GitSaid} for any other code.
 */
export async function holds(git: Runner, clone: string, args: readonly string[], no: readonly number[]): Promise<boolean> {
  const ran = await git.run({ args, clone, mode: 'read' });
  if (ran.code === 0) return true;
  if (ran.code !== null && no.includes(ran.code)) return false;
  throw new GitSaid(formatted(ran.stderr.toString('utf8')) || `git ${args[0]} exited with ${String(ran.code)}`);
}

/**
 * Why the development server's git cannot serve the git half, or null when it can: git is missing from its PATH, or
 * older than 2.30. A failure of the check itself says what failed.
 */
export async function checkGit(git: Runner): Promise<string | null> {
  let said: string;
  try {
    const ran = await git.run({ args: ['--version'], mode: 'read' });
    said = ran.stdout.toString('utf8').trim();
  } catch (error) {
    if (error instanceof Refusal && error.status === 503) return GIT_MISSING;
    return `git --version failed: ${error instanceof Error ? error.message : String(error)}`;
  }
  const version = /^git version (\d+)\.(\d+)\S*/.exec(said);
  if (version === null) return `git --version answered ${formatted(said)}, which names no version`;
  const [major, minor] = [Number(version[1]), Number(version[2])];
  if (major < OLDEST_GIT[0] || (major === OLDEST_GIT[0] && minor < OLDEST_GIT[1])) return gitTooOld(version[0].slice('git version '.length));
  return null;
}

/** What `git status --porcelain=v2 --branch -z` says of a clone. */
export interface Status {
  /** The commit `HEAD` names; null before the first commit. */
  oid: string | null;
  /** The branch; null for a detached `HEAD`. */
  branch: string | null;
  /** The upstream with ahead and behind; null when there is none, or its branch is gone. */
  upstream: { name: string; ahead: number; behind: number } | null;
  entries: StatusEntry[];
}

/** The part of `record` after its first `count` fields, each ended by one space. */
function after(record: string, count: number): string {
  let at = 0;
  for (let i = 0; i < count; i++) at = record.indexOf(' ', at) + 1;
  return record.slice(at);
}

/** The output of `git status --porcelain=v2 --branch -z`, read. Ignored paths, which it lists only when asked, are passed over. */
export function parseStatus(output: string): Status {
  const records = output.split('\0');
  const status: Status = { oid: null, branch: null, upstream: null, entries: [] };
  let upstream: string | null = null;
  let ab: { ahead: number; behind: number } | null = null;
  for (let i = 0; i < records.length; i++) {
    const record = records[i]!;
    const xy = record.slice(2, 4);
    if (record.startsWith('# branch.oid ')) {
      const oid = record.slice('# branch.oid '.length);
      status.oid = oid === '(initial)' ? null : oid;
    } else if (record.startsWith('# branch.head ')) {
      const head = record.slice('# branch.head '.length);
      status.branch = head === '(detached)' ? null : head;
    } else if (record.startsWith('# branch.upstream ')) {
      upstream = record.slice('# branch.upstream '.length);
    } else if (record.startsWith('# branch.ab ')) {
      const [ahead, behind] = record.slice('# branch.ab '.length).split(' ');
      ab = { ahead: Number(ahead?.slice(1)), behind: Number(behind?.slice(1)) };
    } else if (record.startsWith('1 ')) {
      status.entries.push({ kind: 'ordinary', xy, path: after(record, 8) });
    } else if (record.startsWith('2 ')) {
      status.entries.push({ kind: 'renamed', xy, path: after(record, 9), from: records[++i] ?? '' });
    } else if (record.startsWith('u ')) {
      status.entries.push({ kind: 'unmerged', xy, path: after(record, 10) });
    } else if (record.startsWith('? ')) {
      status.entries.push({ kind: 'untracked', xy: '??', path: record.slice(2) });
    }
  }
  if (upstream !== null && ab !== null) status.upstream = { name: upstream, ...ab };
  return status;
}

/** One path that `diff-tree -r -z` or `diff -z --name-status` lists: git's status letter, its path, and the path it was renamed or copied from. */
export interface Diffed {
  letter: string;
  path: string;
  from?: string;
  /** The ids of its older and newer blob, as `diff-tree` gives them; all zeros where a side has none. */
  oids?: [string, string];
}

/** The output of `git diff-tree -r -z`, read. */
export function parseDiffTree(output: string): Diffed[] {
  const records = output.split('\0');
  const diffed: Diffed[] = [];
  for (let i = 0; i < records.length; i++) {
    const record = records[i]!;
    if (!record.startsWith(':')) continue;
    const [, , older = '', newer = '', status = ''] = record.slice(1).split(' ');
    const letter = status.slice(0, 1);
    const first = records[++i] ?? '';
    if (letter === 'R' || letter === 'C') diffed.push({ letter, from: first, path: records[++i] ?? '', oids: [older, newer] });
    else diffed.push({ letter, path: first, oids: [older, newer] });
  }
  return diffed;
}

/** The output of `git diff -z --name-status`, read. */
export function parseNameStatus(output: string): Diffed[] {
  const records = output.split('\0');
  const diffed: Diffed[] = [];
  for (let i = 0; i + 1 < records.length; i++) {
    const letter = records[i]!.slice(0, 1);
    if (letter === '') continue;
    const first = records[++i] ?? '';
    if (letter === 'R' || letter === 'C') diffed.push({ letter, from: first, path: records[++i] ?? '' });
    else diffed.push({ letter, path: first });
  }
  return diffed;
}

/** One entry `git ls-tree -r -z --long` lists. */
export interface TreeEntry {
  mode: string;
  type: string;
  oid: string;
  /** The blob's size; null for anything that is no blob. */
  size: number | null;
  path: string;
}

/** The output of `git ls-tree -r -z --long`, read. */
export function parseTree(output: string): TreeEntry[] {
  return output
    .split('\0')
    .filter((record) => record !== '')
    .map((record) => {
      const tab = record.indexOf('\t');
      const [mode = '', type = '', oid = '', size = '-'] = record.slice(0, tab).split(/ +/);
      return { mode, type, oid, size: size === '-' ? null : Number(size), path: record.slice(tab + 1) };
    });
}

/** The format `log` is read with: id, parents, author, author date and subject, each ended by NUL, as `-z` ends each commit. */
export const LOG_FORMAT = '--format=%H%x00%P%x00%an%x00%aI%x00%s';

/** The output of `git log -z` with {@link LOG_FORMAT}, read. */
export function parseLog(output: string): Commit[] {
  const fields = output.split('\0');
  const commits: Commit[] = [];
  for (let i = 0; i + 4 < fields.length; i += 5) {
    const [id = '', parents = '', author = '', at = '', subject = ''] = fields.slice(i, i + 5);
    commits.push({ id, parents: parents === '' ? [] : parents.split(' '), subject, author, at });
  }
  return commits;
}

/** What `git cat-file --batch` gives of one object: its type and its bytes; null when the clone has no such object. */
export interface Blob {
  type: string;
  bytes: Buffer;
}

/**
 * The objects `names` of the clone at `clone`, each a name `cat-file` takes such as `<commit>:<path>`, read with
 * `git cat-file --batch`, or only their types and sizes with `--batch-check` (`bytes` is then empty, and `size` gives
 * the size). A name the clone lacks is null.
 */
export async function catFile(git: Runner, clone: string, names: readonly string[], check: boolean): Promise<(Blob & { size: number } | null)[]> {
  if (names.length === 0) return [];
  const ran = await git.run({ args: ['cat-file', check ? '--batch-check' : '--batch'], clone, mode: 'read', stdin: names.map((n) => `${n}\n`).join('') });
  if (ran.code !== 0) throw new GitSaid(formatted(ran.stderr.toString('utf8')) || `git cat-file exited with ${String(ran.code)}`);
  const out = ran.stdout;
  const found: (Blob & { size: number } | null)[] = [];
  let at = 0;
  for (let n = 0; n < names.length; n++) {
    const end = out.indexOf(0x0a, at);
    if (end < 0) throw new GitSaid('git cat-file answered fewer objects than it was asked for');
    const header = out.subarray(at, end).toString('utf8').split(' ');
    at = end + 1;
    if (header.length !== 3 || !/^\d+$/.test(header[2]!)) {
      found.push(null);
      continue;
    }
    const size = Number(header[2]);
    if (check) {
      found.push({ type: header[1]!, size, bytes: Buffer.alloc(0) });
    } else {
      found.push({ type: header[1]!, size, bytes: Buffer.from(out.subarray(at, at + size)) });
      at += size + 1;
    }
  }
  return found;
}
