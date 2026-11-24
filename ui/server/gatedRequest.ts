import type { IncomingHttpHeaders, IncomingMessage, ServerResponse } from 'node:http';
import { uiSession, type Mode } from './session.ts';

/** A session in the node's form, for tests: its id is `0123456789abcdef`. */
export const SESSION = `rail8s_0123456789abcdef_${'s'.repeat(43)}`;

/** The session mode of `http://localhost` in development. */
export const SESSION_MODE: Mode = { kind: 'session', cookie: 'rail8-session', secure: false };

/** Token mode with `token` as the shared token. */
export const tokenMode = (token: string): Mode => ({ kind: 'token', token, variable: 'RAIL8_ADMIN_TOKEN' });

/**
 * A request to `url` that the session gate in `mode` admitted, for a test that calls a half's code directly: in
 * session mode it holds {@link SESSION} and names it. It throws when the gate refuses it.
 */
export function gatedRequest(mode: Mode, method = 'GET', url = '/git/packages'): IncomingMessage {
  const headers: IncomingHttpHeaders = { 'sec-fetch-site': 'same-origin' };
  if (mode.kind === 'session') {
    headers.cookie = `${mode.cookie}=${SESSION}`;
    headers['rail8-session'] = '0123456789abcdef';
  }
  const req = { method, url, headers } as IncomingMessage;
  const res = { setHeader: () => undefined, writeHead: () => ({ end: () => undefined }) } as unknown as ServerResponse;
  let admitted = false;
  uiSession({ mode, origins: ['http://localhost:5173'], target: 'http://127.0.0.1:8081' })(req, res, () => (admitted = true));
  if (!admitted) throw new Error(`the session gate refused ${method} ${url}`);
  return req;
}
