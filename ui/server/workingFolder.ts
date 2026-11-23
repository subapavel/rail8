import { createHash, randomUUID } from 'node:crypto';
import { link, lstat, readdir, readFile, realpath, rename, rm, stat, writeFile } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { basename, dirname, join, relative, sep } from 'node:path';

/** At most this many files of the kind a walk looks for are looked at, this deep, and none longer than this is read. */
export const MAX_FILES = 1000;
const MAX_DEPTH = 16;
const READ_BATCH_SIZE = 8;
export const MAX_FILE_BYTES = 1024 * 1024;
const SKIPPED = new Set(['node_modules', 'target']);

/** A request the working folder's routes refuse: the HTTP status and the error text the answer carries. */
export class Refusal extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** At most this many writes of the working folder wait, besides the one that holds its lock; one more is refused with 503. */
export const MAX_WAITING_WRITES = 8;

/**
 * The working folder's lock, as an admitted write holds it: it runs `work` once every work given to it before has
 * settled, first in, first out, and answers what `work` answers. It is not reentrant: a work that asks for the lock
 * again never ends. It has no deadline of its own.
 */
export type WorkingFolderLock = <T>(work: () => Promise<T>) => Promise<T>;

/**
 * The writes of the working folder, which the editors' saves and the git writes share. It admits `write`, runs it with
 * the working folder's lock and answers what `write` answers, so the works the writes give the lock run one at a time.
 * A write counts from the moment it is admitted until it ends, whatever it waits for meanwhile: the write before it in
 * its own module, the node, or the lock. It answers undefined, and runs nothing, when one write and
 * {@link MAX_WAITING_WRITES} more are under way already.
 *
 * Each half of the development server holds its own copy of this file, so an error class of one copy is not the class
 * another copy catches. The writes are therefore only a function over promises, and make no error themselves:
 * {@link admitted}, in the copy of the half that asks, turns a refusal into that half's own.
 */
export type WorkingFolderWrites = <T>(write: (lock: WorkingFolderLock) => Promise<T>) => Promise<T> | undefined;

/** New writes of the working folder, with their own lock. The development server makes them once, for every half. */
export function workingFolderWrites(): WorkingFolderWrites {
  let last: Promise<unknown> = Promise.resolve();
  const lock: WorkingFolderLock = (work) => {
    const done = last.then(() => work());
    last = done.catch(() => undefined);
    return done;
  };
  let underWay = 0;
  return (write) => {
    if (underWay > MAX_WAITING_WRITES) return undefined;
    underWay += 1;
    return Promise.resolve()
      .then(() => write(lock))
      .finally(() => {
        underWay -= 1;
      });
  };
}

/**
 * Runs `write` as one of the working folder's `writes`, with the lock they hand it, and answers what it answers.
 * Refused with 503, and `write` not run, when one write and {@link MAX_WAITING_WRITES} more are under way already.
 */
export async function admitted<T>(writes: WorkingFolderWrites, write: (lock: WorkingFolderLock) => Promise<T>): Promise<T> {
  const done = writes(write);
  if (done === undefined) throw new Refusal(503, `the working folder is busy: ${MAX_WAITING_WRITES} writes wait already; nothing was written, try again`);
  return done;
}

/** One file the walk found: its path relative to the folder, with `/`, and its text. */
export interface FoundFile {
  path: string;
  text: string;
}

/**
 * The files under `root` whose name `takes`, in path order, read as text; the paths of those over
 * {@link MAX_FILE_BYTES}, which are not read, in path order too; and whether the walk stopped at {@link MAX_FILES} of
 * them. Hidden folders and files, `node_modules`, `target`, symbolic links and folders more than 16 deep are passed
 * over.
 */
export async function walk(root: string, takes: (name: string) => boolean, signal?: AbortSignal): Promise<{ files: FoundFile[]; large: string[]; truncated: boolean }> {
  const files: FoundFile[] = [];
  const large: string[] = [];
  const paths: string[] = [];
  let truncated = false;
  const ignoreVanished = (error: NodeJS.ErrnoException) => {
    if (error.code !== 'ENOENT' && error.code !== 'ENOTDIR') throw error;
  };
  const visit = async (dir: string, depth: number) => {
    signal?.throwIfAborted();
    const entries = await readdir(dir, { withFileTypes: true });
    entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    for (const entry of entries) {
      signal?.throwIfAborted();
      if (truncated) break;
      if (entry.name.startsWith('.')) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!SKIPPED.has(entry.name) && depth < MAX_DEPTH) await visit(full, depth + 1).catch(ignoreVanished);
      } else if (entry.isFile() && takes(entry.name)) {
        if (paths.length === MAX_FILES) {
          truncated = true;
          break;
        }
        paths.push(full);
      }
    }
  };
  await visit(root, 0);
  const read = async (full: string) => {
    const info = await lstat(full);
    if (!info.isFile()) return;
    const path = relative(root, full).split(sep).join('/');
    if (info.size > MAX_FILE_BYTES) large.push(path);
    else files.push({ path, text: await readFile(full, { encoding: 'utf8', signal }) });
  };
  for (let i = 0; i < paths.length; i += READ_BATCH_SIZE) {
    signal?.throwIfAborted();
    await Promise.all(paths.slice(i, i + READ_BATCH_SIZE).map((path) => read(path).catch(ignoreVanished)));
  }
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  large.sort();
  return { files, large, truncated };
}

/** The ETag of a file's bytes. */
export function etagOf(bytes: Buffer | string): string {
  return `"sha256-${createHash('sha256').update(bytes).digest('hex')}"`;
}

/**
 * The real path of the file at `path` (relative, with `/`, each segment percent-encoded) under the real folder `root`
 * whose name `takes`. Refused with 400 when the path is malformed or names no such file, 404 when there is none, 403
 * when it leads out of the folder, and 413 when it is over {@link MAX_FILE_BYTES}.
 */
export async function locate(root: string, path: string, takes: (name: string) => boolean, what: string): Promise<string> {
  const segments = segmentsOf(path, takes, what);
  let real: string;
  try {
    real = await realpath(join(root, ...segments));
  } catch {
    throw new Refusal(404, `no file ${segments.join('/')} in the working folder`);
  }
  if (!real.startsWith(root + sep)) throw new Refusal(403, 'the path leaves the working folder');
  const info = await stat(real);
  if (!info.isFile()) throw new Refusal(404, `no file ${segments.join('/')} in the working folder`);
  if (info.size > MAX_FILE_BYTES) throw new Refusal(413, 'the file is over 1 MiB');
  return real;
}

/**
 * Creates the file at `path` (relative, with `/`, each segment percent-encoded) under the real folder `root`, whose name
 * `takes`, holding `text`, and answers its real path. Under the working folder's lock, it looks for its folder, then
 * writes the file whole to a temporary file beside it and links it to its name, so it never replaces a file. Refused
 * with 400 when the path is malformed, names no such file or a place the walk passes over (a hidden or skipped folder
 * or file, or one too deep), 404 when its folder does not exist, 403 when the folder leads out of the working folder,
 * and 412 when a file, or a link, of that name exists.
 */
export async function createFile(lock: WorkingFolderLock, root: string, path: string, takes: (name: string) => boolean, what: string, text: string): Promise<string> {
  const segments = segmentsOf(path, takes, what);
  const folders = segments.slice(0, -1);
  if (folders.length > MAX_DEPTH || segments.some((s) => s.startsWith('.') || SKIPPED.has(s))) throw new Refusal(400, `the path names no ${what} of the working folder`);
  return lock(async () => {
    let folder: string;
    try {
      folder = await realpath(join(root, ...folders));
    } catch {
      throw new Refusal(404, `no folder ${folders.join('/')} in the working folder`);
    }
    if (folder !== root && !folder.startsWith(root + sep)) throw new Refusal(403, 'the path leaves the working folder');
    if (!(await stat(folder)).isDirectory()) throw new Refusal(404, `no folder ${folders.join('/')} in the working folder`);
    const file = join(folder, segments.at(-1)!);
    if (!(await linkNew(file, text))) throw new Refusal(412, 'the file exists already; nothing was written');
    return file;
  });
}

/**
 * Writes `content` to the new file `file` under the working folder's lock, whole to a temporary file beside it that is
 * then linked to its name, so it never replaces a file. False, with nothing written, when a file, or a link, of that
 * name exists.
 */
export function writeNew(lock: WorkingFolderLock, file: string, content: string | Uint8Array): Promise<boolean> {
  return lock(() => linkNew(file, content));
}

/** {@link writeNew}, for a caller that holds the lock. */
async function linkNew(file: string, content: string | Uint8Array): Promise<boolean> {
  if (await lstat(file).then(() => true, () => false)) return false;
  const temporary = join(dirname(file), `.${basename(file)}.${randomUUID()}.tmp`);
  try {
    await writeFile(temporary, content, { flag: 'wx' });
    return await link(temporary, file).then(
      () => true,
      (error: unknown) => {
        if ((error as NodeJS.ErrnoException).code === 'EEXIST') return false;
        throw error;
      },
    );
  } finally {
    await rm(temporary, { force: true });
  }
}

/** The decoded segments of `path`, refused with 400 when it is malformed or its last segment is no name `takes`. */
function segmentsOf(path: string, takes: (name: string) => boolean, what: string): string[] {
  const segments = path.split('/').map((s) => {
    try {
      return decodeURIComponent(s);
    } catch {
      throw new Refusal(400, 'the path is not well encoded');
    }
  });
  if (segments.some((s) => s === '' || s === '.' || s === '..' || /[/\\\0]/.test(s)) || !takes(segments.at(-1)!)) {
    throw new Refusal(400, `the path names no ${what} of the working folder`);
  }
  return segments;
}

/**
 * Writes `text` over `file` under the working folder's lock, through a temporary file beside it, renamed over it,
 * keeping the file's mode while it is there. `before`, when given, runs under the lock just before the rename, and a
 * failure of it leaves the file as it was and passes on as it is: it is where a save compares the file with what it
 * read, and finds the file gone.
 */
export function replaceFile(lock: WorkingFolderLock, file: string, text: string | Uint8Array, before?: () => Promise<void>): Promise<void> {
  return lock(async () => {
    const temporary = join(dirname(file), `.${basename(file)}.${randomUUID()}.tmp`);
    try {
      const mode = await stat(file).then((info) => info.mode & 0o777, () => undefined);
      await writeFile(temporary, text, { flag: 'wx', mode });
      await before?.();
      await rename(temporary, file);
    } catch (error) {
      await rm(temporary, { force: true });
      throw error;
    }
  });
}

/** The request's body as JSON, refused with 413 over {@link MAX_FILE_BYTES} and 400 when it is no JSON. */
export async function jsonBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req as AsyncIterable<Buffer>) {
    size += chunk.length;
    if (size > MAX_FILE_BYTES) throw new Refusal(413, 'the request is over 1 MiB');
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new Refusal(400, 'the request is not JSON');
  }
}

/** Answers `value` as JSON with `status`, never cached, with its `etag` when one is given. */
export function send(res: ServerResponse, status: number, value: unknown, etag?: string) {
  const headers: Record<string, string> = { 'content-type': 'application/json', 'cache-control': 'no-store' };
  if (etag !== undefined) headers.etag = etag;
  res.writeHead(status, headers).end(JSON.stringify(value));
}

/** Answers what `answer` failed with: a {@link Refusal} with its status, anything else as 500; a started answer is cut. */
export function sendFailure(res: ServerResponse, error: unknown) {
  if (res.headersSent) res.destroy();
  else if (error instanceof Refusal) send(res, error.status, { error: error.message });
  else send(res, 500, { error: `the working folder could not be read or written: ${String(error)}` });
}
