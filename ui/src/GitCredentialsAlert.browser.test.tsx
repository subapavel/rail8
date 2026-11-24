import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import { GitCredentialsAlert, readGitCheck, type GitCheck } from './GitCredentialsAlert';

const hosts: HTMLElement[] = [];

/** The warning over a check that answers `answer`, rendered with its read settled. */
async function show(answer: GitCheck | null) {
  const host = document.createElement('div');
  document.body.append(host);
  hosts.push(host);
  const read = () => Promise.resolve(answer);
  await act(async () => createRoot(host).render(<GitCredentialsAlert read={read} />));
  return host;
}

const checked = (credentials: GitCheck['credentials'], reason: string | null = null): GitCheck => ({
  credentials,
  repository: credentials === null ? null : 'orders',
  reason,
  checkedAt: credentials === null ? null : '2026-11-24T08:15:00Z',
});

afterEach(() => {
  for (const host of hosts.splice(0)) host.remove();
});

describe('the warning of refused git credentials', () => {
  test('says the credentials are refused and what stops working: a deploy, and a restart with an empty git cache', async () => {
    await show(checked('REFUSED', 'orders at refs/heads/release: credentials refused'));
    await expect.element(page.getByRole('alert')).toBeVisible();
    await expect.element(page.getByText("The node's git credentials are refused, or the repository is no longer visible to the token")).toBeVisible();
    const said = page.getByRole('alert').element().textContent ?? '';
    expect(said).toContain('a deploy fails');
    expect(said).toContain('a node that restarts with an empty git cache comes up without its packages');
    expect(said).toContain('orders at refs/heads/release: credentials refused at 2026-11-24T08:15:00Z');
  });

  test('shows nothing while the credentials work, before the first check, while the host is not reached, or without an answer', async () => {
    for (const answer of [checked('WORKING', ''), checked(null), checked('UNREACHABLE', 'orders at HEAD: the transport failed'), null]) {
      expect((await show(answer)).textContent, JSON.stringify(answer)).toBe('');
    }
  });

  test('reads the check from /admin/git, and no answer when the caller may not read it', async () => {
    const asked: string[] = [];
    const answer = checked('REFUSED', 'orders at HEAD: not found');
    const send = (status: number) => async (url: RequestInfo | URL) => {
      asked.push(String(url));
      return new Response(JSON.stringify(status === 200 ? answer : { error: 'forbidden' }), { status });
    };
    expect(await readGitCheck(send(200))).toEqual(answer);
    expect(await readGitCheck(send(403))).toBeNull();
    expect(await readGitCheck(() => Promise.reject(new TypeError('the server is gone')))).toBeNull();
    expect(asked).toEqual(['/admin/git', '/admin/git']);
  });
});
