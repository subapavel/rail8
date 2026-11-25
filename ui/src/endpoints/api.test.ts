import { describe, expect, test, vi } from 'vitest';
import { chooseLanguage, type Language } from '../language';
import { EndpointsError, httpEndpointsApi } from './api';

/** Runs `check` with the UI shown in `language`, and shows it in English again after. */
async function inLanguage(language: Language, check: () => Promise<void>) {
  vi.stubGlobal('document', { documentElement: {} });
  chooseLanguage(language);
  try {
    await check();
  } finally {
    chooseLanguage('en');
    vi.unstubAllGlobals();
  }
}

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

const file = { path: 'demo/endpoints/http in.yaml', editable: true, id: 'http-in', scheme: 'http', direction: 'in', description: null, config: {}, environments: [] };

describe('httpEndpointsApi', () => {
  test('reads a file by its path, each segment encoded, with its ETag', async () => {
    const send = vi.fn(async () => json(200, file, { etag: '"sha256-1"' }));
    expect(await httpEndpointsApi(send).read('demo/endpoints/http in.yaml')).toEqual({ file, etag: '"sha256-1"' });
    expect(send).toHaveBeenCalledWith('/files/endpoints/demo/endpoints/http%20in.yaml', { headers: { accept: 'application/json' } });
  });

  test('saves the options with If-Match', async () => {
    const send = vi.fn(async () => json(200, file, { etag: '"sha256-2"' }));
    expect((await httpEndpointsApi(send).save('demo/a.yaml', { port: 1 }, '"sha256-1"')).etag).toBe('"sha256-2"');
    expect(send).toHaveBeenCalledWith('/files/endpoints/demo/a.yaml', {
      method: 'PUT',
      headers: { accept: 'application/json', 'content-type': 'application/json', 'if-match': '"sha256-1"' },
      body: '{"config":{"port":1}}',
    });
  });

  test('creates a file from its text with If-None-Match: *', async () => {
    const send = vi.fn(async () => json(201, file, { etag: '"sha256-3"' }));
    expect((await httpEndpointsApi(send).create('demo/b.yaml', 'kind: endpoint\n')).etag).toBe('"sha256-3"');
    expect(send).toHaveBeenCalledWith('/files/endpoints/demo/b.yaml', {
      method: 'PUT',
      headers: { accept: 'application/json', 'content-type': 'application/json', 'if-none-match': '*' },
      body: '{"text":"kind: endpoint\\n"}',
    });
  });

  test("lists the node's components, and where it takes requests, from the admin API", async () => {
    const components = { environment: 'dev', httpOrigin: null, items: [{ scheme: 'http', httpPrefix: '/http', settings: { format: 1, described: false, tabs: [], fields: [], rules: [] } }] };
    const send = vi.fn(async () => json(200, components));
    expect(await httpEndpointsApi(send).components()).toEqual(components);
    expect(send).toHaveBeenCalledWith('/admin/components', { headers: { accept: 'application/json' } });
  });

  test("rejects with the server's status and error, or status 0 when it does not answer", async () => {
    await expect(httpEndpointsApi(async () => json(412, { error: 'the file changed' })).read('a.yaml')).rejects.toEqual(new EndpointsError(412, 'the file changed'));
  });

  test("shows a refusal in the UI's language by its code, and one of an unknown code in the server's English", async () => {
    await inLanguage('cs', async () => {
      const refused = { error: 'demo/x.yaml is not an endpoint file', code: 'not-endpoint-file', values: { path: 'demo/x.yaml' } };
      await expect(httpEndpointsApi(async () => json(404, refused)).read('demo/x.yaml')).rejects.toEqual(new EndpointsError(404, 'demo/x.yaml není soubor koncového bodu.'));
      const unknown = { error: 'the server says no', code: 'not-a-code-of-the-ui', values: {} };
      await expect(httpEndpointsApi(async () => json(409, unknown)).read('demo/x.yaml')).rejects.toEqual(new EndpointsError(409, 'the server says no'));
    });
  });

  test('rejects with the status line when the server gives no text, or status 0 when it does not answer', async () => {
    await expect(httpEndpointsApi(async () => new Response('down', { status: 502, statusText: 'Bad Gateway' })).list()).rejects.toMatchObject({ status: 502, message: '502 Bad Gateway' });
    await expect(httpEndpointsApi(async () => Promise.reject(new TypeError('failed'))).list()).rejects.toMatchObject({ status: 0 });
  });
});
