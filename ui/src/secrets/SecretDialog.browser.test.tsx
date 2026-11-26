import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import { opened } from '../motion-test';
import { httpSecretsApi, type SecretEntry, type SecretKind } from './api';
import { fakeSecretsNode, type FakeSecrets } from './fake';
import { SecretDialog } from './SecretDialog';

const NOW = new Date('2026-11-26T10:00:00Z');

const empty = (): FakeSecrets => ({
  secrets: [],
  uses: { 'erp.login': [{ kind: 'connection', id: 'erp', kinds: ['credentials'] }] },
  unknown: [],
  key: { nodes: [{ node: 'node-a', joined: true, key: 'a1b2c3d4', previous: null }], common: 'a1b2c3d4', rowsUnderAnotherKey: 0, previousSet: false },
  node: 'node-a',
});

let root: Root | undefined;
let host: HTMLElement | undefined;

afterEach(() => {
  root?.unmount();
  host?.remove();
  document.body.innerHTML = '';
});

/** The dialog alone, as a connection's form opens it, over a fake node holding `state`; gives what it was closed with. */
function show(state: FakeSecrets, props: { name?: string; kinds?: readonly SecretKind[] } = {}) {
  const closed: (SecretEntry | null)[] = [];
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<SecretDialog api={httpSecretsApi(fakeSecretsNode(state, 'sam', () => NOW))} {...props} onClose={(written) => closed.push(written)} />);
  return closed;
}

const dialog = () => page.getByRole('dialog');
const create = () => dialog().getByRole('button', { name: /Create$/ });

describe('the form that creates a secret', () => {
  test('opened with a name and one kind, it asks only for that kind and creates the secret under that name', async () => {
    const state = empty();
    const closed = show(state, { name: 'erp.login', kinds: ['credentials'] });
    await opened(dialog());
    await expect.element(dialog().getByText('erp.login', { exact: true })).toBeVisible();
    expect(dialog().getByLabelText('Name').query()).toBeNull();
    expect(dialog().getByRole('radiogroup').query()).toBeNull();
    await expect.element(dialog().getByText('Credentials', { exact: true })).toBeVisible();
    await expect.element(create()).toBeDisabled();
    await dialog().getByLabelText('User').fill('erp-user');
    await dialog().getByLabelText('Password').fill('s3cret-pass');
    await create().click();
    await expect.poll(() => closed.length).toBe(1);
    expect(closed[0]).toMatchObject({ name: 'erp.login', kind: 'credentials', changedBy: 'sam' });
    expect(state.secrets[0]!.value).toEqual({ kind: 'credentials', user: 'erp-user', password: 's3cret-pass' });
  });

  test('opened with a name and two kinds, it offers just those, the first chosen', async () => {
    show(empty(), { name: 'erp.login', kinds: ['credentials', 'key-pair'] });
    await opened(dialog());
    const kinds = dialog().getByRole('radiogroup', { name: 'Kind' });
    await expect.element(kinds.getByRole('radio', { name: 'Credentials' })).toBeChecked();
    await expect.element(kinds.getByRole('radio', { name: 'Key pair' })).toBeInTheDocument();
    expect(kinds.getByRole('radio', { name: 'Plain value' }).query()).toBeNull();
  });

  test('opened alone, it asks for the name and offers every kind', async () => {
    const state = empty();
    show(state);
    await opened(dialog());
    await dialog().getByLabelText('Name').fill('Bad Name');
    await dialog().getByLabelText('Value').fill('v');
    await expect.element(create()).toBeDisabled();
    await dialog().getByLabelText('Name').fill('erp.token');
    await create().click();
    await expect.poll(() => state.secrets.map((s) => s.entry.name)).toEqual(['erp.token']);
  });

  test('marks the field the node refuses, says why by its code, and empties every field of the value', async () => {
    show(empty(), { name: 'partner.tls', kinds: ['key-pair'] });
    await opened(dialog());
    await dialog().getByLabelText('Private key').fill('-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----');
    await create().click();
    await expect.element(dialog().getByText("The secret's privateKey is not taken: its form or its size is wrong.")).toBeVisible();
    await expect.element(dialog().getByText('The value\'s fields are emptied once the form is sent: enter the value again.')).toBeVisible();
    await expect.element(dialog().getByLabelText('Private key')).toHaveValue('');
    expect(dialog().getByLabelText('Private key').element().closest('.ant-form-item')?.classList.contains('ant-form-item-has-error')).toBe(true);
  });
});
