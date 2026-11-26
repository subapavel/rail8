import { Button } from 'antd';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { home } from './Home';
import { GENERATED_ALPHABET } from './password';
import type { Screen } from './screens';
import { Allowed, SessionGate } from './SessionGate';
import { sessionClient, type Me } from './session';
import { Shell } from './Shell';

const ME: Me = {
  name: 'anna',
  kind: 'person',
  roles: ['operator'],
  actions: ['message.read'],
  refused: {
    'module.call': { code: 'not-allowed', needs: ['developer', 'administrator'] },
    'message.body': { code: 'session-required', needs: ['operator'] },
  },
  via: 'session',
  firstPassword: false,
  expiresAt: '2026-11-24T23:00:00Z',
  emergencyKey: false,
  session: '0123456789abcdef',
};

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

const screens: readonly Screen[] = [
  home,
  { id: 'messages', title: 'Messages', group: 'Monitor', action: 'message.read', description: 'Find messages.', render: () => <p>Messages screen</p> },
  {
    id: 'mappings',
    title: 'Mappings',
    group: 'Design',
    action: 'module.call',
    description: 'Design mappings.',
    render: () => <p>Mappings screen</p>,
  },
  {
    id: 'payload',
    title: 'Payload',
    group: 'Monitor',
    render: () => (
      <Allowed action="message.body">
        <Button>Show the payload</Button>
      </Allowed>
    ),
  },
];

let root: Root | undefined;
let host: HTMLElement | undefined;

afterEach(() => {
  vi.restoreAllMocks();
  root?.unmount();
  host?.remove();
});

/**
 * The gate over the Shell, with a page client whose `send` answers `answer`, which is given each call, and counts the
 * reloads. `answer` may change between calls.
 */
function render(answer: (url: string, init: RequestInit) => Response | Promise<Response>, start = 'home') {
  const calls: { url: string; init: RequestInit }[] = [];
  let reloads = 0;
  const state = { answer };
  const client = sessionClient({
    send: (async (input: string | URL, init: RequestInit = {}) => {
      calls.push({ url: String(input), init });
      return state.answer(String(input), init);
    }) as typeof fetch,
    reload: () => void reloads++,
    timer: () => new Promise(() => undefined),
  });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <SessionGate client={client}>
      <Shell screens={screens} start={start} />
    </SessionGate>,
  );
  return { client, calls, state, reloads: () => reloads };
}

const button = (name: string) => page.getByRole('button', { name, exact: true });
const menuItem = (name: string) => page.getByRole('navigation', { name: 'Screens' }).getByRole('menuitem', { name, exact: true });

describe('the session gate', () => {
  test('shows the Shell for the session, with the name and Log out, which ends it and reloads', async () => {
    const w = render((url) => (url === '/session' ? json(200, ME) : new Response(null, { status: 204 })));
    await expect.element(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    await expect.element(page.getByText('anna', { exact: true })).toBeVisible();
    await button('Log out').click();
    await expect.poll(() => w.reloads()).toBe(1);
    expect(w.calls.at(-1)).toMatchObject({ url: '/session/end', init: { method: 'POST' } });
  });

  test('shows no Log out in token mode', async () => {
    render(() => json(200, { ...ME, via: 'token', session: null }));
    await expect.element(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    expect(button('Log out').query()).toBeNull();
  });

  test('says why the session could not be read, with Try again', async () => {
    const w = render(() => json(503, { error: 'the node is busy', code: 'busy' }, { 'retry-after': '2' }));
    await expect.element(page.getByText('The session could not be read')).toBeVisible();
    await expect.element(page.getByText('The node is busy; nothing was done. Try again. Try again in 2 s.')).toBeVisible();
    w.state.answer = () => json(200, ME);
    await button('Try again').click();
    await expect.element(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
  });

  test('asks for the login, shows a refusal, and shows the Shell once it is in', async () => {
    const w = render((url, init) => (init.method === 'POST' ? json(401, { error: 'no', code: 'login-refused' }) : json(401, { code: 'no-credential' })));
    await page.getByLabelText('Name').fill('anna');
    await page.getByLabelText('Password').fill('the wrong one');
    await button('Log in').click();
    await expect.element(page.getByText('The name or the password is wrong, or the account is blocked.')).toBeVisible();
    expect(page.getByRole('navigation', { name: 'Screens' }).query()).toBeNull();

    w.state.answer = () => json(200, ME);
    await page.getByLabelText('Password').fill('twelve chars!');
    await button('Log in').click();
    await expect.element(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    expect(JSON.parse(String(w.calls.at(-1)!.init.body))).toEqual({ name: 'anna', password: 'twelve chars!' });
  });

  test('asks for the change of a first password before the Shell, checking the repeat', async () => {
    const w = render((url) => (url === '/admin/me/password' ? new Response(null, { status: 204 }) : json(200, { ...ME, firstPassword: true })));
    await expect.element(page.getByRole('heading', { name: 'Change the first password' })).toBeVisible();
    expect(page.getByRole('navigation', { name: 'Screens' }).query()).toBeNull();
    await expect.element(page.getByText('The first password an administrator gave you. It confirms that it is you.')).toBeVisible();
    await expect
      .element(page.getByText('12 to 128 characters. It must not contain your name, anna, nor be the password you logged in with.'))
      .toBeVisible();
    await page.getByLabelText('The password you logged in with').fill('abcdefghijklmnopqrst');
    await page.getByLabelText('New password', { exact: true }).fill('a new long password');
    await page.getByLabelText('Repeat the new password').fill('another long password');
    await button('Change the password').click();
    await expect.element(page.getByText('The two new passwords differ')).toBeVisible();
    expect(w.calls.filter((c) => c.url === '/admin/me/password')).toHaveLength(0);

    w.state.answer = (url) => (url === '/admin/me/password' ? new Response(null, { status: 204 }) : json(200, ME));
    await page.getByLabelText('Repeat the new password').fill('a new long password');
    await button('Change the password').click();
    await expect.element(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    expect(JSON.parse(String(w.calls.find((c) => c.url === '/admin/me/password')!.init.body))).toEqual({
      current: 'abcdefghijklmnopqrst',
      next: 'a new long password',
    });
  });

  test('says what was wrong: not the password logged in with, or the rule the new one breaks', async () => {
    const w = render((url) =>
      url === '/admin/me/password'
        ? json(403, { error: 'the current password is wrong', code: 'wrong-password' })
        : json(200, { ...ME, firstPassword: true }),
    );
    await page.getByLabelText('The password you logged in with').fill('not the first one');
    await page.getByLabelText('New password', { exact: true }).fill('short');
    await page.getByLabelText('Repeat the new password').fill('short');
    await button('Change the password').click();
    await expect.element(page.getByText('A password has 12 to 128 characters: this one has 5.')).toBeVisible();
    expect(w.calls.filter((c) => c.url === '/admin/me/password')).toHaveLength(0);

    await page.getByLabelText('New password', { exact: true }).fill('a new long password');
    await page.getByLabelText('Repeat the new password').fill('a new long password');
    await button('Change the password').click();
    await expect.element(page.getByText('This is not the password you logged in with.')).toBeVisible();

    w.state.answer = (url) =>
      url === '/admin/me/password'
        ? json(400, { error: 'a password does not contain the name', code: 'password-contains-name' })
        : json(200, { ...ME, firstPassword: true });
    await page.getByLabelText('New password', { exact: true }).fill('anna is long enough');
    await page.getByLabelText('Repeat the new password').fill('anna is long enough');
    await button('Change the password').click();
    await expect.element(page.getByText('A password must not contain your name.')).toBeVisible();
  });

  test('generates a new password, shows it to copy while it is the one typed, and sends it', async () => {
    const w = render((url) => (url === '/admin/me/password' ? new Response(null, { status: 204 }) : json(200, { ...ME, firstPassword: true })));
    await expect.element(button('Generate')).toBeVisible();
    expect(page.getByLabelText('Generated password').query()).toBeNull();
    // the first draw spells the name, which the node refuses, so another is drawn
    const spelled = [...'aNNa', ...'x'.repeat(16)].map((c) => GENERATED_ALPHABET.indexOf(c));
    vi.spyOn(crypto, 'getRandomValues').mockImplementationOnce(<T extends ArrayBufferView | null>(into: T) => {
      (into as unknown as Uint8Array).set(spelled);
      return into;
    });
    await button('Generate').click();
    const shown = page.getByLabelText('Generated password');
    await expect.element(shown).toBeVisible();
    const generated = shown.element().textContent!;
    expect(generated).toMatch(/^[a-zA-Z0-9]{20}$/);
    expect(generated.toLowerCase()).not.toContain('anna');
    expect(generated).not.toBe('aNNa' + 'x'.repeat(16));
    await expect.element(page.getByLabelText('New password', { exact: true })).toHaveValue(generated);
    await expect.element(page.getByLabelText('Repeat the new password')).toHaveValue(generated);
    await expect.element(page.getByText('Copy it now: it is shown only here, and Rail8 does not keep it.')).toBeVisible();
    await expect.element(shown.getByRole('button', { name: 'Copy' })).toBeVisible();

    await page.getByLabelText('New password', { exact: true }).fill('typed by hand instead');
    await expect.element(shown).not.toBeInTheDocument();
    await button('Generate').click();
    const again = page.getByLabelText('Generated password').element().textContent!;
    expect(again).not.toBe(generated);

    w.state.answer = (url) => (url === '/admin/me/password' ? new Response(null, { status: 204 }) : json(200, ME));
    await page.getByLabelText('The password you logged in with').fill('abcdefghijklmnopqrst');
    await button('Change the password').click();
    await expect.element(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    expect(JSON.parse(String(w.calls.find((c) => c.url === '/admin/me/password')!.init.body))).toEqual({ current: 'abcdefghijklmnopqrst', next: again });
    expect(page.getByText(again).query()).toBeNull();
  });

  test('asks for the login again over the open tabs once the session ends, and cannot be dismissed', async () => {
    const w = render((url) => (url === '/session' ? json(200, ME) : json(200, [])));
    await expect.element(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    w.state.answer = () => json(401, { code: 'session-ended' });
    const call = w.client.fetch('/admin/messages');
    const dialog = page.getByRole('dialog', { name: 'Log in again' });
    await expect.element(dialog).toBeVisible();
    expect(dialog.getByRole('button', { name: 'Close' }).query()).toBeNull();
    await userEvent.keyboard('{Escape}');
    await expect.element(dialog).toBeVisible();
    await expect.element(page.getByRole('heading', { name: 'Home', level: 1 })).toBeInTheDocument();
    await expect.element(dialog.getByLabelText('Name')).toHaveValue('anna');

    w.state.answer = (url) => (url === '/session' ? json(200, { ...ME, session: 'fedcba9876543210' }) : json(200, ['read']));
    await dialog.getByLabelText('Password').fill('twelve chars!');
    await dialog.getByRole('button', { name: 'Log in' }).click();
    await expect.element(dialog).not.toBeInTheDocument();
    expect(await (await call).json()).toEqual(['read']);
  });
});

describe('a screen the caller may not open', () => {
  test('is disabled in the menu with why, says why on Home, and cannot be chosen in Quick open', async () => {
    render(() => json(200, ME));
    await expect.element(page.getByRole('heading', { name: 'Home', level: 1 })).toBeVisible();
    await expect.element(menuItem('Mappings')).toHaveAttribute('aria-disabled', 'true');
    await expect.element(menuItem('Messages')).not.toHaveAttribute('aria-disabled', 'true');
    await expect.element(page.getByRole('button', { name: 'Mappings' }).first()).toBeDisabled();
    await expect.element(page.getByText('Needs the role developer or administrator').first()).toBeVisible();

    await button('Quick open').click();
    const dialog = page.getByRole('dialog', { name: 'Quick open' });
    await expect.element(dialog.getByRole('button', { name: /Mappings/ })).toBeDisabled();
    await expect.element(dialog.getByRole('button', { name: /Messages/ })).toBeEnabled();
  });

  test('shows why in its open tab in place of the screen', async () => {
    render(() => json(200, ME), 'mappings');
    await expect.element(page.getByRole('heading', { name: 'Mappings cannot open' })).toBeVisible();
    expect(page.getByText('Mappings screen').query()).toBeNull();
  });

  test('disables a control the caller may not use, with why as its tooltip', async () => {
    render(() => json(200, ME), 'payload');
    const control = button('Show the payload');
    await expect.element(control).toBeDisabled();
    await control.hover();
    await expect.element(page.getByRole('tooltip')).toHaveTextContent('Needs a person logged in');
  });
});
