import { parse, parseDocument, stringify } from 'yaml';
import type { RefusalValues } from '../refusal';
import type { Artifact, Busy, Change, ChangeStatus, CommitBody, DeployBody, DiscardBody, Dependency, FolderBody, Kind, LockBody, LockedPin, PackageRow, Pin, RemoteBody } from './api';

/** At most this many packages are listed, and at most this many folders that are no package. */
export const MAX_PACKAGES = 100;

/** The oldest git the git half runs: older ones lack what it relies on. */
export const OLDEST_GIT = [2, 30] as const;

/**
 * A refusal as the records below word it: its stable code, its English text and the values that text names. The
 * development server answers it as `{error, code, values}`, and the UI shows it in the user's language by its code. A
 * code is kebab-case and named by one entry of one record, but for a code of the UI's server that means the same, such
 * as `bad-request`; once shipped it is never renamed. A value is raw data, a name, an id, a count or a limit, never words.
 */
export interface Refused {
  code: string;
  error: string;
  values: RefusalValues;
}

/** A refusal of a record whose text names values: the refusal with the values given, and its code. */
export type Worded<V extends RefusalValues> = ((values: V) => Refused) & { readonly code: string };

/** A refusal whose text is the same every time; `values` are the limits it names, if any. */
const fixed = (code: string, error: string, values: RefusalValues = {}): Refused => ({ code, error, values });

/** A refusal whose text, made by `text`, names the values it is given. */
function worded<V extends RefusalValues>(code: string, text: (values: V) => string): Worded<V> {
  return Object.assign((values: V) => ({ code, error: text(values), values }), { code });
}

/**
 * A refusal whose text also carries what git, another program or the system said, which no fixed text can word: the
 * values are those its text names besides, never what was said. Such a code is left in English.
 */
function saying<V extends RefusalValues = Record<string, never>>(code: string, text: (said: string, values: V) => string): ((said: string, values?: V) => Refused) & { readonly code: string } {
  return Object.assign((said: string, values: V = {} as V) => ({ code, error: text(said, values), values }), { code });
}

/** A request of a shape no screen of the UI sends, refused with 400 and the code of the UI's server for it. */
const badRequest = (error: string): Refused & { status: 400 } => ({ status: 400, code: 'bad-request', error, values: {} });

/** Why a folder directly in the working folder is no package; a request for it as a package is refused with its code. */
export const REASONS = {
  name: fixed('not-package-name', "its name is no repository name: at most 100 letters, digits, '.', '_' and '-', starting with neither '.' nor '-'"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  link: fixed('not-package-link', 'a symbolic link: a package is a folder itself'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noClone: fixed('not-package-no-clone', 'it holds no clone of its own: it has no .git folder'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  worktree: fixed('not-package-worktree', 'its .git is a file: a worktree or a submodule is not supported'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  nested: fixed('not-package-nested', 'git takes it for a folder of another repository, not for a clone of its own'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noManifest: fixed('not-package-no-manifest', 'its clone has no package.yaml at its root'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
} as const;

/** The refusal (404) of the folder `repository` as a package, for `reason`, one of {@link REASONS}: the reason's code, and the folder's name. */
export function notPackage(repository: string, reason: Refused): Refused {
  return { code: reason.code, error: `${repository} is no package: ${reason.error}`, values: { repository } }; // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
}

/** Why the git half does not serve, as every route answers it with 503: git is missing, too old, or its check failed. */
export const GIT_REFUSALS = {
  /** git is not on the development server's PATH, which is checked when the server starts. */
  missing: fixed(
    'git-missing',
    'git is not installed or not on the PATH of the development server: the Packages screen needs git 2.30 or later; install it, then restart the development server', // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  /** The development server's git is older than 2.30. */
  tooOld: worded<{ version: string }>('git-too-old', ({ version }) => `git ${version} is older than 2.30, which the Packages screen needs: install a newer git, then restart the development server`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  /** `git --version` failed, as the error says. */
  versionFailed: saying('git-version-failed', (said) => `git --version failed: ${said}`),
  /** `git --version` answered no version, as it says. */
  noVersion: saying('git-version-unknown', (said) => `git --version answered ${said}, which names no version`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  /** git went missing from the PATH after the start. */
  notFound: fixed('git-not-on-path', 'git is not installed or not on the PATH of the development server'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
} as const;

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

/** At most this many changed paths of one package or of one comparison are listed, and of one package's artifacts at most this many files read for their kind and as many others. */
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

/** What keeps a busy clone busy, in words: `busy` is the operation git left open, or `conflict`. */
const busyWords = (busy: Exclude<Busy, null>) => (busy === 'conflict' ? 'a path is in conflict' : `a ${busy} is in progress`); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language

/** What the routes answer when a request names no package of the working folder, or no part of one, or no route. */
export const PACKAGE_REFUSALS = {
  noWorkspace: fixed('no-workspace', 'no working folder is set: start the UI with RAIL8_WORKSPACE'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  workspaceGone: worded<{ folder: string }>('working-folder-missing', ({ folder }) => `the working folder ${folder} does not exist`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  badRepository: worded<{ repository: string }>('bad-request', ({ repository }) => `${JSON.stringify(repository)} is no repository name`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noPackage: worded<{ repository: string }>('no-package', ({ repository }) => `no package ${repository} in the working folder`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noCommit: worded<{ commit: string }>('no-commit', ({ commit }) => `the clone holds no commit ${commit}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noFileOnDisk: worded<{ path: string }>('no-file-on-disk', ({ path }) => `no file ${path} on disk`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noFileInCommit: worded<{ path: string; commit: string }>('no-file-in-commit', ({ path, commit }) => `no file ${path} in the commit ${commit}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  leadsOut: worded<{ path: string }>('path-leads-out', ({ path }) => `${path} leads out of the package's folder or into its .git folder`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  overReadLimit: worded<{ path: string }>('over-read-limit', ({ path }) => `${path} is over 1 MiB, the read limit`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  outside: worded<{ path: string; repository: string }>('path-outside-package', ({ path, repository }) => `${path} lies outside the package ${repository}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  notEncoded: fixed('bad-path', 'the repository name is not well encoded'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noRoute: worded<{ path: string }>('no-route', ({ path }) => `the git half has no route ${path}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noPackageRoute: worded<{ route: string }>('no-route', ({ route }) => `the git half has no route ${route} of a package`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  onlyMethod: worded<{ method: string; path: string }>('method-not-allowed', ({ method, path }) => `${method} is the only way to call ${path}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  onlyGetAndPost: worded<{ path: string }>('method-not-allowed', ({ path }) => `GET and POST are the only ways to call ${path}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noQuery: worded<{ name: string }>('bad-request', ({ name }) => `the query names no ${name}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noRevision: worded<{ name: string; rev: string }>('bad-request', ({ name, rev }) => `${name} ${JSON.stringify(rev)} is no revision: work, or a commit of 40 hex digits`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noCommitId: worded<{ name: string; rev: string }>('bad-request', ({ name, rev }) => `${name} ${JSON.stringify(rev)} is no revision: a commit of 40 hex digits`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  badLimit: worded<{ limit: string; max: number }>('bad-request', ({ limit, max }) => `limit ${JSON.stringify(limit)} is no number from 1 to ${max}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
} as const;

/** What a run of git, or of another program, answers when the runner stops it or cannot run it. */
export const RUNNER_REFUSALS = {
  notStarted: saying<{ program: string }>('program-not-started', (said, { program }) => `${program} could not be started: ${said}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  timedOut: worded<{ program: string; seconds: number }>('program-timed-out', ({ program, seconds }) => `${program} took longer than ${seconds} s and was stopped`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  tooMuchOutput: worded<{ program: string; mib: number; stream: string }>('program-output-too-large', ({ program, mib, stream }) => `${program} wrote more than ${mib} MiB on its ${stream} and was stopped`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  closing: fixed('server-closing', 'the development server is closing'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  closed: fixed('server-closed', 'the development server closed while git ran'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  queueFull: worded<{ mode: string }>('git-queue-full', ({ mode }) => `too many git ${mode}s are waiting: try again`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  undoCalls: worded<{ max: number }>('undo-calls', ({ max }) => `an undo makes at most ${max} git calls`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
} as const;

/** What a commit answers when it is refused, in both halves. */
export const COMMIT_REFUSALS = {
  readOnly: fixed('commit-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was committed'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  busy: worded<{ busy: Exclude<Busy, null> }>('commit-clone-busy', ({ busy }) => `the clone is busy: ${busyWords(busy)}; finish it in a terminal, then commit`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  /** Another git holds the index: a commit, a fetch, a pull and a push answer it alike. */
  indexLocked: fixed('index-locked', 'the clone is busy: another git holds its index (.git/index.lock); try again once it ends'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  detached: fixed('commit-detached', 'HEAD is detached: commit on a branch, which a terminal can switch to'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noIdentity: fixed('commit-no-identity', 'git gives no author identity in this clone: set it in a terminal with git config user.name and git config user.email'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  moved: worded<{ branch: string; head: string }>('commit-stale-head', ({ branch, head }) => `the clone is now at ${branch} ${head}, not at the branch and HEAD you reviewed; nothing was committed`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  movedUnborn: worded<{ branch: string }>('commit-stale-unborn', ({ branch }) => `the clone is now at ${branch} (no commit yet), not at the branch and HEAD you reviewed; nothing was committed`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  gone: worded<{ path: string }>('commit-path-gone', ({ path }) => `${path} is no longer among the package's changes; nothing was committed`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  renameAlone: worded<{ path: string; from: string }>('commit-rename-alone', ({ path, from }) => `${path} is renamed from ${from}, which is not among the paths chosen with it; nothing was committed`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  renameOnDisk: worded<{ path: string; from: string }>('commit-rename-on-disk', ({ path, from }) => `${from}, which ${path} was renamed from, is on disk: choose it too; nothing was committed`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  changed: worded<{ path: string }>('commit-path-changed', ({ path }) => `${path} changed since you reviewed it; nothing was committed`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  staged: worded<{ path: string }>('commit-path-staged', ({ path }) => `${path} is staged in a terminal with other content: commit or unstage it there; nothing was committed`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  nothingChosen: fixed('commit-nothing-chosen', 'nothing to commit: no path is chosen'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  addFailed: fixed('add-failed', 'git add failed; nothing was committed'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  failed: fixed('commit-failed', 'git commit failed, as git and the hooks say; nothing was committed'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  failedHeadMoved: worded<{ head: string }>(
    'commit-failed-head-moved',
    ({ head }) => `git commit failed, as git and the hooks say; HEAD moved to ${head} meanwhile, so git may have committed: the package's history shows it`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  failedHeadUnread: fixed('commit-failed-head-unread', "git commit failed, as git and the hooks say; HEAD could not be read again, so git may have committed: the package's history shows it"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  /** A failure of git, of a hook or of the runner, with what the undo found: whether git may have committed, and the paths left staged. */
  failedNoted: saying('commit-failed-noted', (said) => said),
} as const;

/** What a discard answers when it is refused, in both halves; each refusal changed nothing. */
export const DISCARD_REFUSALS = {
  readOnly: fixed('discard-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was discarded'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  busy: worded<{ busy: Exclude<Busy, null> }>('discard-clone-busy', ({ busy }) => `the clone is busy: ${busyWords(busy)}; finish it in a terminal, then discard`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  moved: worded<{ head: string }>('discard-stale-head', ({ head }) => `HEAD is now ${head}, not the HEAD you saw; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  movedUnborn: fixed('discard-stale-unborn', 'HEAD is now (no commit yet), not the HEAD you saw; nothing was discarded'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  gone: worded<{ path: string }>('discard-path-gone', ({ path }) => `${path} is no longer among the package's changes; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  renameAlone: worded<{ path: string; from: string }>('discard-rename-alone', ({ path, from }) => `${path} is renamed from ${from}, which is not among the paths chosen with it; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  renameOnDisk: worded<{ path: string; from: string }>('discard-rename-on-disk', ({ path, from }) => `${from}, which ${path} was renamed from, is on disk: choose it too; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  restaged: worded<{ path: string }>('discard-path-restaged', ({ path }) => `${path} was staged or unstaged since you saw it; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  leadsOut: worded<{ path: string }>('discard-path-leads-out', ({ path }) => `${path} leads out of the package's folder, or into its .git folder; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  changed: worded<{ path: string }>('discard-path-changed', ({ path }) => `${path} changed since you saw it; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  staged: worded<{ path: string }>('discard-path-staged', ({ path }) => `${path} is staged in a terminal with other content: discard or unstage it there; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  intentToAdd: worded<{ path: string }>('discard-intent-to-add', ({ path }) => `git holds ${path} as intent to add (git add -N): discard it in a terminal; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  symbolicLink: worded<{ path: string }>('discard-symbolic-link', ({ path }) => `${path} is a symbolic link on disk: discard it in a terminal; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  submodule: worded<{ path: string }>('discard-submodule', ({ path }) => `${path} is a submodule: discard it in a terminal; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  sparse: worded<{ path: string }>('discard-sparse', ({ path }) => `${path} is outside the sparse checkout (skip-worktree): discard it in a terminal; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  letterCase: worded<{ path: string; other: string }>('discard-letter-case', ({ path, other }) => `${path} and ${other} are the same but for letter case: discard them in a terminal; nothing was discarded`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  fileAndFolder: worded<{ path: string; other: string }>(
    'discard-file-and-folder',
    ({ path, other }) => `${path} and ${other} are a file and a folder of the same name: discard them one at a time, or in a terminal; nothing was discarded`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  indexLocked: fixed('discard-index-locked', 'the clone is busy: another git holds its index (.git/index.lock); try again once it ends; nothing was discarded'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  updateIndexFailed: fixed('discard-update-index-failed', 'git update-index failed, as git says; nothing was discarded'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  unread: saying('discard-unread', (said) => `the discard ran, but what it changed could not be read: ${said}; the package's Changes show it`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  nothingChosen: fixed('discard-nothing-chosen', 'nothing to discard: no path is chosen'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
} as const;

/** What a deploy and its check answer when they are refused before the node answers, or the node's answer is no answer. */
export const DEPLOY_REFUSALS = {
  readOnly: fixed('deploy-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was deployed'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  checkLost: worded<{ host: string; minutes: number }>('check-no-answer', ({ host, minutes }) => `the node at ${host} gave no answer to the check within ${minutes} min; the check changes nothing`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  deployLost: worded<{ host: string; minutes: number }>(
    'deploy-no-answer',
    ({ host, minutes }) => `the node at ${host} gave no answer within ${minutes} min, so the outcome of the deploy is unknown: read what it runs before you try again`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  checkBroken: worded<{ host: string }>('check-answer-broken', ({ host }) => `the node at ${host} broke off its answer to the check`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  deployBroken: worded<{ host: string }>(
    'deploy-answer-broken',
    ({ host }) => `the node at ${host} broke off its answer to the deploy, so the outcome of the deploy is unknown: read what it runs before you try again`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  checkTooLarge: worded<{ host: string }>('check-answer-too-large', ({ host }) => `the node at ${host} answered the check with more than 8 MiB`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  deployTooLarge: worded<{ host: string }>(
    'deploy-answer-too-large',
    ({ host }) => `the node at ${host} answered the deploy with more than 8 MiB, so the outcome of the deploy is unknown: read what it runs before you try again`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  checkUnreadable: worded<{ host: string; status: number }>('check-answer-unreadable', ({ host, status }) => `the node at ${host} answered the check with ${status}, but with no deploy the UI can read`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  deployUnreadable: worded<{ host: string; status: number }>(
    'deploy-answer-unreadable',
    ({ host, status }) =>
      `the node at ${host} answered the deploy with ${status}, but with no deploy the UI can read, so the outcome of the deploy is unknown: read what it runs before you try again`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  checkTokenRefused: worded<{ host: string }>('check-token-refused', ({ host }) => `the node at ${host} refuses the UI server's token (401); the check changes nothing`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  deployTokenRefused: worded<{ host: string }>('deploy-token-refused', ({ host }) => `the node at ${host} refuses the UI server's token (401); nothing was deployed`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  /** No connection to the node: what the connection's failure said. */
  checkUnreachable: saying<{ host: string }>('check-node-unreachable', (said, { host }) => `the node at ${host} does not answer (is it running?): ${said}; the check changes nothing`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  deployUnreachable: saying<{ host: string }>('deploy-node-unreachable', (said, { host }) => `the node at ${host} does not answer (is it running?): ${said}; nothing was deployed`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  /** A refusal of the node that names no code of its own: its text, or its status. */
  nodeRefused: saying('node-refused', (said) => said),
} as const;

/** The English text of the node's 412 to a deploy, the pool's record changed since its check: the node's own text, `error`. */
export const staleDeploy = (error: string) => `the node's commit changed since the check: ${error}; nothing was deployed`; // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language

/**
 * `body` as a commit of the package `repository` takes it, or its refusal: 400 when it is malformed (a message that is
 * blank, over 8 KiB or holds a NUL; a branch that is no text; a head that is neither null nor 40 hex digits; more than
 * 1000 paths, a path twice, or a path that is malformed or has a `.git` segment), 404 for a path outside the package,
 * and 422 for no path at all, since there is nothing to commit.
 */
export function commitBodyOf(repository: string, body: unknown): CommitBody | (Refused & { status: 400 | 404 | 422 }) {
  if (!isRecord(body)) return badRequest('the request is no JSON object'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  const { message, branch, head, paths } = body;
  if (typeof message !== 'string' || message.trim() === '') return badRequest('the commit needs a message'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (new TextEncoder().encode(message).length > MAX_MESSAGE_BYTES) return badRequest('the message is over 8 KiB'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (message.includes('\0')) return badRequest('the message holds a NUL character'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (typeof branch !== 'string' || branch === '') return badRequest('the request names no branch'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (head !== null && (typeof head !== 'string' || !isCommitId(head))) return badRequest('head is neither null nor a commit of 40 hex digits'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (!Array.isArray(paths)) return badRequest('paths is no list'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (paths.length > MAX_CHOSEN) return badRequest(`a commit takes at most ${MAX_CHOSEN} paths`); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  const chosen: CommitBody['paths'] = [];
  for (const entry of paths as unknown[]) {
    if (!isRecord(entry) || typeof entry.path !== 'string' || (entry.tag !== null && typeof entry.tag !== 'string')) {
      return badRequest('each path is {path, tag}, its tag a text or null'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    }
    const found = inPackage(repository, entry.path);
    if ('error' in found) return found;
    if (chosen.some((c) => c.path === entry.path)) return badRequest(`${entry.path} is chosen twice`); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    chosen.push({ path: entry.path, tag: entry.tag });
  }
  if (chosen.length === 0) return { status: 422, ...COMMIT_REFUSALS.nothingChosen };
  return { message, branch, head, paths: chosen };
}

/**
 * `body` as a discard of the package `repository` takes it, or its refusal: 400 when it is malformed (a head that is
 * neither null nor 40 hex digits; an index other than `clean`, `staged` or null; more than 1000 paths, a path twice, or a
 * path that is malformed or has a `.git` segment), 404 for a path outside the package, and 422 for no path at all.
 */
export function discardBodyOf(repository: string, body: unknown): DiscardBody | (Refused & { status: 400 | 404 | 422 }) {
  if (!isRecord(body)) return badRequest('the request is no JSON object'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  const { head, paths } = body;
  if (head !== null && (typeof head !== 'string' || !isCommitId(head))) return badRequest('head is neither null nor a commit of 40 hex digits'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (!Array.isArray(paths)) return badRequest('paths is no list'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (paths.length > MAX_CHOSEN) return badRequest(`a discard takes at most ${MAX_CHOSEN} paths`); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  const chosen: DiscardBody['paths'] = [];
  for (const entry of paths as unknown[]) {
    if (
      !isRecord(entry) ||
      typeof entry.path !== 'string' ||
      (entry.tag !== null && typeof entry.tag !== 'string') ||
      (entry.index !== null && entry.index !== 'clean' && entry.index !== 'staged')
    ) {
      return badRequest('each path is {path, tag, index}, its tag a text or null, its index clean, staged or null'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    }
    const found = inPackage(repository, entry.path);
    if ('error' in found) return found;
    if (chosen.some((c) => c.path === entry.path)) return badRequest(`${entry.path} is chosen twice`); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    chosen.push({ path: entry.path, tag: entry.tag, index: entry.index });
  }
  if (chosen.length === 0) return { status: 422, ...DISCARD_REFUSALS.nothingChosen };
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
 * Why a commit or a discard of `chosen` is stale (412), as `compared`, the paths that differ from `HEAD` now, shows it,
 * in the words of `refusals`, the commit's or the discard's; null when it is not: a path no longer among the changes; a
 * renamed change named without the path it was renamed from; or that path named as not on disk while it is on disk.
 */
export function staleChoice(
  repository: string,
  chosen: readonly Chosen[],
  compared: readonly Compared[],
  refusals: Pick<typeof COMMIT_REFUSALS | typeof DISCARD_REFUSALS, 'gone' | 'renameAlone' | 'renameOnDisk'>,
): Refused | null {
  const changed = new Set(compared.flatMap((c) => [c.inside, ...(c.renamedFrom === undefined ? [] : [c.renamedFrom])]));
  for (const c of chosen) if (!changed.has(c.inside)) return refusals.gone({ path: c.path });
  // a renamed change is chosen with the path it was renamed from, as the author saw it: not on disk, unless the request
  // names that path with a tag of its own
  const renamedFrom = new Map(compared.flatMap((c) => (c.renamedFrom === undefined ? [] : [[c.inside, c.renamedFrom] as const])));
  for (const c of chosen) {
    const from = renamedFrom.get(c.inside);
    if (from === undefined) continue;
    const source = chosen.find((s) => s.inside === from);
    if (source === undefined) return refusals.renameAlone({ path: c.path, from: `${repository}/${from}` });
    if (source.tag === null && source.onDisk) return refusals.renameOnDisk({ path: c.path, from: `${repository}/${from}` });
  }
  return null;
}

/** A deploy and its check wait this long for the node's answer: the wait of `rail8 deploy`. */
export const DEPLOY_MS = 5 * 60_000;

/**
 * `body` as a deploy or its check takes it, or its refusal (400): the commit is 40 hex digits; a deploy names the state
 * it expects, a commit of 40 hex digits or `none`, and never `any`, the blind overwrite; a check names none.
 */
export function deployBodyOf(body: unknown): DeployBody | (Refused & { status: 400 }) {
  if (!isRecord(body)) return badRequest('the request is no JSON object'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  const { commit, check, expect } = body;
  if (typeof commit !== 'string' || !isCommitId(commit)) return badRequest('commit is no commit of 40 hex digits'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (typeof check !== 'boolean') return badRequest('check is neither true nor false'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (check) return expect === undefined ? { commit, check } : badRequest('a check names no expect'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (typeof expect !== 'string' || (expect !== 'none' && !isCommitId(expect))) {
    return badRequest('a deploy names the state it expects to replace, as its check answered it: a commit of 40 hex digits, or none'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  }
  return { commit, check, expect };
}

/**
 * `body` as a pull or a push takes it, or its refusal (400): the branch is text; `head` is 40 hex digits; `upstream` is
 * the upstream's name as the package's state gave it, such as `origin/main`, or null when it gave none.
 */
export function remoteBodyOf(body: unknown): RemoteBody | (Refused & { status: 400 }) {
  if (!isRecord(body)) return badRequest('the request is no JSON object'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  const { branch, head, upstream } = body;
  if (typeof branch !== 'string' || branch === '') return badRequest('the request names no branch'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (typeof head !== 'string' || !isCommitId(head)) return badRequest('head is no commit of 40 hex digits'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (upstream !== null && (typeof upstream !== 'string' || upstream === '')) return badRequest('upstream is neither null nor a name'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  return { branch, head, upstream };
}

/** What a fetch, a pull and a push answer when they are refused, in both halves. */
export const REMOTE_REFUSALS = {
  fetchReadOnly: fixed('fetch-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was fetched'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  pullReadOnly: fixed('pull-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was pulled'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  pushReadOnly: fixed('push-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was pushed'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  busy: worded<{ busy: Exclude<Busy, null> }>('remote-clone-busy', ({ busy }) => `the clone is busy: ${busyWords(busy)}; finish it in a terminal first`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  detached: fixed('remote-detached', 'HEAD is detached: fetch, pull and push work on a branch, which a terminal can switch to'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  stale: fixed('remote-stale', 'the clone is no longer at the branch, HEAD and upstream you saw; nothing was done'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noUpstream: fixed('no-upstream', 'the branch has no upstream: push it once in a terminal with git push -u <remote> <branch>, then fetch, pull and push here'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  diverged: fixed('pull-diverged', 'the branch and its upstream have diverged: merge or rebase in a terminal; nothing was pulled'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  overwrites: fixed(
    'pull-overwrites',
    'git would not fast-forward, as git says, as when it would overwrite a file on disk: commit, move or remove that file, then pull again; nothing was pulled', // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  rejected: fixed('push-rejected', 'the remote rejected the push, as git says; nothing was pushed'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  fetchFailed: fixed(
    'fetch-failed',
    "git could not fetch, as git says: if git could not sign in or does not know the host's key, run git fetch in a terminal, where it can ask, then try again", // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  pushFailed: fixed(
    'push-failed',
    "git could not push, as git says: if git could not sign in or does not know the host's key, run git push in a terminal, where it can ask, then try again", // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
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
export function inPackage(repository: string, path: string): { inside: string } | (Refused & { status: 400 | 404 }) {
  const segments = path.split('/');
  // eslint-disable-next-line no-control-regex
  if (segments.some((s) => s === '' || s === '.' || s === '..' || /[\\\u0000-\u001f\u007f]/.test(s))) return badRequest(`the path ${JSON.stringify(path)} is malformed`); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (segments.some((s) => s.toLowerCase() === '.git')) return badRequest('a path with a .git segment names git’s own files, never a file of the package'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (segments[0] !== repository || segments.length < 2) return { status: 404, ...PACKAGE_REFUSALS.outside({ path, repository }) };
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

/** The path within the package of `named`, a path from the folder `folder` within it (`''` for its root); null when it leaves the package. */
function fromFolder(folder: string, named: string): string | null {
  const segments = folder === '' ? [] : folder.split('/');
  for (const segment of named.split('/')) {
    if (segment === '..') {
      if (segments.pop() === undefined) return null;
    } else if (segment !== '.') segments.push(segment);
  }
  return segments.join('/');
}

/**
 * The files of its package that the file at `inside` holding `text` may name: its texts that look like a file's path,
 * from the package's root, or from the file's own folder for a mapping, whose bundles name their files that way.
 */
function namedPaths(inside: string, text: string): string[] {
  const name = inside.slice(inside.lastIndexOf('/') + 1);
  const folder = name.endsWith('.mapping.json') ? inside.slice(0, Math.max(inside.lastIndexOf('/'), 0)) : '';
  const named: string[] = [];
  const visit = (value: unknown) => {
    if (typeof value === 'string') {
      if (!/^[^\s/:]+(?:\/[^\s/:]+)*\.[A-Za-z0-9]+$/.test(value)) return;
      const path = fromFolder(folder, value);
      if (path !== null && walked(path)) named.push(path);
    } else if (Array.isArray(value)) value.forEach(visit);
    else if (typeof value === 'object' && value !== null) Object.values(value).forEach(visit);
  };
  visit(valueOf(name, text));
  return named;
}

/**
 * The artifacts of the package `repository`: each of `files`, the files its walk found with their paths within the
 * package and their texts (null when not read), sorted by path. A file of no kind of its own carries the artifacts that
 * name it, when any does. Each artifact's change is `changes`' status of it.
 */
export function artifactsOf(repository: string, files: readonly { inside: string; text: string | null }[], changes: ReadonlyMap<string, ChangeStatus>): Artifact[] {
  const kinds = files.map(({ inside, text }) => ({ inside, text, ...kindOf(`${repository}/${inside}`, text) }));
  const named = new Map<string, Set<string>>();
  for (const { inside, text, kind } of kinds) {
    if (kind === 'file' || text === null) continue;
    for (const other of namedPaths(inside, text)) {
      if (other === inside) continue;
      if (!named.has(other)) named.set(other, new Set());
      named.get(other)!.add(`${repository}/${inside}`);
    }
  }
  return kinds
    .map(({ inside, text, kind, id }): Artifact => {
      const path = `${repository}/${inside}`;
      const usedBy = kind === 'file' ? named.get(inside) : undefined;
      const change = changes.get(path);
      return {
        path,
        kind,
        ...(id === undefined ? {} : { id }),
        ...(kind === 'folder' && text !== null ? folderFieldsOf(text) : {}),
        ...(usedBy === undefined ? {} : { usedBy: [...usedBy].sort() }),
        ...(change === undefined ? {} : { change }),
      };
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
  notText: fixed('bad-request', 'the request is no {address} of text'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  empty: fixed('address-empty', "paste the address of a repository, as its git host's Clone button gives it"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  space: fixed('address-space', 'paste only the address: it holds white space'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  long: fixed('address-too-long', `the address is over ${MAX_ADDRESS} characters: paste only the address`, { max: MAX_ADDRESS }), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  character: fixed('address-character', "the address holds a character outside printable ASCII: paste it as its git host's Clone button gives it"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  option: fixed('address-option', "an address never starts with '-': paste the address of the repository"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  transport: fixed('address-transport', "an address with '::' names a transport of its own, which is not taken: use the repository's https or ssh address"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  scheme: fixed('address-scheme', "only https:// and ssh:// addresses are taken: use the repository's https or ssh address"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  local: fixed('address-local', "a repository on this machine is not taken: clone it in a terminal, or use its git host's address"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  httpsUser: fixed('address-https-user', "remove the part before '@': your credential helper chooses the account"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  password: fixed('address-password', 'the address holds a password: remove it; your credential helper or your ssh key signs in'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  user: fixed('address-user', "the user name before '@' is malformed: letters, digits, '.', '_' and '-', starting with a letter, a digit or '_'"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  host: fixed('address-host', "the host is malformed: labels of letters, digits, '-' and '_' joined by '.'; an IPv6 address is not taken, use the host's name"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  driveLetter: fixed('address-drive-letter', "a host of one letter is a drive letter to git on Windows: use the host's full name, or an ssh:// address"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  port: fixed('address-port', 'the port is no number from 1 to 65535'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  path: fixed(
    'address-path',
    "the path of the address is malformed: segments of letters, digits, '.', '_', '~', '+' and '-' joined by '/', none of them '.' or '..' or starting with '-'", // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  noRepository: fixed('address-no-repository', 'the address names no repository: paste the address its git host gives'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  folderCharacter: fixed(
    'address-folder-character',
    "the repository's name holds a character a package's folder cannot have, such as a space: a package's folder takes letters, digits, '.', '_' and '-'", // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  name: fixed('address-folder-name', `the repository's name is no name of a package's folder: ${REASONS.name.error.slice('its name is no repository name: '.length)}`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  trailingDot: fixed('address-trailing-dot', "the repository's name ends with '.', which a folder on Windows cannot keep"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  device: fixed('address-device', "the repository's name is a device name on Windows, such as con or nul, which no folder can have"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
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

const refused = (refusal: Refused): Refused & { status: 400 } => ({ status: 400, ...refusal });

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
export function cloneSourceOf(text: unknown): { address: string; repository: string } | (Refused & { status: 400 }) {
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
  another: fixed('clone-under-way', 'another package is being got: wait for it to end'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  exists: fixed(
    'clone-exists',
    "the working folder holds an entry of the repository's name already: nothing was written; rename or remove it, or get the package in another working folder", // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  readOnly: fixed('clone-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was got'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  gitFailed: fixed(
    'clone-failed',
    'git could not clone the repository, and nothing was written: if git could not sign in or does not know the host’s key, run git ls-remote with this address in a terminal, where it can ask, then try again', // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  tooLarge: fixed(
    'clone-too-large',
    `the repository is over ${CLONE_MAX_BYTES / (1024 * 1024 * 1024)} GiB or ${CLONE_MAX_ENTRIES.toLocaleString('en-US')} files and folders on disk, so nothing was written: clone it in a terminal`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    { gib: CLONE_MAX_BYTES / (1024 * 1024 * 1024), entries: CLONE_MAX_ENTRIES },
  ),
  cleanupFailed: saying<{ folder: string }>('clone-cleanup-failed', (said, { folder }) => `the clone failed, and the hidden folder ${folder} it was made in could not be removed: ${said}; delete it`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
} as const;

/** What New package answers when it is refused, in both halves. */
export const NEW_PACKAGE_REFUSALS = {
  body: fixed('bad-request', 'the request is no {repository, name} of two texts'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  repository: fixed('new-package-repository', "the repository name must be one segment of at most 100 letters, digits, '.', '_' and '-', starting with neither '.' nor '-'"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  name: fixed('new-package-name', "the package name must be at most 64 lowercase letters, digits and '-', starting with a letter"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  exists: fixed(
    'new-package-exists',
    "the working folder holds an entry of the repository's name already: nothing was written; choose another name, or rename or remove that entry", // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  readOnly: fixed('new-package-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was made'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noIdentity: fixed('new-package-no-identity', 'git gives no author identity: set it in a terminal with git config user.name and git config user.email; nothing was made'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  gitFailed: fixed('new-package-failed', 'git could not make the first commit, as git and the hooks say; nothing was made'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  cleanupFailed: saying<{ folder: string }>(
    'new-package-cleanup-failed',
    (said, { folder }) => `the new package failed, and the hidden folder ${folder} it was made in could not be removed: ${said}; delete it`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
} as const;

/** Whether `name` is a package name, as `package.yaml`'s schema takes it: lowercase letters, digits and `-`, starting with a letter, at most 64 characters. */
export function isPackageName(name: string): boolean {
  return /^[a-z][a-z0-9-]{0,63}$/.test(name);
}

/**
 * `body` as New package takes it, or its refusal: 400 when it is no `{repository, name}` of two texts, or the
 * repository name is no name of a package's folder; 422 when `package.yaml`'s schema refuses the package name.
 */
export function newPackageBodyOf(body: unknown): { repository: string; name: string } | (Refused & { status: 400 | 422 }) {
  if (!isRecord(body) || typeof body.repository !== 'string' || typeof body.name !== 'string') return { status: 400, ...NEW_PACKAGE_REFUSALS.body };
  if (!isRepositoryName(body.repository)) return { status: 400, ...NEW_PACKAGE_REFUSALS.repository };
  if (!isPackageName(body.name)) return { status: 422, ...NEW_PACKAGE_REFUSALS.name };
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
  readOnly: fixed('lock-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was locked'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  stale: fixed('lock-stale', 'package.yaml, package.lock or a dependency’s folder is no longer what you saw: look at the dependencies again; nothing was locked'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  /** `folders` are the folders' names, joined by `, `. */
  several: worded<{ dependency: string; folders: string }>(
    'lock-several-candidates',
    ({ dependency, folders }) => `several folders of the working folder hold ${dependency}: ${folders}; rail8 lock could pin any of them, so remove or rename all but one; nothing was locked`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  problems: fixed('lock-problems', 'rail8 lock refused the lock, for the problems it names; nothing was written'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  /** `build` is the command that builds the command line, {@link BUILD_CLI}. */
  missing: worded<{ cli: string; build: string }>(
    'cli-missing',
    ({ cli, build }) => `the Rail8 command line ${cli} is missing or cannot run: build it with ${build} from the root of the Rail8 checkout (it needs JDK 25), or set RAIL8_CLI to it, then lock again`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  failed: worded<{ exitCode: number }>('lock-failed', ({ exitCode }) => `rail8 lock ended with exit code ${exitCode}, as it says; nothing may have been written`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  killed: fixed('lock-killed', 'rail8 lock ended with a signal, as it says; nothing may have been written'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  noPins: fixed('lock-no-pins', 'rail8 lock printed lines that are no pins: the command line may be another version than this UI'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
} as const;

/** What a write of a folder's `folder.yaml` is refused for. */
export const FOLDER_REFUSALS = {
  readOnly: fixed('folder-read-only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was written'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  root: fixed('folder-root', "the package's own folder holds package.yaml, not folder.yaml: choose a folder inside it"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  name: worded<{ name: string }>(
    'folder-name',
    ({ name }) => `${name} is no name of a folder: it starts with a small letter and holds small letters, digits and '-', at most 64 characters`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  deep: worded<{ max: number }>('folder-too-deep', ({ max }) => `a folder lies at most ${max} folders deep in its package`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  namespaceLength: worded<{ max: number }>('folder-namespace-length', ({ max }) => `a namespace holds 1 to ${max} characters`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  namespaceTaken: worded<{ namespace: string; folder: string }>(
    'folder-namespace-taken',
    ({ namespace, folder }) => `the namespace ${namespace} is the namespace of the folder ${folder} already: one namespace belongs to one folder`, // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  ),
  inTheWay: worded<{ path: string }>('folder-in-the-way', ({ path }) => `${path} is a file or a link, not a folder; nothing was written`), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  exists: fixed('folder-exists', 'the folder has a folder.yaml already: open the folder again to change it; nothing was written'), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  stale: fixed('folder-stale', "the folder's folder.yaml is no longer what you saw: open the folder again; nothing was written"), // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
} as const;

/**
 * The code of every entry of the records of the packages, in the order of the records: a code of the UI's server that an
 * entry shares, such as `bad-request`, once for each entry that gives it.
 */
export const PACKAGE_REFUSAL_CODES: readonly string[] = [
  REASONS,
  GIT_REFUSALS,
  PACKAGE_REFUSALS,
  RUNNER_REFUSALS,
  COMMIT_REFUSALS,
  DISCARD_REFUSALS,
  DEPLOY_REFUSALS,
  REMOTE_REFUSALS,
  ADDRESS_REFUSALS,
  CLONE_REFUSALS,
  NEW_PACKAGE_REFUSALS,
  LOCK_REFUSALS,
  FOLDER_REFUSALS,
].flatMap((record): string[] => Object.values(record).map((entry: { code: string }) => entry.code));

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
export function lockBodyOf(body: unknown): LockBody | (Refused & { status: 400 }) {
  if (!isRecord(body)) return badRequest('the request is no JSON object'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  const { manifestTag, lockTag, dependencies } = body;
  const tag = (value: unknown) => value === null || typeof value === 'string';
  if (!tag(manifestTag) || !tag(lockTag)) return badRequest('manifestTag and lockTag are each a tag or null'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (!Array.isArray(dependencies)) return badRequest('dependencies is no list'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  if (dependencies.length > MAX_LISTED) return badRequest(`a lock names at most ${MAX_LISTED} dependencies`); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  const named: LockBody['dependencies'] = [];
  for (const d of dependencies as unknown[]) {
    if (
      !isRecord(d) ||
      typeof d.name !== 'string' ||
      (d.folder !== null && (typeof d.folder !== 'string' || !isRepositoryName(d.folder))) ||
      (d.head !== null && (typeof d.head !== 'string' || !isCommitId(d.head)))
    ) {
      return badRequest('each dependency is {name, folder, head}: its folder a repository name or null, its head a commit of 40 hex digits or null'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    }
    if (named.some((n) => n.name === d.name)) return badRequest(`the dependency ${d.name} is named twice`); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
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

/** A folder of a package lies at most this many folders deep, and its name holds at most this many characters. */
export const MAX_FOLDER_DEPTH = 4;
export const MAX_FOLDER_NAME = 64;
/** A folder's namespace holds at most this many characters. */
export const MAX_NAMESPACE = 1024;

const FOLDER_NAME = /^[a-z][a-z0-9-]*$/;

/**
 * Why the folder at `inside`, a path within its package, cannot hold a `folder.yaml`: it is the package's own folder, a
 * name is no name of a folder (a small letter, then small letters, digits and `-`, at most 64 characters), or it lies
 * deeper than four folders; null when it can.
 */
export function folderPathRefused(inside: string): Refused | null {
  if (inside === '') return FOLDER_REFUSALS.root;
  const names = inside.split('/');
  const wrong = names.find((name) => !FOLDER_NAME.test(name) || name.length > MAX_FOLDER_NAME);
  if (wrong !== undefined) return FOLDER_REFUSALS.name({ name: wrong });
  if (names.length > MAX_FOLDER_DEPTH) return FOLDER_REFUSALS.deep({ max: MAX_FOLDER_DEPTH });
  return null;
}

/** Why `namespace` cannot be a folder's: it is empty or longer than 1024 characters; null when it can. */
export function namespaceRefused(namespace: string): Refused | null {
  const length = [...namespace].length;
  return length < 1 || length > MAX_NAMESPACE ? FOLDER_REFUSALS.namespaceLength({ max: MAX_NAMESPACE }) : null;
}

/**
 * Why the folder at `inside` cannot take `namespace`: another folder of `folders`, each a path within the package
 * with its namespace, has it already, compared byte for byte; null when none has it.
 */
export function namespaceTaken(namespace: string, inside: string, folders: readonly { inside: string; namespace?: string }[]): Refused | null {
  const other = folders.find((f) => f.inside !== inside && f.namespace === namespace);
  return other === undefined ? null : FOLDER_REFUSALS.namespaceTaken({ namespace, folder: other.inside });
}

/** The namespace and the description a `folder.yaml` of `text` gives, each when it is text; none when it cannot be read. */
export function folderFieldsOf(text: string): { namespace?: string; description?: string } {
  let doc: unknown;
  try {
    doc = parse(text);
  } catch {
    return {};
  }
  if (!isRecord(doc)) return {};
  return {
    ...(typeof doc.namespace === 'string' ? { namespace: doc.namespace } : {}),
    ...(typeof doc.description === 'string' ? { description: doc.description } : {}),
  };
}

/**
 * The text of a folder's `folder.yaml` with `namespace` and `description`, each left out when null: a new file when
 * `before` is null, else `before` with those two fields set or removed, its comments and other keys kept.
 */
export function folderText(before: string | null, namespace: string | null, description: string | null): string {
  if (before === null) {
    return stringify({ schemaVersion: 1, kind: 'folder', ...(namespace === null ? {} : { namespace }), ...(description === null ? {} : { description }) });
  }
  const doc = parseDocument(before);
  for (const [key, value] of [['namespace', namespace], ['description', description]] as const) {
    if (value === null) doc.delete(key);
    else doc.set(key, value);
  }
  return doc.toString();
}

/**
 * `body` as a write of a folder's `folder.yaml` in the package `repository` takes it, with the folder's path within the
 * package; refused with 400 when it is no `{path, tag, namespace, description}` (the path relative to the working
 * folder, each of the others text or null) or the path is malformed, 404 when the path lies outside the package, and
 * 422 when the folder cannot hold a `folder.yaml` or the namespace is no namespace.
 */
export function folderBodyOf(repository: string, body: unknown): (FolderBody & { inside: string }) | (Refused & { status: 400 | 404 | 422 }) {
  if (!isRecord(body)) return badRequest('the request is no JSON object'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  const { path, tag, namespace, description } = body;
  const text = (value: unknown) => value === null || typeof value === 'string';
  if (typeof path !== 'string' || !text(tag) || !text(namespace) || !text(description)) {
    return badRequest('the request is {path, tag, namespace, description}: the path is text, each of the others text or null'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
  }
  const found = path === repository ? { inside: '' } : inPackage(repository, path);
  if ('error' in found) return found;
  const refused = folderPathRefused(found.inside) ?? (namespace === null ? null : namespaceRefused(namespace as string));
  if (refused !== null) return { status: 422, ...refused };
  return { path, tag: tag as string | null, namespace: namespace as string | null, description: description as string | null, inside: found.inside };
}
