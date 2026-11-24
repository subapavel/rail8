import { X509Certificate } from 'node:crypto';
import { closeSync, openSync, readSync } from 'node:fs';
import type { IncomingHttpHeaders, IncomingMessage, ServerResponse } from 'node:http';
import { isIPv4 } from 'node:net';
import { Readable } from 'node:stream';
import type { ReadableStream as WebReadableStream } from 'node:stream/web';
import tls from 'node:tls';

/** Where the development proxy sends the UI's admin requests, and whom it lets through. */
export interface AdminProxyOptions {
  /** The node's admin API, such as `http://127.0.0.1:8081`; a request keeps its path and query. */
  target: string;
  /** The development server's own origins (scheme, host and port); a request with any other `Origin` is refused. */
  origins: readonly string[];
  /** The bearer token the node needs; undefined for a node without one. */
  token?: string;
}

/** The most bytes RAIL8_ADMIN_CA may hold. */
export const CA_MAX_BYTES = 64 * 1024;

type Env = Readonly<Record<string, string | undefined>>;

/**
 * The node the development server calls, from its environment: RAIL8_ADMIN_URL, by default http://127.0.0.1:8081, and
 * RAIL8_ADMIN_TOKEN, when it is set and not blank. Both are read on the server and never reach the browser. It throws,
 * so the development server does not start and sends nothing, when RAIL8_ADMIN_URL is no http or https URL, when
 * RAIL8_ADMIN_TLS is neither `off` nor unset (empty counts as unset), or when the URL is plain `http://` to a host other
 * than loopback without RAIL8_ADMIN_TLS=off. Loopback is the host as the URL parser writes it, never resolved:
 * `localhost`, an IPv4 address in 127.0.0.0/8, or `[::1]`. A refusal never names the token.
 */
export function nodeOf(env: Env): { target: string; token?: string } {
  const target = env.RAIL8_ADMIN_URL ?? 'http://127.0.0.1:8081';
  const url = adminUrl(target);
  const off = tlsOff(env);
  if (url.protocol === 'http:' && !off && !loopback(url.hostname)) {
    throw new Error(
      `RAIL8_ADMIN_URL '${target}' is plain HTTP to a host other than loopback, so the UI server does not start and sends nothing: ` +
        'use https://, or RAIL8_ADMIN_TLS=off where the network to the node is protected another way',
    );
  }
  return { target, token: env.RAIL8_ADMIN_TOKEN?.trim() || undefined };
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

function loopback(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '[::1]' || (isIPv4(hostname) && hostname.startsWith('127.'));
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
const RETURNED = ['content-type', 'content-length', 'www-authenticate'] as const;

/** What to do with a request: refuse it, saying why, or send it on with these headers. */
export type Forward = { refused: string } | { headers: Record<string, string> };

/**
 * Why a request does not come from a page of this server, or undefined when it does: it must carry
 * `Sec-Fetch-Site: same-origin`, and an `Origin`, when there is one, from `origins` (never compared with the request's
 * `Host`, which a page on a re-bound domain controls). `what` names what the request is for in the refusal.
 */
export function foreign(headers: IncomingHttpHeaders, origins: readonly string[], what: string): string | undefined {
  if (headers['sec-fetch-site'] !== 'same-origin') {
    return `only this server's own pages may call ${what} through it (Sec-Fetch-Site must be same-origin)`;
  }
  if (headers.origin !== undefined && !origins.includes(headers.origin)) {
    return `the origin ${headers.origin} may not call ${what} through this server`;
  }
  return undefined;
}

/**
 * Decides whether a browser request may reach the node, and with which headers. It must come from a page of this
 * server: `Sec-Fetch-Site: same-origin`, and an `Origin`, when there is one, from the fixed list (never compared with
 * the request's `Host`, which a page on a re-bound domain controls). Only `Accept` and `Content-Type` go on; the token,
 * when one is configured, is set here, so the browser never holds it.
 */
export function forward(headers: IncomingHttpHeaders, options: Pick<AdminProxyOptions, 'origins' | 'token'>): Forward {
  const refused = foreign(headers, options.origins, 'the admin API');
  if (refused !== undefined) return { refused };
  const passed: Record<string, string> = {};
  for (const name of PASSED) {
    const value = headers[name];
    if (typeof value === 'string') passed[name] = value;
  }
  if (options.token) passed.authorization = `Bearer ${options.token}`;
  return { headers: passed };
}

function isAdmin(url: string): boolean {
  return url === '/admin' || url.startsWith('/admin/') || url.startsWith('/admin?');
}

function refuse(res: ServerResponse, status: number, error: string) {
  res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify({ error }));
}

/**
 * A development-server middleware that sends `/admin` requests from the UI's own pages on to the node, as the node
 * answers no browser. It takes GET and POST without a body, which is all the UI sends (405 otherwise), refuses what
 * {@link forward} refuses (403), and answers 502 when the node cannot be reached. Every other path goes to `next`.
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
    const decided = forward(req.headers, options);
    if ('refused' in decided) {
      refuse(res, 403, decided.refused);
      return;
    }
    const stop = new AbortController();
    res.on('close', () => stop.abort());
    fetch(options.target + url, { method: req.method, headers: decided.headers, signal: stop.signal }).then(
      (answer) => {
        const returned: Record<string, string> = {};
        for (const name of RETURNED) {
          const value = answer.headers.get(name);
          if (value !== null) returned[name] = value;
        }
        res.writeHead(answer.status, returned);
        if (answer.body === null) res.end();
        else Readable.fromWeb(answer.body as WebReadableStream<Uint8Array>).on('error', () => res.destroy()).pipe(res);
      },
      (failed: unknown) => {
        if (!res.headersSent) refuse(res, 502, `the node's admin API at ${options.target} does not answer: ${String(failed)}`);
      },
    );
  };
}
