import { describe, expect, it, vi } from 'vitest';
import { chooseLanguage } from '../language';
import { httpSecretsApi, SecretsError } from './api';

/** A fetch that records every call and answers each with the next of `answers`. */
function fakeFetch(...answers: Response[]) {
  const calls: { url: string; init?: RequestInit }[] = [];
  const send = (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init });
    const next = answers.shift();
    return next === undefined ? Promise.reject(new Error('no answer left')) : Promise.resolve(next);
  };
  return { send: send as typeof fetch, calls };
}

const json = (status: number, body: unknown) => new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

describe('the secrets of the admin API', () => {
  it('creates with the name in the body, overwrites and deletes by the encoded name, and confirms with ?confirm', async () => {
    const { send, calls } = fakeFetch(json(201, { name: 'erp.login' }), json(200, { name: 'erp.login' }), json(204, null), json(204, null));
    const api = httpSecretsApi(send);
    await api.create('erp.login', { kind: 'credentials', user: 'u', password: 'p' });
    await api.overwrite('erp.login', { kind: 'credentials', user: 'u', password: 'q' });
    await api.remove('erp.login');
    await api.remove('erp.login', 'erp.login');
    expect(calls.map((c) => [c.init?.method, c.url])).toEqual([
      ['POST', '/admin/secrets'],
      ['POST', '/admin/secrets/erp.login'],
      ['DELETE', '/admin/secrets/erp.login'],
      ['DELETE', '/admin/secrets/erp.login?confirm=erp.login'],
    ]);
    expect(JSON.parse(calls[0]!.init!.body as string)).toEqual({ name: 'erp.login', kind: 'credentials', user: 'u', password: 'p' });
    expect(JSON.parse(calls[1]!.init!.body as string)).toEqual({ kind: 'credentials', user: 'u', password: 'q' });
    expect(calls[0]!.init!.headers).toEqual({ accept: 'application/json', 'content-type': 'application/json' });
    expect(calls[2]!.init!.headers).toEqual({ accept: 'application/json' });
  });

  it('rejects a refusal with its status, its code, its body and its text in the UI language', async () => {
    vi.stubGlobal('document', { documentElement: {} });
    chooseLanguage('de');
    try {
      const body = { error: 'the nodes [b] do not hold the key ab12', code: 'keys-differ', values: { key: 'ab12', nodes: ['b'] } };
      const { send } = fakeFetch(json(409, body));
      const refused = await httpSecretsApi(send).create('x', { kind: 'value', value: 'v' }).catch((error: unknown) => error);
      expect(refused).toBeInstanceOf(SecretsError);
      expect(refused).toMatchObject({ status: 409, code: 'keys-differ', body, message: 'Ein laufender Knoten hat den Schlüssel ab12 nicht: versuchen Sie es nach dem Schlüsselwechsel erneut.' });
    } finally {
      chooseLanguage('en');
      vi.unstubAllGlobals();
    }
  });

  it('shows the English text of a code the UI does not know, and the status without a body', async () => {
    const { send } = fakeFetch(json(409, { error: 'a new refusal', code: 'brand-new', values: {} }), new Response('not json', { status: 502 }));
    const api = httpSecretsApi(send);
    await expect(api.list()).rejects.toMatchObject({ status: 409, code: 'brand-new', message: 'a new refusal' });
    await expect(api.list()).rejects.toMatchObject({ status: 502, code: undefined, message: 'the node answered 502' });
  });
});
