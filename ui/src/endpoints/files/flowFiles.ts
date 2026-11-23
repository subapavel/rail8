import { readFile, realpath } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { foreign } from '../../../server/adminProxy.ts';
import { readFlow, saysFlow } from '../flows/flowYaml.ts';
import { contextOf, surveyOf, type FlowContext } from './flowContext.ts';
import { createFile, etagOf, jsonBody, locate, MAX_FILE_BYTES, Refusal, replaceFile, send, sendFailure, walk } from '../../../server/workingFolder.ts';

export type { FlowContext, FlowItem } from './flowContext.ts';

/** The working folder whose flow files the flow screen draws, and who may reach them. */
export interface FlowFilesOptions {
  /** The working folder; undefined when none is set, and the routes say so. */
  workspace?: string;
  /** Whether the files may be edited; the answers say so. */
  editable: boolean;
  /** The development server's own origins; a request with any other `Origin` is refused. */
  origins: readonly string[];
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
 *   (compared again just before the rename), and 422 when the text is no YAML or no flow, as the browser reads it.
 * - `PUT /files/flows/<path>` with `If-None-Match: *` and `{"text": "..."}`: creates the flow file, a `*.yaml` or
 *   `*.yml` in a folder of the working folder that exists, and answers 201 as GET does. Refused as a save is, with 412
 *   when a file of that name exists, 404 when its folder does not, and 400 when both preconditions are sent; 428 when
 *   neither is.
 *
 * Saves are made one at a time. Every other path goes to `next`.
 */
export function flowFiles(options: FlowFilesOptions) {
  let saves: Promise<unknown> = Promise.resolve();
  const rootOf = async () => {
    if (options.workspace === undefined) throw new Refusal(404, 'no working folder is set: start the UI with RAIL8_WORKSPACE');
    return realpath(options.workspace);
  };

  const list = async (signal: AbortSignal) => {
    if (options.workspace === undefined) return { configured: false, editable: false, items: [], truncated: false };
    const { flows, truncated } = await survey(await rootOf(), signal);
    return { configured: true, editable: options.editable, truncated, items: flows.map((f) => f.item) };
  };

  const read = async (path: string, signal?: AbortSignal) => {
    const root = await rootOf();
    const file = await locate(root, path, isYaml, 'flow file');
    const bytes = await readFile(file, { signal });
    const text = bytes.toString('utf8');
    const flow = readFlow(text);
    if (flow.kind === 'other' || (flow.kind === 'problem' && !saysFlow(text))) throw new Refusal(404, `${path} is not a flow file`);
    const { truncated, ...found } = await survey(root, signal);
    const context = contextOf(found, path, truncated);
    return { file: { path, text, editable: options.editable, context } satisfies FlowFile, etag: etagOf(bytes) };
  };

  /** The flow text a save or a create sends, refused when it is too large, no YAML or no flow. */
  const flowText = async (req: IncomingMessage) => {
    const sent = await jsonBody(req);
    if (!isRecord(sent) || typeof sent.text !== 'string') throw new Refusal(400, 'a save sends {"text": "..."}');
    const text = sent.text;
    if (Buffer.byteLength(text, 'utf8') > MAX_FILE_BYTES) throw new Refusal(413, 'the text is over 1 MiB');
    const flow = readFlow(text);
    if (flow.kind === 'problem') throw new Refusal(422, flow.message);
    if (flow.kind === 'other') throw new Refusal(422, 'the text is not a flow');
    return text;
  };

  const create = async (req: IncomingMessage, path: string) => {
    await createFile(await rootOf(), path, isYaml, 'flow file', await flowText(req));
    return read(path);
  };

  const save = async (req: IncomingMessage, path: string) => {
    if (!options.editable) throw new Refusal(403, 'this server shows flow files read-only');
    const ifMatch = req.headers['if-match'];
    const ifNoneMatch = req.headers['if-none-match'];
    if (ifNoneMatch !== undefined && ifMatch !== undefined) throw new Refusal(400, 'a save sends If-Match or If-None-Match, not both');
    if (ifNoneMatch === '*') return { ...(await create(req, path)), created: true };
    if (ifMatch === undefined) throw new Refusal(428, 'a save names the version it changes in If-Match, or creates the file with If-None-Match: *');
    const text = await flowText(req);
    const file = await locate(await rootOf(), path, isYaml, 'flow file');
    const bytes = await readFile(file);
    const current = bytes.toString('utf8');
    const old = readFlow(current);
    if (old.kind === 'other' || (old.kind === 'problem' && !saysFlow(current))) throw new Refusal(404, `${path} is not a flow file`);
    const unchanged = async () => {
      if (etagOf(await readFile(file)) !== ifMatch) throw new Refusal(412, 'the file changed since it was read; nothing was saved');
    };
    if (etagOf(bytes) !== ifMatch) throw new Refusal(412, 'the file changed since it was read; nothing was saved');
    if (!bytes.equals(Buffer.from(text, 'utf8'))) await replaceFile(file, text, unchanged);
    return { ...(await read(path)), created: false };
  };

  return (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const url = new URL(req.url ?? '', 'http://localhost');
    if (url.pathname !== PREFIX && !url.pathname.startsWith(`${PREFIX}/`)) {
      next();
      return;
    }
    const refused = foreign(req.headers, options.origins, 'the working folder');
    if (refused !== undefined) {
      send(res, 403, { error: refused });
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
        const done = saves.then(() => save(req, path));
        saves = done.catch(() => undefined);
        const { file, etag, created } = await done;
        send(res, created ? 201 : 200, file, etag);
      } else send(res, 405, { error: 'the flow files take GET, and PUT of one file' });
    };
    answer().catch((error: unknown) => {
      if (!res.destroyed) sendFailure(res, error);
    });
  };
}
