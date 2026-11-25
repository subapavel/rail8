import { readFile, realpath } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { foreign } from '../../../server/adminProxy.ts';
import { refusalBody } from '../../../server/refusal.ts';
import { readFlow, saysFlow } from '../flows/flowYaml.ts';
import { contextOf, surveyOf, type FlowContext } from './flowContext.ts';
import {
  admitted,
  createFile,
  etagOf,
  jsonBody,
  locate,
  MAX_FILE_BYTES,
  Refusal,
  replaceFile,
  send,
  sendFailure,
  walk,
  type WorkingFolderLock,
  type WorkingFolderWrites,
} from '../../../server/workingFolder.ts';

export type { FlowContext, FlowItem } from './flowContext.ts';

/** The working folder whose flow files the flow screen draws, and who may reach them. */
export interface FlowFilesOptions {
  /** The working folder; undefined when none is set, and the routes say so. */
  workspace?: string;
  /** Whether the files may be edited; the answers say so. */
  editable: boolean;
  /** The development server's own origins; a request with any other `Origin` is refused. */
  origins: readonly string[];
  /** The working folder's writes, which admit every save and create, and whose lock each takes for its write. */
  writes: WorkingFolderWrites;
}

/** The files the node reads as a package's YAML: `*.yaml` and `*.yml`. */
const isYaml = (name: string) => name.endsWith('.yaml') || name.endsWith('.yml');

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** What `GET /files/flows/<path>` answers. */
export interface FlowFile {
  path: string;
  text: string;
  editable: boolean;
  context: FlowContext;
}

const PREFIX = '/files/flows';

/** The working folder as one walk reads it: its flows, its endpoints, the folder of each package, and what could not be read. */
async function survey(root: string, signal?: AbortSignal) {
  const { files, truncated } = await walk(root, isYaml, signal);
  signal?.throwIfAborted();
  return { ...surveyOf(files), truncated };
}

/**
 * A development-server middleware over the flow files of the working folder, for the UI's own pages only (403 as the
 * `/admin` proxy refuses). A flow file is a `*.yaml` or `*.yml` whose `kind` is `flow`; the walk and its limits are the
 * endpoint files'.
 *
 * - `GET /files/flows`: 200 `{configured, editable, truncated, items}`, each flow file as a {@link FlowItem}, by path;
 *   `configured` is false, with no items, when no working folder is set.
 * - `GET /files/flows/<path>`: 200 the {@link FlowFile} with its `ETag`, the text as it is, also when it cannot be
 *   drawn; 404 when there is no such flow file, 413 when it is over 1 MiB.
 * - `PUT /files/flows/<path>` with `If-Match` and `{"text": "..."}`: writes the text over the flow file, through a
 *   temporary file renamed over it, and answers 200 as GET does. A text equal to the file's is not written, so its
 *   `ETag` stays. Refused with 403 when not editable, 428 without `If-Match`, 400 when the body is not `{"text": "..."}`,
 *   413 when the text is over 1 MiB, 404 when there is no such flow file, 412 when the file changed since that tag
 *   (compared again under the working folder's lock, just before the rename, where a file that is gone, or a path that
 *   now leads to another file, has changed too), 422 when the text is no YAML or no flow, as the browser reads it, and
 *   503 when the working folder has too many writes under way.
 * - `PUT /files/flows/<path>` with `If-None-Match: *` and `{"text": "..."}`: creates the flow file, a `*.yaml` or
 *   `*.yml` in a folder of the working folder that exists, and answers 201 as GET does. Refused as a save is, with 412
 *   when a file of that name exists, 404 when its folder does not, and 400 when both preconditions are sent; 428 when
 *   neither is.
 *
 * Saves are made one at a time, each admitted by the working folder's writes before its body is read, and each takes
 * the working folder's lock for its write. Every other path goes to `next`.
 */
export function flowFiles(options: FlowFilesOptions) {
  let saves: Promise<unknown> = Promise.resolve();
  const rootOf = async () => {
    if (options.workspace === undefined) throw new Refusal(404, 'no-workspace', 'no working folder is set: start the UI with RAIL8_WORKSPACE'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    return realpath(options.workspace);
  };

  const list = async (signal: AbortSignal) => {
    if (options.workspace === undefined) return { configured: false, editable: false, items: [], truncated: false };
    const { flows, truncated } = await survey(await rootOf(), signal);
    return { configured: true, editable: options.editable, truncated, items: flows.map((f) => f.item) };
  };

  const read = async (path: string, signal?: AbortSignal) => {
    const root = await rootOf();
    const file = await locate(root, path, isYaml, 'flow file'); // untranslated: the development server refuses with this text, as the node does, and it has no translations
    const bytes = await readFile(file, { signal });
    const text = bytes.toString('utf8');
    const flow = readFlow(text);
    if (flow.kind === 'other' || (flow.kind === 'problem' && !saysFlow(text))) throw new Refusal(404, 'not-flow-file', `${path} is not a flow file`, { path }); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    const { truncated, ...found } = await survey(root, signal);
    const context = contextOf(found, path, truncated);
    return { file: { path, text, editable: options.editable, context } satisfies FlowFile, etag: etagOf(bytes) };
  };

  /** The flow text a save or a create sends, refused when it is too large, no YAML or no flow. */
  const flowText = async (req: IncomingMessage) => {
    const sent = await jsonBody(req);
    if (!isRecord(sent) || typeof sent.text !== 'string') throw new Refusal(400, 'bad-request', 'a save sends {"text": "..."}');
    const text = sent.text;
    if (Buffer.byteLength(text, 'utf8') > MAX_FILE_BYTES) throw new Refusal(413, 'file-too-large', 'the text is over 1 MiB', { max: MAX_FILE_BYTES }); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    const flow = readFlow(text);
    if (flow.kind === 'problem') throw new Refusal(422, 'flow-file-refused', flow.message);
    if (flow.kind === 'other') throw new Refusal(422, 'not-flow-text', 'the text is not a flow'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    return text;
  };

  const create = async (req: IncomingMessage, path: string, lock: WorkingFolderLock) => {
    await createFile(lock, await rootOf(), path, isYaml, 'flow file', await flowText(req)); // untranslated: the development server refuses with this text, as the node does, and it has no translations
    return read(path);
  };

  const save = async (req: IncomingMessage, path: string, lock: WorkingFolderLock) => {
    if (!options.editable) throw new Refusal(403, 'read-only', 'this server shows flow files read-only'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    const ifMatch = req.headers['if-match'];
    const ifNoneMatch = req.headers['if-none-match'];
    if (ifNoneMatch !== undefined && ifMatch !== undefined) throw new Refusal(400, 'bad-request', 'a save sends If-Match or If-None-Match, not both'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    if (ifNoneMatch === '*') return { ...(await create(req, path, lock)), created: true };
    if (ifMatch === undefined) throw new Refusal(428, 'version-required', 'a save names the version it changes in If-Match, or creates the file with If-None-Match: *'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    const text = await flowText(req);
    const file = await locate(await rootOf(), path, isYaml, 'flow file'); // untranslated: the development server refuses with this text, as the node does, and it has no translations
    const bytes = await readFile(file);
    const current = bytes.toString('utf8');
    const old = readFlow(current);
    if (old.kind === 'other' || (old.kind === 'problem' && !saysFlow(current))) throw new Refusal(404, 'not-flow-file', `${path} is not a flow file`, { path }); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    // under the lock: a file that is gone, or a path that now leads to another file, has changed too
    const unchanged = async () => {
      const now = await locate(await rootOf(), path, isYaml, 'flow file').catch(() => undefined); // untranslated: the development server refuses with this text, as the node does, and it has no translations
      if (now !== file || etagOf(await readFile(file)) !== ifMatch) throw new Refusal(412, 'file-changed', 'the file changed since it was read; nothing was saved'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    };
    if (etagOf(bytes) !== ifMatch) throw new Refusal(412, 'file-changed', 'the file changed since it was read; nothing was saved'); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    if (!bytes.equals(Buffer.from(text, 'utf8'))) await replaceFile(lock, file, text, unchanged);
    return { ...(await read(path)), created: false };
  };

  return (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const url = new URL(req.url ?? '', 'http://localhost');
    if (url.pathname !== PREFIX && !url.pathname.startsWith(`${PREFIX}/`)) {
      next();
      return;
    }
    const refused = foreign(req.headers, options.origins, 'the working folder'); // untranslated: the development server refuses with this text, as the node does, and it has no translations
    if (refused !== undefined) {
      send(res, 403, refusalBody('foreign-origin', refused));
      return;
    }
    const path = url.pathname.slice(PREFIX.length + 1);
    const answer = async () => {
      if (req.method === 'GET') {
        const controller = new AbortController();
        res.once('close', () => controller.abort());
        if (path === '') send(res, 200, await list(controller.signal));
        else {
          const { file, etag } = await read(path, controller.signal);
          send(res, 200, file, etag);
        }
      } else if (path !== '' && req.method === 'PUT') {
        const { file, etag, created } = await admitted(options.writes, (lock) => {
          const done = saves.then(() => save(req, path, lock));
          saves = done.catch(() => undefined);
          return done;
        });
        send(res, created ? 201 : 200, file, etag);
      } else send(res, 405, refusalBody('method-not-allowed', 'the flow files take GET, and PUT of one file')); // untranslated: the English text beside the refusal's code, by which the UI shows it in the user's language
    };
    answer().catch((error: unknown) => {
      if (!res.destroyed) sendFailure(res, error);
    });
  };
}
