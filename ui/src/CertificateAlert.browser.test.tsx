import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import { CertificateAlert, readEndingCertificates, type EndingCertificate } from './CertificateAlert';
import { SessionGate } from './SessionGate';
import { sessionClient, type Me } from './session';

const hosts: HTMLElement[] = [];

/** The warning over a read that answers `answer`, rendered with its read settled. */
async function show(answer: EndingCertificate[] | null) {
  const host = document.createElement('div');
  document.body.append(host);
  hosts.push(host);
  const read = () => Promise.resolve(answer);
  await act(async () => createRoot(host).render(<CertificateAlert read={read} />));
  return host;
}

afterEach(() => {
  for (const host of hosts.splice(0)) host.remove();
});

/** A list of the store as `GET /admin/secrets` answers it, with `secrets` as its entries. */
const listed = (secrets: unknown[]) => ({ secrets, missing: [], key: { nodes: [], common: null, rowsUnderAnotherKey: 0, previousSet: false }, node: 'node-a' });

const pair = (name: string, notAfter: string | undefined, certificate?: 'ending' | 'ended') => ({
  name,
  kind: 'key-pair',
  keyPair: { algorithm: 'EC', size: 256, fingerprint: '00', ...(notAfter === undefined ? {} : { notAfter }), ...(certificate === undefined ? {} : { certificate }) },
});

describe('the warning of certificates that end', () => {
  test('names each key pair whose certificate ends within 30 days or has ended, with its end, and says what to do', async () => {
    await show([
      { name: 'bank.tls', notAfter: '2026-12-05T00:00:00Z', certificate: 'ending' },
      { name: 'old.partner', notAfter: '2026-11-20T12:00:00Z', certificate: 'ended' },
    ]);
    await expect.element(page.getByRole('alert')).toBeVisible();
    await expect.element(page.getByText("2 key pairs' certificates end within 30 days or have ended")).toBeVisible();
    const said = page.getByRole('alert').element().textContent ?? '';
    expect(said).toContain('bank.tls ends Dec 5, 2026, 00:00 UTC');
    expect(said).toContain('old.partner ended Nov 20, 2026, 12:00 UTC');
    expect(said).toContain('Enter a new certificate for each on the Secrets screen');
  });

  test('speaks of one key pair in the singular', async () => {
    await show([{ name: 'bank.tls', notAfter: '2026-12-05T00:00:00Z', certificate: 'ending' }]);
    await expect.element(page.getByText("A key pair's certificate ends within 30 days or has ended")).toBeVisible();
  });

  test('shows nothing when no certificate ends soon, or without an answer', async () => {
    for (const answer of [[], null]) expect((await show(answer)).textContent, JSON.stringify(answer)).toBe('');
  });

  test('reads nothing for a caller who may not list secrets, so the browser logs no refusal', async () => {
    const me: Me = {
      name: 'alice',
      kind: 'person',
      roles: ['administrator'],
      actions: ['self.read', 'user.read'],
      refused: { 'secret.list': { code: 'not-allowed', needs: ['developer', 'secret-administrator'] } },
      via: 'session',
      firstPassword: false,
      expiresAt: '2026-11-26T22:00:00Z',
      emergencyKey: false,
      session: '0123456789abcdef',
    };
    const client = sessionClient({
      send: (async () => new Response(JSON.stringify(me), { status: 200, headers: { 'content-type': 'application/json' } })) as typeof fetch,
      reload: () => undefined,
      timer: () => new Promise(() => undefined),
    });
    let reads = 0;
    const read = () => {
      reads += 1;
      return Promise.resolve([{ name: 'bank.tls', notAfter: '2026-12-05T00:00:00Z', certificate: 'ending' as const }]);
    };
    const host = document.createElement('div');
    document.body.append(host);
    hosts.push(host);
    await act(async () =>
      createRoot(host).render(
        <SessionGate client={client}>
          <span>gate open</span>
          <CertificateAlert read={read} />
        </SessionGate>,
      ),
    );
    await expect.element(page.getByText('gate open')).toBeVisible();
    expect(reads).toBe(0);
    expect(page.getByRole('alert').query()).toBeNull();
  });

  test('reads the marked key pairs from /admin/secrets, and no answer when the caller may not read it', async () => {
    const asked: string[] = [];
    const answer = listed([
      { name: 'erp.token', kind: 'value' },
      pair('later.tls', '2027-06-01T00:00:00Z'),
      pair('no.certificate', undefined),
      pair('old.partner', '2026-11-20T12:00:00Z', 'ended'),
      pair('bank.tls', '2026-12-05T00:00:00Z', 'ending'),
    ]);
    const send = (status: number) => async (url: RequestInfo | URL) => {
      asked.push(String(url));
      return new Response(JSON.stringify(status === 200 ? answer : { error: 'forbidden' }), { status });
    };
    expect(await readEndingCertificates(send(200))).toEqual([
      { name: 'old.partner', notAfter: '2026-11-20T12:00:00Z', certificate: 'ended' },
      { name: 'bank.tls', notAfter: '2026-12-05T00:00:00Z', certificate: 'ending' },
    ]);
    expect(await readEndingCertificates(send(403))).toBeNull();
    expect(await readEndingCertificates(() => Promise.reject(new TypeError('the server is gone')))).toBeNull();
    expect(asked).toEqual(['/admin/secrets', '/admin/secrets']);
  });
});
