import { currentLanguage, textsIn } from '../../language';
import type { FlowFile, FlowItem } from '../files/flowFiles';
import type { NodeComponents } from '../api';
import type { FlowIngress, ServedSteps } from './types';
import { refusalText } from '../../refusal';
import { uiFetch } from '../../session';

export type { FlowFile, FlowItem };

/** The working folder's flow files, and whether they may be edited. `configured` is false when no folder is set. */
export interface FlowList {
  configured: boolean;
  editable: boolean;
  items: FlowItem[];
  /** True when the folder holds more YAML files than are looked at. */
  truncated: boolean;
}

/** A flow file and the version it was read at. */
export interface StoredFlow {
  file: FlowFile;
  etag: string;
}

/** What the flow screens ask of the server. Every call rejects with an {@link FlowsError} when it is refused. */
export interface FlowsApi {
  list(signal?: AbortSignal): Promise<FlowList>;
  read(path: string, signal?: AbortSignal): Promise<StoredFlow>;
  /**
   * Writes `text` over the file, on condition that it is still at `etag`; a 412 refusal means it changed since. With
   * `etag` null it creates the file, and a 412 refusal means a file of that name exists.
   */
  save(path: string, text: string, etag: string | null): Promise<StoredFlow>;
  /** The step types of the node's modules and plugins, from its admin API, or why they could not be read. */
  steps(signal?: AbortSignal): Promise<ServedSteps>;
  /** Where the node takes requests, from its admin API, or undefined when it could not be asked. */
  ingress(signal?: AbortSignal): Promise<FlowIngress | undefined>;
}

/** A refusal: its HTTP status (0 when the server did not answer) and the error text it gave. */
export class FlowsError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'FlowsError';
    this.status = status;
  }
}

async function refusal(res: Response): Promise<FlowsError> {
  let text = `${res.status} ${res.statusText}`.trim();
  try {
    text = refusalText(await res.json()) ?? text;
  } catch {
    // not JSON: the status line stands
  }
  return new FlowsError(res.status, text);
}

/**
 * The working folder's flow files at `/files/flows`, and the node's step types at `/admin/steps` and components at
 * `/admin/components`, of the server that serves the UI, through `send` (the page's `uiFetch` by default).
 */
export function httpFlowsApi(send: typeof fetch = uiFetch): FlowsApi {
  async function call(url: string, init: RequestInit = {}): Promise<Response> {
    let res: Response;
    try {
      res = await send(url, { ...init, headers: { accept: 'application/json', ...(init.headers as Record<string, string> | undefined) } });
    } catch (failed) {
      throw new FlowsError(0, textsIn(currentLanguage()).t('flows.api.noAnswer', { reason: String(failed) }));
    }
    if (!res.ok) throw await refusal(res);
    return res;
  }

  const fileUrl = (path: string) => `/files/flows/${path.split('/').map(encodeURIComponent).join('/')}`;

  async function stored(res: Response): Promise<StoredFlow> {
    const etag = res.headers.get('etag');
    if (etag === null) throw new FlowsError(res.status, textsIn(currentLanguage()).t('flows.api.noEtag'));
    return { file: (await res.json()) as FlowFile, etag };
  }

  return {
    async list(signal) {
      return (await (await call('/files/flows', { signal })).json()) as FlowList;
    },
    async read(path, signal) {
      return stored(await call(fileUrl(path), { signal }));
    },
    async save(path, text, etag) {
      const precondition: Record<string, string> = etag === null ? { 'if-none-match': '*' } : { 'if-match': etag };
      return stored(await call(fileUrl(path), { method: 'PUT', headers: { 'content-type': 'application/json', ...precondition }, body: JSON.stringify({ text }) }));
    },
    async steps(signal) {
      try {
        return ((await (await call('/admin/steps', { signal })).json()) as { items: { type: string; configSchema: unknown }[] }).items;
      } catch (failed) {
        return { missing: failed instanceof Error ? failed.message : String(failed) };
      }
    },
    async ingress(signal) {
      try {
        const { environment, httpOrigin, items } = (await (await call('/admin/components', { signal })).json()) as NodeComponents;
        const prefixes = Object.fromEntries(items.flatMap((c) => (c.httpPrefix === null ? [] : [[c.scheme, c.httpPrefix]])));
        return { environment, httpOrigin, prefixes };
      } catch {
        return undefined;
      }
    },
  };
}
