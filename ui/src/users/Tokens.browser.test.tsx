import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { SessionGate } from '../SessionGate';
import { sessionClient, type Me } from '../session';
import { Shell } from '../Shell';
import type { LiveToken, TokensApi, User } from './api';
import { fakeTokensApi, type FakeUsers } from './fake';
import { myTokensScreen, tokensScreen } from './screen';

const NOW = new Date('2026-11-24T10:00:00Z');
const PASSWORD = 'alice password';

const ALICE: Me = {
  name: 'alice',
  kind: 'person',
  roles: ['administrator'],
  actions: ['self.read', 'self.tokens', 'self.token-issue', 'token.read', 'token.revoke', 'token.ceiling'],
  refused: {},
  via: 'session',
  firstPassword: false,
  expiresAt: '2026-11-24T22:00:00Z',
  emergencyKey: false,
  session: '0123456789abcdef',
};

const token = (id: string, name: string, expiresAt: string, rest: Partial<LiveToken> = {}): LiveToken => ({
  id,
  name,
  createdAt: '2026-11-01T10:00:00Z',
  createdBy: 'alice',
  expiresAt,
  lastUsedAt: null,
  ...rest,
});

const user = (name: string, tokens: LiveToken[], rest: Partial<User> = {}): User => ({
  name,
  kind: 'person',
  roles: [],
  blockedAt: null,
  firstPassword: false,
  createdAt: '2026-11-01T09:00:00Z',
  createdBy: 'alice',
  sessions: [],
  tokens,
  ...rest,
});

const seed = (): FakeUsers => ({
  users: [
    user('alice', [token('aaaa000000000001', 'laptop', '2027-01-30T10:00:00Z', { lastUsedAt: '2026-11-24T08:00:00Z' })]),
    user('anna', [token('bbbb000000000001', 'cli', '2026-11-30T10:00:00Z', { createdBy: 'anna' }), token('bbbb000000000002', 'later', '2027-03-01T10:00:00Z', { createdBy: 'anna' })]),
    user('ci-deploy', [token('cccc000000000001', 'nightly', '2026-12-08T09:00:00Z'), token('cccc000000000002', 'gone', '2026-11-20T10:00:00Z')], { kind: 'technical' }),
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

/** The screen `start` of a Shell with both token screens, behind the session's gate as `me`, over the tokens of `state`. */
function render(start: 'my-tokens' | 'tokens', state: FakeUsers = seed(), me: Me = ALICE, api: TokensApi = fakeTokensApi(state, me.name, () => NOW, PASSWORD)) {
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
      <Shell screens={[myTokensScreen(api), tokensScreen(api)]} start={start} />
    </SessionGate>,
  );
  return state;
}

const button = (name: string) => page.getByRole('button', { name, exact: true });
const dialog = () => page.getByRole('dialog');
/** Issue in the open dialog; once it has been loading, antd keeps its hidden loading icon in the name. */
const issue = () => dialog().getByRole('button', { name: /Issue$/ });
const rowIn = (table: string, text: string) => page.getByRole('table', { name: table }).getByRole('row').filter({ hasText: text });
/** Presses Revoke in the row that holds `text` of the table `table`, and confirms in the popover that asks `question`. */
const revoke = async (table: string, text: string, question: string) => {
  await rowIn(table, text).getByRole('button', { name: 'Revoke' }).click();
  await page.getByRole('tooltip').filter({ hasText: question }).getByRole('button', { name: 'Revoke' }).click();
};

describe('the My tokens screen', () => {
  test("lists the caller's own live tokens, never another user's, with the ceiling", async () => {
    render('my-tokens', { ...seed(), maxDays: 120 });
    await expect.element(page.getByRole('heading', { level: 1, name: 'My tokens' })).toBeVisible();
    await expect.element(page.getByText(/The tokens of alice for the command line.*A token lives at most 120 days\./)).toBeVisible();
    await expect.element(rowIn('Your tokens', 'laptop')).toMatchTextContent(/laptop.*aaaa000000000001.*Nov 1, 2026, 10:00 UTC by alice.*Jan 30, 2027, 10:00 UTC.*Nov 24, 2026, 08:00 UTC/);
    expect(rowIn('Your tokens', 'cli').query()).toBeNull();
  });

  test('issues a token with the password asked again, and shows it only once', async () => {
    const state = render('my-tokens');
    await button('New token').click();
    await expect.element(dialog().getByLabelText('Valid for')).toHaveValue('90');
    await expect
      .element(dialog().getByText('It confirms that it is you: the token outlives your session. The token itself is made by Rail8 and shown once after Issue.'))
      .toBeVisible();
    await dialog().getByLabelText('Name').fill('Build');
    await dialog().getByLabelText('The password you log in with').fill(PASSWORD);
    await expect.element(issue()).toBeDisabled();
    await dialog().getByLabelText('Name').fill('build');
    await dialog().getByLabelText('The password you log in with').fill('');
    await expect.element(issue()).toBeDisabled();

    await dialog().getByLabelText('The password you log in with').fill('not it');
    await issue().click();
    await expect.element(dialog().getByText('The token was not issued')).toBeVisible();
    await expect.element(dialog().getByText('This is not the password you log in with.')).toBeVisible();
    expect(state.users[0]!.tokens).toHaveLength(1);

    await dialog().getByLabelText('The password you log in with').fill(PASSWORD);
    await dialog().getByLabelText('Valid for').fill('10');
    await issue().click();
    await expect.element(dialog().getByText('The token build, valid until Dec 4, 2026, 10:00 UTC:')).toBeVisible();
    const secret = dialog().getByLabelText('Token').element().textContent!;
    expect(secret).toMatch(/^rail8t_[0-9a-f]{16}_/);
    await expect.element(dialog().getByText(/It is shown only now: copy it\. The command line reads it from RAIL8_TOKEN\./)).toBeVisible();
    await button('Done').click();
    await expect.element(rowIn('Your tokens', 'build')).toMatchTextContent('Dec 4, 2026, 10:00 UTC');
    expect(page.getByText(secret).query()).toBeNull();
  });

  test('revokes a token of its own', async () => {
    const state = render('my-tokens');
    await revoke('Your tokens', 'laptop', 'Revoke the token laptop?');
    await expect.element(page.getByText('The token laptop is revoked')).toBeVisible();
    await expect.element(page.getByRole('table', { name: 'Your tokens' })).toMatchTextContent('No live token. New token issues one for the command line.');
    expect(state.users[0]!.tokens).toEqual([]);
  });

  test('disables New token without a session, saying why, and still revokes', async () => {
    render('my-tokens', seed(), { ...ALICE, via: 'token', session: null, actions: ['self.read', 'self.tokens'], refused: { 'self.token-issue': { code: 'session-required', needs: [] } } });
    await expect.element(button('New token')).toBeDisabled();
    await userEvent.hover(button('New token').element().parentElement!);
    await expect.element(page.getByRole('tooltip', { name: 'Needs a person logged in' })).toBeVisible();
    await expect.element(rowIn('Your tokens', 'laptop').getByRole('button', { name: 'Revoke' })).toBeEnabled();
  });

  test('says when the tokens could not be read, with Try again', async () => {
    let fail = true;
    const state = seed();
    const fake = fakeTokensApi(state, 'alice', () => NOW, PASSWORD);
    render('my-tokens', state, ALICE, { ...fake, mine: () => (fail ? Promise.reject(new Error('the node answered 503')) : fake.mine()) });
    await expect.element(page.getByText('Your tokens could not be read')).toBeVisible();
    fail = false;
    await button('Try again').click();
    await expect.element(rowIn('Your tokens', 'laptop')).toBeVisible();
  });
});

describe('the Tokens screen', () => {
  test("shows the ceiling, and every user's live tokens that expire within 14 days, soonest first", async () => {
    render('tokens');
    await expect.element(page.getByRole('heading', { level: 1, name: 'Tokens' })).toBeVisible();
    await expect.element(page.getByText('Longest life of a token')).toBeVisible();
    await expect.element(page.getByText('365 days')).toBeVisible();
    await expect.element(page.getByText('90 days')).toBeVisible();
    const table = page.getByRole('table', { name: 'Tokens that expire soon' });
    await expect.element(table).toMatchTextContent(/anna.*cli.*bbbb000000000001.*Nov 30, 2026, 10:00 UTC.*ci-deploy.*nightly.*Dec 8, 2026, 09:00 UTC/);
    expect(rowIn('Tokens that expire soon', 'later').query()).toBeNull();
    expect(rowIn('Tokens that expire soon', 'laptop').query()).toBeNull();
    expect(rowIn('Tokens that expire soon', 'gone').query()).toBeNull();
  });

  test('revokes any user’s token', async () => {
    const state = render('tokens');
    await revoke('Tokens that expire soon', 'nightly', 'Revoke the token nightly of ci-deploy?');
    await expect.element(page.getByText('The token nightly of ci-deploy is revoked')).toBeVisible();
    expect(rowIn('Tokens that expire soon', 'nightly').query()).toBeNull();
    expect(state.users[2]!.tokens.map((t) => t.name)).toEqual(['gone']);
  });

  test('lowers the ceiling, which shortens the live tokens and the default', async () => {
    const state = render('tokens');
    await button('Change…').click();
    await expect.element(dialog().getByLabelText('Longest life of a token')).toHaveValue('365');
    await expect.element(button('Save')).toBeDisabled();
    await dialog().getByLabelText('Longest life of a token').fill('30');
    await button('Save').click();
    await expect.element(page.getByText('A token lives at most 30 days')).toBeVisible();
    expect(state.maxDays).toBe(30);
    await expect.element(page.getByText('30 days', { exact: true }).nth(1)).toBeVisible();
    // laptop, issued on 2026-11-01, now ends on 2026-12-01, within 14 days
    await expect.element(rowIn('Tokens that expire soon', 'laptop')).toMatchTextContent('Dec 1, 2026, 10:00 UTC');
  });

  test('says in its words why a ceiling is refused', async () => {
    const state = seed();
    const fake = fakeTokensApi(state, 'alice', () => NOW, PASSWORD);
    render('tokens', state, ALICE, { ...fake, setCeiling: () => Promise.reject(new Error('token.ceiling needs a session')) });
    await button('Change…').click();
    await dialog().getByLabelText('Longest life of a token').fill('30');
    await button('Save').click();
    await expect.element(dialog().getByText('The ceiling was not changed')).toBeVisible();
    await expect.element(dialog().getByText('Token.ceiling needs a session.')).toBeVisible();
  });

  test('disables what the caller may not do, saying why', async () => {
    const refused = { code: 'session-required', needs: ['administrator'] };
    render('tokens', seed(), { ...ALICE, via: 'token', session: null, actions: ['self.read', 'token.read'], refused: { 'token.ceiling': refused, 'token.revoke': refused } });
    await expect.element(button('Change…')).toBeDisabled();
    await expect.element(rowIn('Tokens that expire soon', 'cli').getByRole('button', { name: 'Revoke' })).toBeDisabled();
  });
});
