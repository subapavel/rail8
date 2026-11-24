import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { SessionGate } from '../SessionGate';
import { sessionClient, type Me } from '../session';
import type { Screen } from '../screens';
import { Shell } from '../Shell';
import type { TokensApi, User, UsersApi } from './api';
import { fakeTokensApi, fakeUsersApi, type FakeUsers } from './fake';
import { usersScreen } from './screen';

const NOW = new Date('2026-11-24T10:00:00Z');

const ADMIN_ACTIONS = ['self.read', 'user.read', 'user.create', 'roles.change', 'administrators.change', 'user.block', 'user.password-reset', 'session.end', 'token.issue', 'token.revoke'];

const ALICE: Me = {
  name: 'alice',
  kind: 'person',
  roles: ['administrator'],
  actions: ADMIN_ACTIONS,
  refused: {},
  via: 'session',
  firstPassword: false,
  expiresAt: '2026-11-24T22:00:00Z',
  emergencyKey: false,
  session: '0123456789abcdef',
};

/** A screen that needs the operator role, to see the menu follow the caller's roles. */
const MESSAGES: Screen = { id: 'messages', title: 'Messages', group: 'Monitor', action: 'message.read', render: () => 'Messages' };

/** The session the node gives alice over `state`: the roles she holds there now, and their actions. */
const aliceIn = (state: FakeUsers) => (): Me => {
  const roles = state.users.find((u) => u.name === 'alice')!.roles.map((g) => g.role);
  const actions = ['self.read', ...(roles.includes('administrator') ? ADMIN_ACTIONS : []), ...(roles.includes('operator') ? ['message.read'] : [])];
  return { ...ALICE, roles, actions: [...new Set(actions)] };
};

const user = (name: string, rest: Partial<User> = {}): User => ({
  name,
  kind: 'person',
  roles: [],
  blockedAt: null,
  firstPassword: false,
  createdAt: '2026-11-20T09:00:00Z',
  createdBy: 'alice',
  sessions: [],
  tokens: [],
  ...rest,
});

const seed = (): FakeUsers => ({
  users: [
    user('alice', {
      roles: [{ role: 'administrator', grantedAt: '2026-11-20T08:00:00Z', grantedBy: 'emergency-key', grantedVia: 'emergency-key' }],
      createdBy: 'emergency-key',
      sessions: [{ id: '0123456789abcdef', createdAt: '2026-11-24T09:00:00Z', lastSeenAt: '2026-11-24T09:55:00Z', expiresAt: '2026-11-24T21:00:00Z' }],
    }),
    user('anna', {
      roles: [
        { role: 'operator', grantedAt: '2026-11-21T10:00:00Z', grantedBy: 'alice', grantedVia: 'session' },
        { role: 'developer', grantedAt: '2026-11-21T10:05:00Z', grantedBy: 'ops', grantedVia: 'shared-token' },
      ],
    }),
    user('ci-deploy', {
      kind: 'technical',
      roles: [{ role: 'developer', grantedAt: '2026-11-22T10:00:00Z', grantedBy: 'alice', grantedVia: 'session' }],
      tokens: [{ id: 'fedcba9876543210', name: 'ci', createdAt: '2026-11-22T10:01:00Z', createdBy: 'alice', expiresAt: '2027-02-20T10:01:00Z', lastUsedAt: null }],
    }),
    user('bert', { blockedAt: '2026-11-23T12:00:00Z' }),
  ],
  firstPasswords: [],
});

let root: Root | undefined;
let host: HTMLElement | undefined;

afterEach(() => {
  root?.unmount();
  host?.remove();
  document.body.innerHTML = '';
});

/**
 * The Users screen of a Shell behind the session's gate, as `me`, over the users and tokens of `state`, which `me` acts
 * on. `me` may be a function, which gives the session each time the page reads it.
 */
function render(
  state: FakeUsers = seed(),
  me: Me | (() => Me) = ALICE,
  api: UsersApi = fakeUsersApi(state, (typeof me === 'function' ? me() : me).name, () => NOW),
  tokens: TokensApi = fakeTokensApi(state, (typeof me === 'function' ? me() : me).name, () => NOW, 'alice password'),
) {
  const session = typeof me === 'function' ? me : () => me;
  const client = sessionClient({
    send: (async () => new Response(JSON.stringify(session()), { status: 200, headers: { 'content-type': 'application/json' } })) as typeof fetch,
    reload: () => undefined,
    timer: () => new Promise(() => undefined),
  });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <SessionGate client={client}>
      <Shell screens={[usersScreen(api, tokens), MESSAGES]} start="users" />
    </SessionGate>,
  );
  return state;
}

const table = () => page.getByRole('table', { name: 'Users' });
const rowOf = (name: string) => table().getByRole('row').filter({ has: page.getByText(name, { exact: true }) }).first();
const button = (name: string) => page.getByRole('button', { name, exact: true });
const dialog = () => page.getByRole('dialog');
const menuItem = (name: string) => page.getByRole('navigation', { name: 'Screens' }).getByRole('menuitem', { name, exact: true });
/** Issue in the open dialog; once it has been loading, antd keeps its hidden loading icon in the name. */
const issue = () => dialog().getByRole('button', { name: /Issue$/ });
/** Runs `action` on the user `row`, confirming in that user's popover: another may still be fading out. */
const confirm = async (row: string, action: string) => {
  await rowOf(row).getByRole('button', { name: action, exact: true }).click();
  await page.getByRole('tooltip').filter({ hasText: `${row}?` }).getByRole('button', { name: action, exact: true }).click();
};

describe('the Users screen', () => {
  test('lists each user with kind, roles, status, live sessions and tokens, and who granted each role and how', async () => {
    render();
    await expect.element(page.getByRole('heading', { level: 1, name: 'Users' })).toBeVisible();
    await expect.element(rowOf('alice')).toMatchTextContent(/aliceYou.*Person.*Administrator.*Active.*1.*0/);
    await expect.element(rowOf('anna')).toMatchTextContent(/Person.*Developer.*Operator.*Active/);
    await expect.element(rowOf('ci-deploy')).toMatchTextContent(/Technical account.*Developer.*Active.*0.*1/);
    await expect.element(rowOf('bert').getByText('Blocked')).toBeVisible();
    expect(page.getByText('The node holds an emergency key').query()).toBeNull();

    await rowOf('anna').getByRole('button', { name: /expand row/i }).click();
    const roles = page.getByRole('table', { name: 'Roles of anna' });
    await expect.element(roles.getByRole('row').filter({ hasText: 'Operator' })).toMatchTextContent(/alice.*in a session.*2026-11-21 10:00 UTC/);
    await expect.element(roles.getByRole('row').filter({ hasText: 'Developer' })).toMatchTextContent(/ops.*with the shared token/);
    await expect.element(page.getByRole('table', { name: 'Sessions of anna' })).toMatchTextContent('No live session');

    await rowOf('ci-deploy').getByRole('button', { name: /expand row/i }).click();
    await expect.element(page.getByRole('table', { name: 'Tokens of ci-deploy' })).toMatchTextContent(/ci.*fedcba9876543210.*2027-02-20 10:01 UTC/);
    await rowOf('alice').getByRole('button', { name: /expand row/i }).click();
    await expect.element(page.getByRole('table', { name: 'Roles of alice' })).toMatchTextContent('with the emergency key');
    await expect.element(page.getByRole('table', { name: 'Sessions of alice' })).toMatchTextContent(/0123456789abcdef.*2026-11-24 09:55 UTC/);
  });

  test('pages a list longer than one page', async () => {
    const many = seed();
    for (let i = 10; i < 40; i++) many.users.push(user(`user-${i}`));
    render(many);
    await expect.element(rowOf('alice')).toBeVisible();
    await expect.element(rowOf('user-30')).toBeVisible();
    expect(rowOf('user-31').query()).toBeNull();
    await page.getByRole('listitem', { name: '2' }).click();
    await expect.element(rowOf('user-39')).toBeVisible();
    expect(rowOf('alice').query()).toBeNull();
  });

  test('creates a person and shows the first password once', async () => {
    const state = render();
    await button('New user').click();
    await page.getByLabelText('Name').fill('Bob');
    await expect.element(button('Create')).toBeDisabled();
    await page.getByLabelText('Name').fill('bob');
    await dialog().getByLabelText('Operator').click();
    await button('Create').click();

    await expect.element(dialog().getByText('bob is created')).toBeVisible();
    const password = state.firstPasswords[0]!;
    await expect.element(dialog().getByText(password, { exact: true })).toBeVisible();
    expect(state.users.find((u) => u.name === 'bob')).toMatchObject({ kind: 'person', firstPassword: true, roles: [{ role: 'operator', grantedBy: 'alice' }] });
    await button('Done').click();
    await expect.element(rowOf('bob')).toMatchTextContent(/Operator.*First password/);
    expect(page.getByText(password).query()).toBeNull();
  });

  test('creates a technical account, which may not be an administrator and has no password', async () => {
    const state = render();
    await button('New user').click();
    await page.getByLabelText('Name').fill('ci-test');
    await page.getByText('Technical account, which has tokens only').click();
    await expect.element(dialog().getByLabelText('Administrator')).toBeDisabled();
    await dialog().getByLabelText('Developer').click();
    await button('Create').click();
    await expect.element(dialog()).toMatchTextContent('ci-test is a technical account: it has no password');
    expect(state.firstPasswords).toEqual([]);
    await button('Done').click();
    await expect.element(rowOf('ci-test')).toMatchTextContent(/Technical account.*Developer/);
  });

  test('says in its words why a name is refused', async () => {
    render();
    await button('New user').click();
    await page.getByLabelText('Name').fill('anna');
    await button('Create').click();
    await expect.element(dialog().getByText('The user was not created')).toBeVisible();
    await expect.element(dialog().getByText('The name anna is taken.')).toBeVisible();
  });

  test('changes roles by difference', async () => {
    const state = render();
    const sent: [string, string[], string[]][] = [];
    const fake = fakeUsersApi(state, 'alice', () => NOW);
    root?.unmount();
    host?.remove();
    render(state, ALICE, {
      ...fake,
      changeRoles: (name, add, remove) => {
        sent.push([name, [...add], [...remove]]);
        return fake.changeRoles(name, add, remove);
      },
    });
    await rowOf('anna').getByRole('button', { name: 'Roles…' }).click();
    await expect.element(dialog().getByLabelText('Operator')).toBeChecked();
    await expect.element(button('Save')).toBeDisabled();
    await dialog().getByLabelText('Operator').click();
    await dialog().getByLabelText('Secret administrator').click();
    await button('Save').click();
    await expect.element(page.getByText('anna gets Secret administrator, loses Operator')).toBeVisible();
    expect(sent).toEqual([['anna', ['secret-administrator'], ['operator']]]);
    await expect.element(rowOf('anna')).toMatchTextContent(/Developer.*Secret administrator/);
    await expect.element(rowOf('anna')).not.toMatchTextContent('Operator');
  });

  test('says that the message bodies role a user gives themselves is recorded as their own', async () => {
    render();
    await rowOf('alice').getByRole('button', { name: 'Roles…' }).click();
    await dialog().getByLabelText('Message bodies').click();
    await expect.element(dialog().getByText('You give yourself the message bodies role')).toBeVisible();
    await expect.element(dialog().getByText(/The audit records it as your own assignment/)).toBeVisible();
    await button('Save').click();
    await expect.element(page.getByText('alice gets Message bodies, recorded in the audit as your own')).toBeVisible();
    await expect.element(rowOf('alice')).toMatchTextContent('Message bodies (own)');

    await rowOf('anna').getByRole('button', { name: 'Roles…' }).click();
    await dialog().getByLabelText('Message bodies').click();
    expect(page.getByText('You give yourself the message bodies role').query()).toBeNull();
  });

  test('shows a role the caller gives themselves at once, without a reload', async () => {
    const state = seed();
    render(state, aliceIn(state));
    await expect.element(menuItem('Messages')).toHaveAttribute('aria-disabled', 'true');
    await rowOf('alice').getByRole('button', { name: 'Roles…' }).click();
    await dialog().getByLabelText('Operator').click();
    await button('Save').click();
    await expect.element(page.getByText('alice gets Operator')).toBeVisible();
    await expect.element(menuItem('Messages')).not.toHaveAttribute('aria-disabled', 'true');
  });

  test('takes away at once what a role the caller takes from themselves allowed', async () => {
    const state = seed();
    state.users = state.users.map((u) =>
      u.name === 'alice' ? { ...u, roles: [...u.roles, { role: 'operator', grantedAt: '2026-11-21T10:00:00Z', grantedBy: 'alice', grantedVia: 'session' }] } : u,
    );
    render(state, aliceIn(state));
    await expect.element(menuItem('Messages')).not.toHaveAttribute('aria-disabled', 'true');
    await rowOf('alice').getByRole('button', { name: 'Roles…' }).click();
    await dialog().getByLabelText('Operator').click();
    await button('Save').click();
    await expect.element(page.getByText('alice loses Operator')).toBeVisible();
    await expect.element(menuItem('Messages')).toHaveAttribute('aria-disabled', 'true');
  });

  test("keeps the actions of a blocked user's row on one line", async () => {
    for (const [width, height] of [
      [1440, 900],
      [1920, 1080],
    ] as const) {
      await page.viewport(width, height);
      render();
      await expect.element(rowOf('bert').getByRole('button', { name: 'End sessions' })).toBeVisible();
      const tops = rowOf('bert')
        .getByRole('button')
        .elements()
        .filter((b) => !b.getAttribute('aria-label')?.match(/expand row/i))
        .map((b) => b.getBoundingClientRect().top);
      expect(tops.length, `${width}`).toBe(4);
      expect(new Set(tops).size, `the actions at ${width} px`).toBe(1);
      root?.unmount();
      host?.remove();
    }
  });

  test('shows the invariants the node keeps as plain messages', async () => {
    render();
    await confirm('alice', 'Block');
    await expect.element(page.getByText('alice was not blocked')).toBeVisible();
    await expect.element(page.getByText('Nobody blocks themselves.')).toBeVisible();

    await rowOf('alice').getByRole('button', { name: 'Roles…' }).click();
    await dialog().getByLabelText('Administrator').click();
    await button('Save').click();
    await expect.element(dialog().getByText('The roles were not changed')).toBeVisible();
    await expect.element(dialog().getByText('At least one unblocked person holds the administrator role.')).toBeVisible();
  });

  test('blocks and unblocks a user, resets a password and ends sessions', async () => {
    const state = render();
    await confirm('anna', 'Block');
    await expect.element(rowOf('anna').getByText('Blocked')).toBeVisible();
    await confirm('anna', 'Unblock');
    await expect.element(rowOf('anna').getByText('Active')).toBeVisible();

    await confirm('anna', 'Reset password');
    const password = state.firstPasswords[0]!;
    await expect.element(dialog().getByText('New first password of anna')).toBeVisible();
    await expect.element(dialog().getByText(password, { exact: true })).toBeVisible();
    await button('Done').click();
    await expect.element(rowOf('anna').getByText('First password')).toBeVisible();
    await expect.element(rowOf('ci-deploy').getByRole('button', { name: 'Reset password' })).not.toBeInTheDocument();

    await confirm('alice', 'End sessions');
    await expect.element(page.getByText('The sessions of alice ended')).toBeVisible();
    await expect.element(rowOf('alice')).toMatchTextContent(/Active\s*0\s*0/);
  });

  test("issues a technical account's token within the ceiling, and shows it once", async () => {
    const state = render({ ...seed(), maxDays: 30 });
    await expect.element(rowOf('anna')).toBeVisible();
    expect(rowOf('anna').getByRole('button', { name: 'New token…' }).query()).toBeNull();
    await rowOf('ci-deploy').getByRole('button', { name: 'New token…' }).click();
    await expect.element(dialog().getByText('New token of ci-deploy')).toBeVisible();
    await expect.element(dialog().getByLabelText('Valid for')).toHaveValue('30');
    expect(dialog().getByLabelText('Your password').query()).toBeNull();
    await dialog().getByLabelText('Name').fill('ci');
    await issue().click();
    await expect.element(dialog().getByText('The token was not issued')).toBeVisible();
    await expect.element(dialog().getByText(/holds a live token named ci\./)).toBeVisible();

    await dialog().getByLabelText('Name').fill('release');
    await dialog().getByLabelText('Valid for').fill('');
    await expect.element(issue()).toBeDisabled();
    await dialog().getByLabelText('Valid for').fill('7');
    await issue().click();
    const issued = state.users.find((u) => u.name === 'ci-deploy')!.tokens.find((t) => t.name === 'release')!;
    expect(issued).toMatchObject({ createdBy: 'alice', expiresAt: '2026-12-01T10:00:00.000Z' });
    await expect.element(dialog().getByText('The token release, valid until 2026-12-01 10:00 UTC:')).toBeVisible();
    const secret = dialog().getByLabelText('Token').element().textContent!;
    expect(secret).toMatch(new RegExp(`^rail8t_${issued.id}_`));
    await button('Done').click();
    expect(page.getByText(secret).query()).toBeNull();
    await expect.element(rowOf('ci-deploy')).toMatchTextContent(/Technical account.*Developer.*Active.*0.*2/);
  });

  test("revokes a user's token from the user's row", async () => {
    const state = render();
    await rowOf('ci-deploy').getByRole('button', { name: /expand row/i }).click();
    const tokens = page.getByRole('table', { name: 'Tokens of ci-deploy' });
    await tokens.getByRole('button', { name: 'Revoke' }).click();
    await page.getByRole('tooltip').filter({ hasText: 'Revoke the token ci?' }).getByRole('button', { name: 'Revoke' }).click();
    await expect.element(page.getByText('The token ci of ci-deploy is revoked')).toBeVisible();
    expect(state.users.find((u) => u.name === 'ci-deploy')!.tokens).toEqual([]);
    await expect.element(rowOf('ci-deploy')).toMatchTextContent(/Active\s*0\s*0/);
  });

  test('shows the notice of the emergency key while the node holds it', async () => {
    render(seed(), { ...ALICE, emergencyKey: true });
    await expect.element(page.getByText('The node holds an emergency key')).toBeVisible();
    await expect.element(page.getByText(/unset it on the node and restart the node/)).toBeVisible();
  });

  test('disables what the caller may not do, saying why', async () => {
    const refused = { code: 'session-required', needs: ['administrator'] };
    render(seed(), {
      ...ALICE,
      via: 'token',
      session: null,
      actions: ['self.read', 'user.read'],
      refused: Object.fromEntries(['user.create', 'roles.change', 'administrators.change', 'user.block', 'user.password-reset', 'session.end', 'token.issue', 'token.revoke'].map((a) => [a, refused])),
    });
    await expect.element(rowOf('ci-deploy').getByRole('button', { name: 'New token…' })).toBeDisabled();
    await rowOf('ci-deploy').getByRole('button', { name: /expand row/i }).click();
    await expect.element(page.getByRole('table', { name: 'Tokens of ci-deploy' }).getByRole('button', { name: 'Revoke' })).toBeDisabled();
    await expect.element(button('New user')).toBeDisabled();
    const anna = rowOf('anna');
    for (const action of ['Roles…', 'Block', 'Reset password', 'End sessions']) await expect.element(anna.getByRole('button', { name: action })).toBeDisabled();
    await userEvent.hover(button('New user').element().parentElement!);
    await expect.element(page.getByRole('tooltip', { name: 'Needs a person logged in' })).toBeVisible();
  });

  test('says when the users could not be read, with Try again', async () => {
    let fail = true;
    const state = seed();
    const fake = fakeUsersApi(state, 'alice', () => NOW);
    render(state, ALICE, {
      ...fake,
      list: () => (fail ? Promise.reject(new Error('the node answered 503')) : fake.list()),
    });
    await expect.element(page.getByText('The users could not be read')).toBeVisible();
    await expect.element(page.getByText('The node answered 503.')).toBeVisible();
    fail = false;
    await button('Try again').click();
    await expect.element(rowOf('anna')).toBeVisible();
  });
});
