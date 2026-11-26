import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import { chooseLanguage } from '../language';
import { opened } from '../motion-test';
import { SessionGate } from '../SessionGate';
import { sessionClient, type Me } from '../session';
import { Shell } from '../Shell';
import { httpSecretsApi, type SecretEntry } from './api';
import { fakeSecretsNode, type FakeSecrets } from './fake';
import { secretsScreen } from './screen';

const NOW = new Date('2026-11-26T10:00:00Z');

const DEVELOPER: Me = {
  name: 'dana',
  kind: 'person',
  roles: ['developer'],
  actions: ['self.read', 'secret.list'],
  refused: {
    'secret.write': { code: 'not-allowed', needs: ['secret-administrator'] },
    'secret.delete': { code: 'not-allowed', needs: ['secret-administrator'] },
  },
  via: 'session',
  firstPassword: false,
  expiresAt: '2026-11-26T22:00:00Z',
  emergencyKey: false,
  session: '0123456789abcdef',
};

const SECRET_ADMINISTRATOR: Me = { ...DEVELOPER, name: 'sam', roles: ['secret-administrator'], actions: ['self.read', 'secret.list', 'secret.write', 'secret.delete'], refused: {} };

const KEY = 'a1b2c3d4e5f60718';

const entry = (name: string, kind: SecretEntry['kind'], rest: Partial<SecretEntry> = {}): SecretEntry => ({
  name,
  kind,
  scope: 'installation',
  changedAt: '2026-11-20T09:00:00Z',
  changedBy: 'sam',
  usedBy: [],
  users: 0,
  unreadable: false,
  keyId: KEY,
  ...rest,
});

const seed = (): FakeSecrets => ({
  secrets: [
    { entry: entry('erp.token', 'value'), value: { kind: 'value', value: 'erp-token-value' } },
    {
      entry: entry('partner.sftp', 'key-pair', {
        keyPair: { algorithm: 'Ed25519', size: 255, fingerprint: '9e1f00aa', subject: 'CN=rail8.acme.example', issuer: 'CN=Acme Issuing CA', notAfter: '2027-03-01T00:00:00Z' },
      }),
      value: { kind: 'key-pair', privateKey: 'private', certificates: [] },
    },
    { entry: entry('rail8.git', 'credentials', { changedBy: 'import' }), value: { kind: 'credentials', user: 'git', password: 'git-token' } },
    { entry: entry('old.token', 'value', { unreadable: true, keyId: '0badc0de' }), value: { kind: 'value', value: 'old' } },
  ],
  uses: {
    'erp.token': [{ kind: 'flow', id: 'orders/order-in', kinds: ['value'] }],
    'partner.sftp': [{ kind: 'connection', id: 'partner-sftp', kinds: ['key-pair'] }],
    'rail8.git': [{ kind: 'platform', id: 'git', kinds: ['credentials'] }],
    'billing.api': [{ kind: 'flow', id: 'billing/invoice-out', kinds: ['value'] }],
  },
  unknown: [],
  key: {
    nodes: [
      { node: 'node-a', joined: true, key: KEY, previous: null },
      { node: 'node-b', joined: true, key: KEY, previous: null },
    ],
    common: KEY,
    rowsUnderAnotherKey: 1,
    previousSet: false,
  },
  node: 'node-a',
});

let root: Root | undefined;
let host: HTMLElement | undefined;

afterEach(() => {
  root?.unmount();
  host?.remove();
  document.body.innerHTML = '';
  chooseLanguage('en');
  localStorage.removeItem('rail8.language');
});

/** The Secrets screen of a Shell behind the session's gate, as `me`, over a fake node holding `state`; gives every answer's text. */
function render(state: FakeSecrets = seed(), me: Me = SECRET_ADMINISTRATOR) {
  const node = fakeSecretsNode(state, me.name, () => NOW);
  const answers: string[] = [];
  const send: typeof fetch = async (input, init) => {
    const res = await node(input, init);
    answers.push(await res.clone().text());
    return res;
  };
  const client = sessionClient({
    send: (async () => new Response(JSON.stringify(me), { status: 200, headers: { 'content-type': 'application/json' } })) as typeof fetch,
    reload: () => undefined,
    timer: () => new Promise(() => undefined),
  });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <SessionGate client={client}>
      <Shell screens={[secretsScreen(httpSecretsApi(send))]} start="secrets" />
    </SessionGate>,
  );
  return { state, answers };
}

const table = () => page.getByRole('table', { name: 'Secrets' });
const rowOf = (name: string) => table().getByRole('row').filter({ has: page.getByText(name, { exact: true }) }).first();
const button = (name: string | RegExp) => page.getByRole('button', { name, exact: true });
const dialog = () => page.getByRole('dialog');
/** The open dialog's button whose name ends with `name`: once it has been loading, antd keeps its hidden icon in the name. */
const inDialog = (name: string) => dialog().getByRole('button', { name: new RegExp(`${name}$`) });

/** Opens New secret, names it `name`, chooses `kind` and fills `fields` by their labels. */
async function create(name: string, kind: string, fields: Record<string, string>) {
  await button('New secret').click();
  await opened(dialog());
  await dialog().getByLabelText('Name').fill(name);
  // a button-style radio's own input is hidden: its label takes the click
  await dialog().getByRole('radiogroup').getByText(kind, { exact: true }).click();
  for (const [label, text] of Object.entries(fields)) await dialog().getByLabelText(label, { exact: true }).fill(text);
  await inDialog('Create').click();
  await expect.element(page.getByText(`The secret ${name} is created.`)).toBeVisible();
}

describe('the Secrets screen', () => {
  test('shows a developer each secret with its kind, users, state and last change, a key pair’s facts, the names not stored, the key and the node, and no write', async () => {
    render(seed(), DEVELOPER);
    await expect.element(page.getByRole('heading', { level: 1, name: 'Secrets' })).toBeVisible();
    await expect.element(rowOf('erp.token')).toMatchTextContent(/Plain value.*Flow orders\/order-in.*Readable.*sam, Nov 20, 2026, 09:00 UTC/);
    await expect.element(rowOf('partner.sftp')).toMatchTextContent(/Key pair.*Connection partner-sftp/);
    await expect.element(rowOf('rail8.git')).toMatchTextContent(/Credentials.*Platform \(git\).*import/);
    await expect.element(rowOf('old.token')).toMatchTextContent(/Plain value.*-.*Unreadable/);
    await expect.element(page.getByText('Answered by node-a')).toBeVisible();

    await rowOf('partner.sftp').getByRole('button', { name: /expand row/i }).click();
    await expect.element(page.getByText('Ed25519, 255 bits')).toBeVisible();
    await expect.element(page.getByText('CN=rail8.acme.example')).toBeVisible();
    await expect.element(page.getByText('CN=Acme Issuing CA')).toBeVisible();
    await expect.element(page.getByText('Mar 1, 2027, 00:00 UTC')).toBeVisible();
    await expect.element(page.getByText('9e1f00aa')).toBeVisible();
    expect(rowOf('erp.token').getByRole('button', { name: /expand row/i }).query()).toBeNull();

    await expect.element(page.getByRole('table', { name: 'Used but not stored' }).getByRole('row').filter({ hasText: 'billing.api' })).toMatchTextContent('Flow billing/invoice-out');
    const keys = page.getByRole('table', { name: 'Keys of the live nodes' });
    await expect.element(keys.getByRole('row').filter({ hasText: 'node-b' })).toMatchTextContent(new RegExp(`node-b${KEY}-`));
    await expect.element(page.getByText('1 row', { exact: true })).toBeVisible();
    await expect.element(page.getByText('Not set', { exact: true })).toBeVisible();

    await expect.element(button('New secret')).toBeDisabled();
    await expect.element(rowOf('erp.token').getByRole('button', { name: 'Overwrite…' })).toBeDisabled();
    await expect.element(rowOf('erp.token').getByRole('button', { name: 'Delete…' })).toBeDisabled();
    await expect.element(page.getByRole('table', { name: 'Used but not stored' }).getByRole('button', { name: 'Create…' })).toBeDisabled();
    await button('New secret').hover();
    await expect.element(page.getByRole('tooltip').getByText('Needs the role secret-administrator')).toBeVisible();
    // nothing in the page holds a value the store holds
    expect(document.body.textContent).not.toContain('erp-token-value');
  });

  test('lets a secret administrator create a secret of each kind, overwrite one and delete one with the confirmation', async () => {
    const { state } = render();
    await create('erp.api', 'Plain value', { Value: 'api-value-1' });
    await create('erp.login', 'Credentials', { User: 'erp-user', Password: 'erp-password' });
    await create('partner.tls', 'Key pair', {
      'Private key': '-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEI\n-----END PRIVATE KEY-----',
      Certificates: 'leaf\n-----BEGIN CERTIFICATE-----\nAAA\n-----END CERTIFICATE-----\n-----BEGIN CERTIFICATE-----\nBBB\n-----END CERTIFICATE-----\n',
    });
    expect(state.secrets.find((s) => s.entry.name === 'erp.login')!.value).toEqual({ kind: 'credentials', user: 'erp-user', password: 'erp-password' });
    expect(state.secrets.find((s) => s.entry.name === 'partner.tls')!.value).toMatchObject({
      kind: 'key-pair',
      certificates: ['-----BEGIN CERTIFICATE-----\nAAA\n-----END CERTIFICATE-----', '-----BEGIN CERTIFICATE-----\nBBB\n-----END CERTIFICATE-----'],
    });
    await expect.element(rowOf('erp.login')).toMatchTextContent(/Credentials.*sam, Nov 26, 2026, 10:00 UTC/);

    await rowOf('erp.token').getByRole('button', { name: 'Overwrite…' }).click();
    await opened(dialog());
    await expect.element(dialog().getByText('Overwrite the secret erp.token')).toBeVisible();
    expect(dialog().getByRole('radiogroup').query()).toBeNull();
    await dialog().getByLabelText('Value', { exact: true }).fill('erp-token-value-2');
    await inDialog('Overwrite').click();
    await expect.element(page.getByText('The secret erp.token is overwritten.')).toBeVisible();
    expect(state.secrets.find((s) => s.entry.name === 'erp.token')!.value).toEqual({ kind: 'value', value: 'erp-token-value-2' });

    await rowOf('erp.token').getByRole('button', { name: 'Delete…' }).click();
    await opened(dialog());
    await expect.element(dialog()).toMatchTextContent(/erp\.token has 1 user:.*Flow orders\/order-in/);
    await expect.element(inDialog('Delete')).toBeDisabled();
    await dialog().getByLabelText('Type erp.token to delete it.').fill('erp.toke');
    await expect.element(inDialog('Delete')).toBeDisabled();
    await dialog().getByLabelText('Type erp.token to delete it.').fill('erp.token');
    await inDialog('Delete').click();
    await expect.element(page.getByText('The secret erp.token is deleted.')).toBeVisible();
    expect(state.secrets.some((s) => s.entry.name === 'erp.token')).toBe(false);
    // its use stays, so the name shows as used but not stored
    await expect.element(page.getByRole('table', { name: 'Used but not stored' }).getByRole('row').filter({ hasText: 'erp.token' })).toBeVisible();
  });

  test('creates a name used but not stored with the kind its users take', async () => {
    const { state } = render();
    await page.getByRole('table', { name: 'Used but not stored' }).getByRole('row').filter({ hasText: 'billing.api' }).getByRole('button', { name: 'Create…' }).click();
    await opened(dialog());
    await expect.element(dialog().getByText('billing.api', { exact: true })).toBeVisible();
    expect(dialog().getByRole('radiogroup').query()).toBeNull();
    await dialog().getByLabelText('Value', { exact: true }).fill('billing-value');
    await inDialog('Create').click();
    await expect.element(page.getByText('The secret billing.api is created.')).toBeVisible();
    expect(state.secrets.find((s) => s.entry.name === 'billing.api')!.value).toEqual({ kind: 'value', value: 'billing-value' });
  });

  test('shows a 409 in-use translated, names the possible users, and then deletes with the name typed out', async () => {
    const state = seed();
    state.unknown = ['legacy'];
    chooseLanguage('de');
    render(state);
    await rowOf('old.token').getByRole('button', { name: 'Löschen…' }).click();
    await opened(dialog());
    await expect.element(dialog().getByText('Nichts verwendet old.token.')).toBeVisible();
    await inDialog('Löschen').click();
    await expect.element(dialog().getByText('Das Secret old.token wird verwendet: löschen Sie es nur mit der Bestätigung.')).toBeVisible();
    await expect.element(dialog().getByText('Das Paket legacy verwendet es vielleicht auch: Seine Verwendungen sind nicht bekannt.')).toBeVisible();
    await expect.element(inDialog('Löschen')).toBeDisabled();
    await dialog().getByLabelText('Geben Sie old.token ein, um es zu löschen.').fill('old.token');
    await inDialog('Löschen').click();
    await expect.element(page.getByText('Das Secret old.token ist gelöscht.')).toBeVisible();
    expect(state.secrets.some((s) => s.entry.name === 'old.token')).toBe(false);
  });

  test('shows a 409 keys-differ translated, saying to try again once the key’s roll has ended', async () => {
    const state = seed();
    state.keysDiffer = { key: 'ffee0011', nodes: ['node-b'] };
    chooseLanguage('cs');
    render(state);
    await button('Nové tajemství').click();
    await opened(dialog());
    await dialog().getByLabelText('Jméno').fill('erp.api');
    await dialog().getByLabelText('Hodnota', { exact: true }).fill('api-value');
    await inDialog('Vytvořit').click();
    await expect.element(dialog().getByText('Tajemství nebylo vytvořeno')).toBeVisible();
    await expect.element(dialog().getByText(/ffee0011/)).toBeVisible();
    expect(dialog().element().textContent).not.toContain('do not hold the key');
    expect(state.secrets.some((s) => s.entry.name === 'erp.api')).toBe(false);
  });

  test('keeps no value in any answer, the page or the browser’s storage once the form is sent', async () => {
    const { state, answers } = render();
    const values = ['plain-value-7f3a', 'login-user-7f3a', 'login-password-7f3a', 'MC4CAQAwBQYDK2VwBCIEIkey7f3a'];
    await create('one.value', 'Plain value', { Value: values[0]! });
    await create('one.login', 'Credentials', { User: values[1]!, Password: values[2]! });
    await create('one.pair', 'Key pair', { 'Private key': `-----BEGIN PRIVATE KEY-----\n${values[3]}\n-----END PRIVATE KEY-----` });
    // the values reached the node
    expect(JSON.stringify(state.secrets.map((s) => s.value))).toContain(values[3]);

    // a closed form keeps nothing either
    await button('New secret').click();
    await opened(dialog());
    await dialog().getByLabelText('Value', { exact: true }).fill('closed-value-7f3a');
    await inDialog('Cancel').click();
    await expect.poll(() => dialog().query()).toBeNull();
    values.push('closed-value-7f3a');

    const fields = [...document.querySelectorAll('input, textarea')].map((f) => (f as HTMLInputElement).value);
    const stored = [localStorage, sessionStorage].flatMap((s) => Object.keys(s).map((k) => `${k}=${s.getItem(k)}`));
    const databases = await indexedDB.databases();
    for (const value of values) {
      expect(answers.filter((a) => a.includes(value))).toEqual([]);
      expect(document.documentElement.outerHTML).not.toContain(value);
      expect(fields.filter((f) => f.includes(value))).toEqual([]);
      expect(stored.filter((s) => s.includes(value))).toEqual([]);
    }
    expect(databases).toEqual([]);
  });
});
