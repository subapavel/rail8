import { parse } from 'yaml';
import type { PackageRow } from './api';

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
