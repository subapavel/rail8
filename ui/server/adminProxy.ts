import { X509Certificate } from 'node:crypto';
import { closeSync, openSync, readSync } from 'node:fs';
import type { IncomingHttpHeaders, IncomingMessage, ServerResponse } from 'node:http';
import { Readable } from 'node:stream';
import type { ReadableStream as WebReadableStream } from 'node:stream/web';
import tls from 'node:tls';
import { foreign, isJson, isLoopbackName, isSessionRefused, nodeFor, refusedSession } from './session.ts';

export { foreign } from './session.ts';

/** Where the development proxy sends the UI's admin requests, and whom it lets through. */
export interface AdminProxyOptions {
  /** The node's admin API, such as `http://127.0.0.1:8081`; a request keeps its path and query. */
  target: string;
  /** The development server's own origins (scheme, host and port); a request with any other `Origin` is refused. */
  origins: readonly string[];
  /** The node's client; `fetch` by default. The credential is the session gate's, never the proxy's. */
  send?: typeof fetch;
}

/** The most bytes of a POST's body the proxy passes on. */
export const PROXY_BODY_MAX_BYTES = 64 * 1024;

/** The most bytes RAIL8_ADMIN_CA may hold. */
export const CA_MAX_BYTES = 64 * 1024;

type Env = Readonly<Record<string, string | undefined>>;

/**
 * The node the development server calls, from its environment: RAIL8_ADMIN_URL, by default http://127.0.0.1:8081. It
 * is read on the server and never reaches the browser; the credential is the session gate's. It throws,
 * so the development server does not start and sends nothing, when RAIL8_ADMIN_URL is no http or https URL, when
 * RAIL8_ADMIN_TLS is neither `off` nor unset (empty counts as unset), or when the URL is plain `http://` to a host other
 * than loopback without RAIL8_ADMIN_TLS=off. Loopback is the host as the URL parser writes it, never resolved:
 * `localhost`, an IPv4 address in 127.0.0.0/8, or `[::1]`.
 */
export function nodeOf(env: Env): { target: string } {
  const target = env.RAIL8_ADMIN_URL ?? 'http://127.0.0.1:8081';
  const url = adminUrl(target);
  const off = tlsOff(env);
  if (url.protocol === 'http:' && !off && !isLoopbackName(url.hostname)) {
    throw new Error(
      `RAIL8_ADMIN_URL '${target}' is plain HTTP to a host other than loopback, so the UI server does not start and sends nothing: ` +
        'use https://, or RAIL8_ADMIN_TLS=off where the network to the node is protected another way',
    );
  }
  return { target };
}

/**
 * For an `https://` RAIL8_ADMIN_URL with RAIL8_ADMIN_CA set and not empty: makes the certificates of that PEM file (at
 * most {@link CA_MAX_BYTES} bytes) the only ones this process's TLS clients trust, `fetch` among them, in place of
 * Node's default list. Without it they trust that default list; RAIL8_ADMIN_TLS=off never turns the certificate's or the
 * host name's check off. Call it once, when the development server starts. It throws when the file does not exist,
 * cannot be read, is too large or holds no PEM certificate, and on a Node older than 22.19, which cannot replace the
 * default list.
 */
export function trustAdminCa(env: Env): void {
  const ca = env.RAIL8_ADMIN_CA;
  if (!ca || adminUrl(env.RAIL8_ADMIN_URL ?? 'http://127.0.0.1:8081').protocol !== 'https:') return;
  const pem = boundedRead(ca).toString('ascii');
  const certificates = pem.match(/-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----/g) ?? [];
  try {
    for (const certificate of certificates) new X509Certificate(certificate);
  } catch {
    certificates.length = 0;
  }
  if (certificates.length === 0) throw new Error(`RAIL8_ADMIN_CA holds no PEM certificate: ${ca}`);
  // Node 22.19 brought it: the typings name it on every 22, so the check is made on the module as it runs
  if (typeof (tls as Partial<typeof tls>).setDefaultCACertificates !== 'function') {
    throw new Error(`RAIL8_ADMIN_CA needs Node 22.19 or newer to be trusted; this is Node ${process.version}`);
  }
  tls.setDefaultCACertificates(certificates);
}

/** `target` parsed; it throws unless it is an http or https URL with a host. */
function adminUrl(target: string): URL {
  let url: URL;
  try {
    url = new URL(target);
  } catch {
    throw new Error(`RAIL8_ADMIN_URL '${target}' is not an http or https URL`);
  }
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.hostname === '') {
    throw new Error(`RAIL8_ADMIN_URL '${target}' is not an http or https URL`);
  }
  return url;
}

/** Whether RAIL8_ADMIN_TLS is `off`; it throws for any value but `off` and empty. */
function tlsOff(env: Env): boolean {
  const value = env.RAIL8_ADMIN_TLS ?? '';
  if (value !== '' && value !== 'off') throw new Error(`RAIL8_ADMIN_TLS must be off or not set, was '${value}'`);
  return value === 'off';
}

/** At most {@link CA_MAX_BYTES} bytes of the file `path`; it throws when there are more, or the file cannot be read. */
function boundedRead(path: string): Buffer {
  const buffer = Buffer.alloc(CA_MAX_BYTES + 1);
  let length = 0;
  let fd: number;
  try {
    fd = openSync(path, 'r');
  } catch (failed) {
    const missing = (failed as NodeJS.ErrnoException).code === 'ENOENT';
    throw new Error(`RAIL8_ADMIN_CA names a file that ${missing ? 'does not exist' : 'cannot be read'}: ${path}`, { cause: failed });
  }
  try {
    for (let read = -1; read !== 0 && length < buffer.length; length += read) read = readSync(fd, buffer, length, buffer.length - length, null);
  } catch {
    throw new Error(`RAIL8_ADMIN_CA names a file that cannot be read: ${path}`);
  } finally {
    closeSync(fd);
  }
  if (length > CA_MAX_BYTES) throw new Error(`RAIL8_ADMIN_CA names a file of more than ${CA_MAX_BYTES} bytes: ${path}`);
  return buffer.subarray(0, length);
}

/** The request headers passed on to the node. Every other one, `Origin`, `Referer`, `Cookie`, `Authorization` and `Sec-Fetch-*` among them, stays behind. */
const PASSED = ['accept', 'content-type'] as const;

/** The response headers passed back to the browser. */
const RETURNED = ['content-type', 'content-length', 'www-authenticate', 'retry-after'] as const;

/** What to do with a request: refuse it, saying why, or send it to this URL of the node with these headers. */
export type Forward = { refused: { status: number; code: string; error: string } } | { url: URL; headers: Record<string, string> };

/**
 * Decides whether a browser request to `url` may reach the node at `target`, where, and with which headers. It must come
 * from a page of this server (403 as {@link foreign} refuses). The URL is parsed once against `target`, and that same
 * URL is the one sent. Its path, each segment percent-decoded (400 when one does not decode), empty segments dropped and
 * lower-cased, may not begin `admin`, `sessions` (403 `sessions-refused`): no page script ever sees a session's secret.
 * Only `Accept` and `Content-Type` go on; the credential is set by the session gate's client.
 */
export function forward(url: string, headers: IncomingHttpHeaders, options: Pick<AdminProxyOptions, 'origins' | 'target'>): Forward {
  const refused = foreign(headers, options.origins, 'the admin API');
  if (refused !== undefined) return { refused: { status: 403, code: 'foreign-origin', error: refused } };
  const sent = new URL(url, options.target);
  let segments: string[];
  try {
    segments = sent.pathname.split('/').filter((segment) => segment !== '').map((segment) => decodeURIComponent(segment).toLowerCase());
  } catch {
    return { refused: { status: 400, code: 'bad-path', error: 'a segment of the path is not well encoded' } };
  }
  if (segments[0] === 'admin' && segments[1] === 'sessions') {
    return { refused: { status: 403, code: 'sessions-refused', error: "the UI server passes nothing to the node's sessions: log in through /session" } };
  }
  const passed: Record<string, string> = {};
  for (const name of PASSED) {
    const value = headers[name];
    if (typeof value === 'string') passed[name] = value;
  }
  return { url: sent, headers: passed };
}

function isAdmin(url: string): boolean {
  return url === '/admin' || url.startsWith('/admin/') || url.startsWith('/admin?');
}

function refuse(res: ServerResponse, status: number, error: string, code?: string) {
  res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(code === undefined ? { error } : { error, code }));
}

/** The body of a POST: none, or JSON of at most {@link PROXY_BODY_MAX_BYTES} bytes, counted while it is read. */
async function bodyOf(req: IncomingMessage): Promise<{ body: Buffer | undefined } | { status: number; code: string; error: string }> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req as AsyncIterable<Buffer>) {
    size += chunk.length;
    if (size > PROXY_BODY_MAX_BYTES) return { status: 413, code: 'too-large', error: `this server passes bodies of at most ${PROXY_BODY_MAX_BYTES} bytes on to the admin API` };
    chunks.push(chunk);
  }
  if (size === 0) return { body: undefined };
  if (!isJson(req.headers['content-type'])) return { status: 415, code: 'unsupported-media-type', error: 'this server passes only JSON bodies on to the admin API' };
  return { body: Buffer.concat(chunks) };
}

/**
 * A development-server middleware that sends `/admin` requests from the UI's own pages on to the node, as the node
 * answers no browser. It is mounted after the session gate, whose credential every call carries. It takes GET and POST
 * (405 otherwise) and refuses what {@link forward} refuses. A POST's body is passed on when it is JSON of at most
 * {@link PROXY_BODY_MAX_BYTES} bytes (415, 413 otherwise, before anything is sent), and a POST without one goes on
 * without one. It passes back the status, `Content-Type`, `Content-Length`, `WWW-Authenticate` and `Retry-After`. The
 * session's refusal by the node is answered as the gate answers it; a node that cannot be reached, or that redirects,
 * is 502. Every other path goes to `next`.
 */
export function adminProxy(options: AdminProxyOptions) {
  return (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const url = req.url ?? '';
    if (!isAdmin(url)) {
      next();
      return;
    }
    if (req.method !== 'GET' && req.method !== 'POST') {
      refuse(res, 405, 'this server passes only GET and POST on to the admin API');
      return;
    }
    const decided = forward(url, req.headers, options);
    if ('refused' in decided) {
      refuse(res, decided.refused.status, decided.refused.error, decided.refused.code);
      return;
    }
    const stop = new AbortController();
    res.on('close', () => stop.abort());
    const sent = async () => {
      const node = nodeFor(req, options.target, options.send);
      let body: Buffer | undefined;
      if (req.method === 'POST') {
        const read = await bodyOf(req);
        if ('status' in read) return refuse(res, read.status, read.error, read.code);
        body = read.body;
      }
      const headers = { ...decided.headers };
      if (body === undefined) delete headers['content-type'];
      const answer = await node.send(decided.url, { method: req.method, headers, body: body === undefined ? undefined : new Uint8Array(body), signal: stop.signal });
      const returned: Record<string, string> = {};
      for (const name of RETURNED) {
        const value = answer.headers.get(name);
        if (value !== null) returned[name] = value;
      }
      res.writeHead(answer.status, returned);
      if (answer.body === null) res.end();
      else Readable.fromWeb(answer.body as WebReadableStream<Uint8Array>).on('error', () => res.destroy()).pipe(res);
    };
    sent().catch((failed: unknown) => {
      if (res.headersSent) res.destroy();
      else if (isSessionRefused(failed)) refusedSession(res, failed);
      else refuse(res, 502, `the node's admin API at ${options.target} does not answer: ${String(failed)}`);
    });
  };
}
