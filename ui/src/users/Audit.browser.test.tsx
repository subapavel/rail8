import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { SessionGate } from '../SessionGate';
import { sessionClient, type Me } from '../session';
import { Shell } from '../Shell';
import { UsersError, type AuditApi, type AuditEntry } from './api';
import { AuditList } from './AuditList';
import { fakeAuditApi } from './fake';
import { auditScreen } from './screen';

const NOW = new Date('2026-11-24T10:00:00Z');

const ALICE: Me = {
  name: 'alice',
  kind: 'person',
  roles: ['administrator'],
  actions: ['self.read', 'audit.read'],
  refused: {},
  via: 'session',
  firstPassword: false,
  expiresAt: '2026-11-24T22:00:00Z',
  emergencyKey: false,
  session: '0123456789abcdef',
};

const entry = (id: number, at: string, action: string, actor: string, rest: Partial<AuditEntry> = {}): AuditEntry => ({
  id,
  at,
  action,
  subject: `user/${actor}`,
  actor,
  via: 'session',
  credential: '0123456789abcdef',
  detail: {},
  ...rest,
});

/** The audit of the tests, newest first. */
const ROWS: AuditEntry[] = [
  entry(7, '2026-11-24T09:50:00Z', 'message.body.view', 'anna', { subject: 'message/m-1', detail: { step: 2 } }),
  entry(6, '2026-11-24T09:40:00Z', 'message.retry', 'ci-deploy', { subject: 'message/m-1', via: 'token', credential: 'fedcba9876543210' }),
  entry(5, '2026-11-24T09:30:00Z', 'login', 'anna'),
  entry(4, '2026-11-24T09:00:00Z', 'user.roles', 'alice', { subject: 'user/anna', detail: { added: ['operator'], removed: [] } }),
  entry(3, '2026-11-24T08:30:00Z', 'emergency-key.use', 'emergency-key', { subject: 'emergency-key', via: 'emergency-key', credential: null }),
  entry(2, '2026-11-24T08:00:00Z', 'user.create', 'emergency-key', { subject: 'user/alice', via: 'emergency-key', credential: null }),
  entry(1, '2026-11-23T08:00:00Z', 'login', 'alice'),
];

let root: Root | undefined;
let host: HTMLElement | undefined;

afterEach(() => {
  root?.unmount();
  host?.remove();
  document.body.innerHTML = '';
});

/** The Audit screen of a Shell, behind the session's gate as alice, over `api`; a saved file goes to `save`. */
function render(api: AuditApi, save: (name: string, file: Blob) => void = () => {}) {
  const client = sessionClient({
    send: (async () => new Response(JSON.stringify(ALICE), { status: 200, headers: { 'content-type': 'application/json' } })) as typeof fetch,
    reload: () => undefined,
    timer: () => new Promise(() => undefined),
  });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <SessionGate client={client}>
      <Shell screens={[{ ...auditScreen(api), render: () => <AuditList api={api} now={() => NOW} save={save} /> }]} start="audit" />
    </SessionGate>,
  );
}

const table = () => page.getByRole('table', { name: 'Audit' });
const rows = () => table().getByRole('row');

describe('Audit', () => {
  test('lists the audit newest first, with who did it and how, and loads the older rows', async () => {
    const api = fakeAuditApi(ROWS, 3);
    const read = vi.spyOn(api, 'read');
    render(api);
    await expect.element(rows().nth(1)).toHaveTextContent('2026-11-24T09:50:00ZViewed the payload after step 2message/m-1annaSession 0123456789abcdef{"step":2}');
    await expect.element(rows().nth(2)).toHaveTextContent('2026-11-24T09:40:00ZRetriedmessage/m-1ci-deployToken fedcba9876543210-');
    expect(read).toHaveBeenCalledWith({});

    await page.getByRole('button', { name: 'Load more', exact: false }).click();
    await expect.element(rows().nth(5)).toHaveTextContent('2026-11-24T08:30:00ZUsed the emergency keyemergency-keyemergency-keyEmergency key-');
    expect(read).toHaveBeenLastCalledWith({}, '3');
    await page.getByRole('button', { name: 'Load more', exact: false }).click();
    await expect.element(rows().nth(7)).toHaveTextContent('2026-11-23T08:00:00ZLogged inuser/alicealiceSession 0123456789abcdef-');
    await expect.element(page.getByRole('button', { name: 'Load more', exact: false })).not.toBeInTheDocument();
  });

  test('narrows the list by user, action and time, and shows every row again on Reset', async () => {
    const api = fakeAuditApi(ROWS);
    const read = vi.spyOn(api, 'read');
    render(api);
    await expect.element(rows().nth(7)).toBeInTheDocument();

    await page.getByLabelText('User').fill('anna');
    await page.getByRole('button', { name: 'Query', exact: false }).click();
    await expect.element(rows().nth(2)).toHaveTextContent('2026-11-24T09:30:00ZLogged inuser/annaannaSession 0123456789abcdef-');
    await expect.element(rows().nth(3)).not.toBeInTheDocument();
    expect(read).toHaveBeenLastCalledWith({ user: 'anna' });

    await page.getByLabelText('Action').click();
    await page.getByRole('option', { name: 'Logged in' }).click();
    await page.getByRole('button', { name: 'Query', exact: false }).click();
    await expect.element(rows().nth(1)).toHaveTextContent('2026-11-24T09:30:00ZLogged inuser/annaannaSession 0123456789abcdef-');
    await expect.element(rows().nth(2)).not.toBeInTheDocument();
    expect(read).toHaveBeenLastCalledWith({ user: 'anna', action: 'login' });

    await page.getByRole('button', { name: 'Reset', exact: false }).click();
    await expect.element(rows().nth(7)).toBeInTheDocument();
    expect(read).toHaveBeenLastCalledWith({});

    await page.getByPlaceholder('From').click();
    await userEvent.keyboard('2026-11-24 08:15:00{Enter}');
    await userEvent.keyboard('2026-11-24 09:35:00{Enter}');
    await page.getByRole('button', { name: 'Query', exact: false }).click();
    const from = new Date('2026-11-24T08:15:00').toISOString();
    const to = new Date('2026-11-24T09:35:00').toISOString();
    await vi.waitFor(() => expect(read).toHaveBeenLastCalledWith({ from, to }));
  });

  test('exports every page of the rows the filters keep as a CSV file', async () => {
    const api = fakeAuditApi(ROWS, 2);
    const read = vi.spyOn(api, 'read');
    const saved: { name: string; file: Blob }[] = [];
    render(api, (name, file) => saved.push({ name, file }));
    await expect.element(rows().nth(2)).toBeInTheDocument();
    await page.getByLabelText('User').fill('anna');
    await page.getByRole('button', { name: 'Query', exact: false }).click();
    await expect.element(rows().nth(2)).toHaveTextContent('2026-11-24T09:30:00ZLogged inuser/annaannaSession 0123456789abcdef-');

    read.mockClear();
    await page.getByRole('button', { name: 'Export CSV', exact: false }).click();
    await vi.waitFor(() => expect(saved).toHaveLength(1));
    expect(read.mock.calls).toEqual([
      [{ user: 'anna' }, undefined, 500],
    ]);
    expect(saved[0]?.name).toBe('rail8-audit-2026-11-24T10-00-00Z.csv');
    expect(saved[0]?.file.type).toBe('text/csv;charset=utf-8');
    expect(await saved[0]?.file.text()).toBe(
      'id,at,action,subject,actor,via,credential,detail\r\n' +
        '7,2026-11-24T09:50:00Z,message.body.view,message/m-1,anna,session,0123456789abcdef,"{""step"":2}"\r\n' +
        '5,2026-11-24T09:30:00Z,login,user/anna,anna,session,0123456789abcdef,{}\r\n',
    );

    await page.getByRole('button', { name: 'Reset', exact: false }).click();
    await expect.element(rows().nth(2)).toBeInTheDocument();
    read.mockClear();
    await page.getByRole('button', { name: 'Export CSV', exact: false }).click();
    await vi.waitFor(() => expect(saved).toHaveLength(2));
    expect(read.mock.calls.map((call) => call[1])).toEqual([undefined, '2', '4', '6']);
    expect((await saved[1]?.file.text())?.split('\r\n').slice(1, -1).map((line) => line.split(',')[0])).toEqual(['7', '6', '5', '4', '3', '2', '1']);
  });

  test('asks for a narrower time when the list is longer than an export holds, and says why an export failed', async () => {
    const many = { read: vi.fn(async () => ({ items: ROWS.slice(0, 1), next: 'more' })) } satisfies AuditApi;
    many.read.mockImplementation(async (_q?: unknown, _after?: string, limit?: number) => ({
      items: Array.from({ length: limit ?? 50 }, (_, i) => ({ ...ROWS[0], id: i + 1 }) as AuditEntry),
      next: 'more',
    }));
    const save = vi.fn();
    render(many, save);
    await expect.element(rows().nth(1)).toBeInTheDocument();
    await page.getByRole('button', { name: 'Export CSV', exact: false }).click();
    await expect.element(page.getByText('The list is longer than 100,000 rows')).toBeVisible();
    await expect.element(page.getByText('Choose a narrower time, then export again.')).toBeVisible();
    expect(many.read).toHaveBeenCalledTimes(1 + 200);
    expect(save).not.toHaveBeenCalled();

    many.read.mockImplementation(async () => Promise.reject(new UsersError(503, 'the node is stopping')));
    await page.getByRole('button', { name: 'Export CSV', exact: false }).click();
    await expect.element(page.getByText('The audit could not be exported')).toBeVisible();
    await expect.element(page.getByText('The node is stopping.')).toBeVisible();
  });

  test('says when the audit could not be read, and reads it again', async () => {
    const api: AuditApi = { read: vi.fn(async () => Promise.reject(new UsersError(403, 'audit.read is not allowed'))) };
    render(api);
    await expect.element(page.getByText('The audit could not be read')).toBeVisible();
    await expect.element(page.getByText('Audit.read is not allowed.')).toBeVisible();
    api.read = fakeAuditApi(ROWS).read;
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect.element(rows().nth(7)).toBeInTheDocument();
  });
});
