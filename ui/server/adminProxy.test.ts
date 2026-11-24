import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, request, type IncomingHttpHeaders, type Server } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import tls from 'node:tls';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { adminProxy, CA_MAX_BYTES, forward, nodeOf, trustAdminCa } from './adminProxy';

const origins = ['http://localhost:5173'];

describe('nodeOf', () => {
  it('is the node at 127.0.0.1:8081 without a token when nothing is set', () => {
    expect(nodeOf({})).toEqual({ target: 'http://127.0.0.1:8081', token: undefined });
  });

  it('follows RAIL8_ADMIN_URL and RAIL8_ADMIN_TOKEN, and takes a blank token for none', () => {
    expect(nodeOf({ RAIL8_ADMIN_URL: 'https://node:9000', RAIL8_ADMIN_TOKEN: ' t0ken ' })).toEqual({ target: 'https://node:9000', token: 't0ken' });
    expect(nodeOf({ RAIL8_ADMIN_TOKEN: '  ' }).token).toBeUndefined();
  });

  it.each(['http://localhost:8081', 'http://LocalHost:8081/', 'http://127.0.0.1:8081', 'http://127.31.0.9:8081', 'http://[::1]:8081',
    'https://node.example:8443', 'https://10.0.0.5:8443'])('takes %s: loopback, or HTTPS', (url) => {
    expect(nodeOf({ RAIL8_ADMIN_URL: url }).target).toBe(url);
  });

  it.each(['http://node.example:8081', 'http://0.0.0.0:8081', 'http://10.0.0.5:8081', 'http://localhost.:8081', 'http://127.0.0.1.example:8081',
    'http://[fe80::1]:8081', 'HTTP://node.example:8081'])('refuses %s, plain HTTP to another host, unless RAIL8_ADMIN_TLS=off', (url) => {
    expect(() => nodeOf({ RAIL8_ADMIN_URL: url, RAIL8_ADMIN_TOKEN: 'k3y' })).toThrow(
      new Error(`RAIL8_ADMIN_URL '${url}' is plain HTTP to a host other than loopback, so the UI server does not start and sends nothing: use https://, or RAIL8_ADMIN_TLS=off where the network to the node is protected another way`),
    );
    expect(nodeOf({ RAIL8_ADMIN_URL: url, RAIL8_ADMIN_TLS: 'off' }).target).toBe(url);
  });

  it('never names the token in a refusal', () => {
    expect(() => nodeOf({ RAIL8_ADMIN_URL: 'http://node.example', RAIL8_ADMIN_TOKEN: 'k3y-of-the-node' })).toThrow(/^(?!.*k3y-of-the-node)/);
  });

  it.each(['ftp://node.example', 'node.example:8081', 'http://', 'http://a b'])('refuses %s, which is not an http or https URL', (url) => {
    expect(() => nodeOf({ RAIL8_ADMIN_URL: url })).toThrow(`RAIL8_ADMIN_URL '${url}' is not an http or https URL`);
  });

  it('takes RAIL8_ADMIN_TLS as off or not set, empty being not set', () => {
    expect(() => nodeOf({ RAIL8_ADMIN_TLS: 'on' })).toThrow(new Error("RAIL8_ADMIN_TLS must be off or not set, was 'on'"));
    expect(() => nodeOf({ RAIL8_ADMIN_URL: 'http://node.example', RAIL8_ADMIN_TLS: '' })).toThrow(/is plain HTTP/);
  });
});

describe('trustAdminCa', () => {
  let dir: string;

  beforeAll(() => {
    dir = mkdtempSync(join(tmpdir(), 'rail8-ca-'));
  });

  afterAll(() => {
    rmSync(dir, { recursive: true, force: true });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function refusal(ca: string): string {
    vi.spyOn(tls, 'setDefaultCACertificates').mockImplementation(() => undefined);
    try {
      trustAdminCa({ RAIL8_ADMIN_URL: 'https://localhost:8443', RAIL8_ADMIN_CA: ca });
    } catch (refused) {
      return (refused as Error).message;
    }
    return 'not refused';
  }

  it('refuses a file that does not exist, holds no certificate or is too large', () => {
    const missing = join(dir, 'missing.pem');
    const notACertificate = join(dir, 'not.pem');
    writeFileSync(notACertificate, 'not a certificate\n');
    const large = join(dir, 'large.pem');
    writeFileSync(large, Buffer.alloc(CA_MAX_BYTES + 1));
    expect(refusal(missing)).toBe(`RAIL8_ADMIN_CA names a file that does not exist: ${missing}`);
    expect(refusal(notACertificate)).toBe(`RAIL8_ADMIN_CA holds no PEM certificate: ${notACertificate}`);
    expect(refusal(large)).toBe(`RAIL8_ADMIN_CA names a file of more than 65536 bytes: ${large}`);
    expect(refusal(dir)).toBe(`RAIL8_ADMIN_CA names a file that cannot be read: ${dir}`);
  });

  it('reads nothing for plain HTTP, and installs nothing without RAIL8_ADMIN_CA', () => {
    const install = vi.spyOn(tls, 'setDefaultCACertificates').mockImplementation(() => undefined);
    trustAdminCa({ RAIL8_ADMIN_URL: 'http://localhost:8081', RAIL8_ADMIN_CA: join(dir, 'missing.pem') });
    trustAdminCa({ RAIL8_ADMIN_URL: 'https://localhost:8443', RAIL8_ADMIN_CA: '' });
    expect(install).not.toHaveBeenCalled();
  });

  it('refuses RAIL8_ADMIN_CA on a Node without tls.setDefaultCACertificates', () => {
    const ca = join(dir, 'self.crt');
    execFileSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-keyout', join(dir, 'self.key'),
      '-out', ca, '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost', '-days', '1'], { stdio: 'ignore' });
    vi.spyOn(tls as unknown as Record<string, unknown>, 'setDefaultCACertificates', 'get').mockReturnValue(undefined);
    expect(() => trustAdminCa({ RAIL8_ADMIN_URL: 'https://localhost:8443', RAIL8_ADMIN_CA: ca })).toThrow(
      new Error(`RAIL8_ADMIN_CA needs Node 22.19 or newer to be trusted; this is Node ${process.version}`),
    );
  });
});

describe('forward', () => {
  it('refuses a request without Sec-Fetch-Site', () => {
    expect(forward({}, { origins })).toHaveProperty('refused');
  });

  it('refuses a request another site sent', () => {
    expect(forward({ 'sec-fetch-site': 'cross-site' }, { origins })).toHaveProperty('refused');
    expect(forward({ 'sec-fetch-site': 'same-site' }, { origins })).toHaveProperty('refused');
    expect(forward({ 'sec-fetch-site': 'none' }, { origins })).toHaveProperty('refused');
  });

  it('refuses an Origin outside the fixed list, even one that matches the Host header', () => {
    const rebound = { 'sec-fetch-site': 'same-origin', origin: 'http://evil.example:5173', host: 'evil.example:5173' };
    expect(forward(rebound, { origins })).toHaveProperty('refused');
  });

  it('passes only Accept and Content-Type on, never what the browser adds', () => {
    const headers: IncomingHttpHeaders = {
      'sec-fetch-site': 'same-origin',
      'sec-fetch-mode': 'cors',
      'sec-fetch-dest': 'empty',
      origin: 'http://localhost:5173',
      referer: 'http://localhost:5173/',
      cookie: 'session=1',
      authorization: 'Bearer from-the-browser',
      accept: 'application/json',
      'content-type': 'application/json',
    };
    expect(forward(headers, { origins })).toEqual({ headers: { accept: 'application/json', 'content-type': 'application/json' } });
  });

  it('sets the configured token and drops the one the browser sent', () => {
    const headers = { 'sec-fetch-site': 'same-origin', authorization: 'Bearer from-the-browser' };
    expect(forward(headers, { origins, token: 'node-token' })).toEqual({ headers: { authorization: 'Bearer node-token' } });
  });
});

describe('adminProxy', () => {
  const servers: Server[] = [];

  afterEach(async () => {
    await Promise.all(servers.splice(0).map((s) => new Promise((done) => s.close(done))));
  });

  async function listen(server: Server): Promise<string> {
    servers.push(server);
    await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
    return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  }

  /** A node that records what it was sent and answers 202 with a JSON body. */
  async function node(): Promise<{ url: string; seen: { method?: string; url?: string; headers?: IncomingHttpHeaders }[] }> {
    const seen: { method?: string; url?: string; headers?: IncomingHttpHeaders }[] = [];
    const url = await listen(
      createServer((req, res) => {
        seen.push({ method: req.method, url: req.url, headers: req.headers });
        res.writeHead(202, { 'content-type': 'application/json', 'set-cookie': 'node=1' }).end('{"id":"m-1","attempt":2}');
      }),
    );
    return { url, seen };
  }

  async function proxy(target: string, token?: string): Promise<string> {
    const middleware = adminProxy({ target, origins, token });
    return listen(createServer((req, res) => middleware(req, res, () => res.writeHead(404).end('next'))));
  }

  function send(base: string, method: string, path: string, headers: Record<string, string>) {
    return new Promise<{ status: number; headers: IncomingHttpHeaders; body: string }>((done, fail) => {
      const req = request(base + path, { method, headers }, (res) => {
        let body = '';
        res.setEncoding('utf8').on('data', (chunk: string) => (body += chunk));
        res.on('end', () => done({ status: res.statusCode ?? 0, headers: res.headers, body }));
      });
      req.on('error', fail).end();
    });
  }

  const browser = { 'sec-fetch-site': 'same-origin', origin: 'http://localhost:5173', cookie: 'session=1' };

  it('sends an admin request on to the node with its path and query, and gives back its answer', async () => {
    const target = await node();
    const base = await proxy(target.url, 'node-token');
    const answer = await send(base, 'POST', '/admin/dlq/m-1/retry?x=1', browser);
    expect(answer.status).toBe(202);
    expect(answer.body).toBe('{"id":"m-1","attempt":2}');
    expect(answer.headers['content-type']).toBe('application/json');
    expect(answer.headers['set-cookie']).toBeUndefined();
    expect(target.seen).toHaveLength(1);
    expect(target.seen[0]?.method).toBe('POST');
    expect(target.seen[0]?.url).toBe('/admin/dlq/m-1/retry?x=1');
    expect(target.seen[0]?.headers?.authorization).toBe('Bearer node-token');
    expect(target.seen[0]?.headers?.origin).toBeUndefined();
    expect(target.seen[0]?.headers?.cookie).toBeUndefined();
    expect(target.seen[0]?.headers?.['sec-fetch-site']).toBeUndefined();
  });

  it('answers 403 itself to a request from another site, and the node never sees it', async () => {
    const target = await node();
    const base = await proxy(target.url);
    const answer = await send(base, 'GET', '/admin/messages/m-1', { 'sec-fetch-site': 'cross-site', origin: 'http://evil.example' });
    expect(answer.status).toBe(403);
    expect(JSON.parse(answer.body)).toHaveProperty('error');
    expect(target.seen).toHaveLength(0);
  });

  it('answers 405 to a method other than GET and POST', async () => {
    const target = await node();
    const base = await proxy(target.url);
    expect((await send(base, 'DELETE', '/admin/packages/demo', browser)).status).toBe(405);
    expect(target.seen).toHaveLength(0);
  });

  describe('over HTTPS', () => {
    let dir: string;
    let ca: string;
    let defaults: string[];

    beforeAll(() => {
      dir = mkdtempSync(join(tmpdir(), 'rail8-https-'));
      ca = join(dir, 'node.crt');
      execFileSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-keyout', join(dir, 'node.key'),
        '-out', ca, '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost', '-days', '1'], { stdio: 'ignore' });
      defaults = tls.getCACertificates('default');
    });

    afterEach(() => {
      tls.setDefaultCACertificates(defaults);
    });

    afterAll(() => {
      rmSync(dir, { recursive: true, force: true });
    });

    /** A node on HTTPS whose certificate, for localhost only, is its own CA: what it is sent, and its port. */
    async function secureNode(): Promise<{ port: number; seen: IncomingHttpHeaders[] }> {
      const seen: IncomingHttpHeaders[] = [];
      const server = createHttpsServer({ cert: readFileSync(ca), key: readFileSync(join(dir, 'node.key')) }, (req, res) => {
        seen.push(req.headers);
        res.writeHead(200, { 'content-type': 'application/json' }).end('{"ok":true}');
      });
      await listen(server as unknown as Server);
      return { port: (server.address() as AddressInfo).port, seen };
    }

    it('trusts the certificate of RAIL8_ADMIN_CA, and the token goes', async () => {
      const target = await secureNode();
      const env = { RAIL8_ADMIN_URL: `https://localhost:${target.port}`, RAIL8_ADMIN_CA: ca, RAIL8_ADMIN_TOKEN: 'node-token' };
      trustAdminCa(env);
      const node = nodeOf(env);
      const answer = await send(await proxy(node.target, node.token), 'GET', '/admin/me', browser);
      expect(answer.status).toBe(200);
      expect(target.seen[0]?.authorization).toBe('Bearer node-token');
    });

    it('refuses a certificate it does not trust: 502, and the node is sent nothing', async () => {
      const target = await secureNode();
      const answer = await send(await proxy(`https://localhost:${target.port}`, 'node-token'), 'GET', '/admin/me', browser);
      expect(answer.status).toBe(502);
      expect(target.seen).toHaveLength(0);
    });

    it('refuses a certificate for another host, even with RAIL8_ADMIN_TLS=off', async () => {
      const target = await secureNode();
      const env = { RAIL8_ADMIN_URL: `https://127.0.0.1:${target.port}`, RAIL8_ADMIN_CA: ca, RAIL8_ADMIN_TLS: 'off' };
      trustAdminCa(env);
      const answer = await send(await proxy(nodeOf(env).target, 'node-token'), 'GET', '/admin/me', browser);
      expect(answer.status).toBe(502);
      expect(target.seen).toHaveLength(0);
    });
  });

  it('answers 502 when the node does not answer', async () => {
    const base = await proxy('http://127.0.0.1:1');
    const answer = await send(base, 'GET', '/admin/messages/m-1', browser);
    expect(answer.status).toBe(502);
    expect(JSON.parse(answer.body).error).toContain('http://127.0.0.1:1');
  });

  it('leaves every other path to the next handler', async () => {
    const target = await node();
    const base = await proxy(target.url);
    expect((await send(base, 'GET', '/administration', browser)).body).toBe('next');
    expect((await send(base, 'GET', '/src/main.tsx', browser)).body).toBe('next');
    expect(target.seen).toHaveLength(0);
  });
});
