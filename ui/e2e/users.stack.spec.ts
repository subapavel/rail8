import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { sessionPort } from './stack-workspace';

test.use({ viewport: { width: 1440, height: 900 }, baseURL: `http://localhost:${sessionPort}` });

const admin = process.env.RAIL8_ADMIN_URL ?? 'http://127.0.0.1:8081';
const token = process.env.RAIL8_ADMIN_TOKEN ?? '';
// Users are never deleted, so each run has names of its own. They sort before those of every earlier run, so that they
// stay on the list's first page however many runs came before.
const run = (Number.MAX_SAFE_INTEGER - Date.now()).toString(36);
const ADMIN = `e2e-${run}-admin`;
const USER = `e2e-${run}-user`;
const ADMIN_PASSWORD = 'the administrator chose this one';
const USER_PASSWORD = 'the operator chose this one';

let adminFirst = '';

// The first administrator, made with the token as `rail8 user add` makes one: the node answers its first password once.
test.beforeAll(async ({ request }) => {
  const created = await request.post(`${admin}/admin/users`, {
    headers: { authorization: `Bearer ${token}` },
    data: { name: ADMIN, kind: 'person', roles: ['administrator'] },
  });
  expect(created.status(), await created.text()).toBe(201);
  adminFirst = ((await created.json()) as { firstPassword: string }).firstPassword;
});

async function logIn(page: Page, name: string, password: string) {
  await page.getByLabel('Name', { exact: true }).fill(name);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
}

async function changeFirstPassword(page: Page, first: string, chosen: string) {
  await expect(page.getByRole('heading', { name: 'Change the first password' })).toBeVisible();
  await page.getByLabel('Current password', { exact: true }).fill(first);
  await page.getByLabel('New password', { exact: true }).fill(chosen);
  await page.getByLabel('Repeat the new password', { exact: true }).fill(chosen);
  await page.getByRole('button', { name: 'Change the password', exact: true }).click();
}

async function logOut(page: Page) {
  await page.getByRole('button', { name: 'Log out', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Log in', level: 2 })).toBeVisible();
}

const shown = (page: Page) => page.locator('.rail8-panel').filter({ visible: true });
const rowOf = (page: Page, name: string) => shown(page).getByRole('table', { name: 'Users' }).getByRole('row').filter({ hasText: name });

/** Blocks `name` from its row, confirming in its own popover: another may still be fading out. */
async function block(page: Page, name: string) {
  await rowOf(page, name).getByRole('button', { name: 'Block', exact: true }).click();
  await page.getByRole('tooltip').filter({ hasText: `Block ${name}?` }).getByRole('button', { name: 'Block', exact: true }).click();
}

async function openUsers(page: Page) {
  await page.getByRole('navigation', { name: 'Screens' }).getByRole('menuitem', { name: 'Users', exact: true }).click();
  await expect(shown(page).getByRole('heading', { name: 'Users', level: 1 })).toBeVisible();
}

test('an administrator creates a user and gives a role; the user logs in, changes the first password, and is blocked', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/');

  // the first administrator's first login asks for a password of their own
  await logIn(page, ADMIN, adminFirst);
  await changeFirstPassword(page, adminFirst, ADMIN_PASSWORD);
  await openUsers(page);
  await expect(rowOf(page, ADMIN)).toContainText('Administrator');

  // a new person, whose first password shows once
  await shown(page).getByRole('button', { name: 'New user', exact: true }).click();
  const create = page.getByRole('dialog', { name: 'New user' });
  await create.getByLabel('Name', { exact: true }).fill(USER);
  await create.getByRole('button', { name: 'Create', exact: true }).click();
  const created = page.getByRole('dialog', { name: `${USER} is created` });
  const userFirst = (await created.getByLabel('First password').textContent())!.trim();
  expect(userFirst).toMatch(/^[a-z2-7]{20}$/);
  await created.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(rowOf(page, USER)).toContainText('First password');

  // the operator role, given by difference
  await rowOf(page, USER).getByRole('button', { name: 'Roles…', exact: true }).click();
  const roles = page.getByRole('dialog', { name: `Roles of ${USER}` });
  await roles.getByLabel('Operator', { exact: true }).check();
  await roles.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByText(`${USER} gets Operator`)).toBeVisible();
  await expect(rowOf(page, USER)).toContainText('Operator');
  await rowOf(page, USER).getByRole('button', { name: /expand row/i }).click();
  await expect(shown(page).getByRole('table', { name: `Roles of ${USER}` })).toContainText(`${ADMIN}in a session`);
  await logOut(page);

  // the user's first login, the change of the first password, and the operator's screen
  await logIn(page, USER, userFirst);
  await changeFirstPassword(page, userFirst, USER_PASSWORD);
  const menu = page.getByRole('navigation', { name: 'Screens' });
  await menu.getByRole('menuitem', { name: 'Messages', exact: true }).click();
  await expect(shown(page).getByRole('group', { name: 'Messages by status' })).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: 'Users', exact: true })).toHaveAttribute('aria-disabled', 'true');
  await logOut(page);

  // the administrator blocks the user, whose login is refused from then on
  await logIn(page, ADMIN, ADMIN_PASSWORD);
  await openUsers(page);
  await expect(rowOf(page, USER)).toContainText('Active');
  await block(page, USER);
  await expect(rowOf(page, USER)).toContainText('Blocked');

  // nobody blocks themselves: the node's refusal shows as a plain message
  await block(page, ADMIN);
  await expect(shown(page).getByText('Nobody blocks themselves.')).toBeVisible();
  await logOut(page);

  await logIn(page, USER, USER_PASSWORD);
  await expect(page.getByText('The name or the password is wrong, or the account is blocked.')).toBeVisible();
  // the browser logs each refusal the scenario expects: the 401s of a page logged out and of the blocked login, and the 409
  const expected = /^Failed to load resource: the server responded with a status of (401 \(Unauthorized\)|409 \(Conflict\))$/;
  expect(problems.filter((problem) => !expected.test(problem))).toEqual([]);
});
