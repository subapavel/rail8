import { Refusal } from '../../../server/workingFolder.ts';
import { GIT_MISSING, gitTooOld, OLDEST_GIT } from '../rules.ts';
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

/** One path `git status --porcelain=v2` lists: ordinary, renamed or copied (with the path it came `from`), unmerged, or untracked. */
export interface StatusEntry {
  kind: 'ordinary' | 'renamed' | 'unmerged' | 'untracked';
  /** The index's and the work tree's status letters, such as `.M`; `??` for an untracked path. */
  xy: string;
  path: string;
  from?: string;
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
