import { execFileSync } from 'node:child_process';
import { cpSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { deployWorkspace, sessionPort } from './stack-workspace';
import { edit, node, quickOpen, save, selectTarget, setParameters } from './studio';

test.use({ viewport: { width: 1440, height: 900 }, baseURL: `http://localhost:${sessionPort}` });

// The quickstart's "Change a mapping and see it": the demo as its first run makes it, the flow greet sent a person, its
// mapping changed in the studio, committed and deployed from Packages, and the person sent again.
const checkout = fileURLToPath(new URL('../..', import.meta.url));
const repository = join(deployWorkspace, 'demo');
const admin = process.env.RAIL8_ADMIN_URL ?? 'http://127.0.0.1:8081';
const key = process.env.RAIL8_ADMIN_TOKEN ?? '';
const token = process.env.RAIL8_TOKEN ?? '';
// the node's HTTP port, where the quickstart publishes every inbound HTTP endpoint
const greet = 'http://127.0.0.1:8080/http/greet';
const PERSON = { name: 'Ann', city: 'Brno' };
// users are never deleted, so each run has a name of its own
const USER = `e2e-${(Number.MAX_SAFE_INTEGER - Date.now()).toString(36)}-dev`;
const PASSWORD = 'the developer chose this one';
const TEXT = '/Greeting/Text';

const git = (...args: string[]) => execFileSync('git', args, { cwd: repository, stdio: 'pipe' });

// The demo's first run: the package copied into the working folder, committed and deployed with bin/rail8 over a node
// that runs no demo; and a person who holds the roles the owner gives himself on the first run.
test.beforeAll(async ({ request }) => {
  const undeployed = await request.delete(`${admin}/admin/packages/demo`, { headers: { authorization: `Bearer ${token}` } });
  expect([200, 204, 404], await undeployed.text()).toContain(undeployed.status());
  rmSync(repository, { recursive: true, force: true });
  cpSync(join(checkout, 'examples', 'demo'), repository, { recursive: true });
  git('init', '-q', '-b', 'main');
  git('config', 'user.name', 'Stack Scenario');
  git('config', 'user.email', 'stack@example.com');
  writeFileSync(join(repository, '.gitattributes'), '* -text\n');
  git('add', '-A');
  git('commit', '-q', '-m', 'The demo');
  execFileSync(join(checkout, 'bin', 'rail8'), ['deploy', 'demo', 'HEAD', '--expect', 'none'], { cwd: checkout, stdio: 'pipe' });

  // the emergency key makes an administrator, who chooses a password and gives himself the other roles, as the owner does
  const created = await request.post(`${admin}/admin/users`, {
    headers: { authorization: `Bearer ${key}` },
    data: { name: USER, kind: 'person', roles: ['administrator'] },
  });
  expect(created.status(), await created.text()).toBe(201);
  const first = ((await created.json()) as { firstPassword: string }).firstPassword;
  const session = async (password: string) => {
    const opened = await request.post(`${admin}/admin/sessions`, { data: { name: USER, password } });
    expect(opened.status(), await opened.text()).toBe(201);
    return { authorization: `Bearer ${((await opened.json()) as { session: string }).session}` };
  };
  const changed = await request.post(`${admin}/admin/me/password`, { headers: await session(first), data: { current: first, next: PASSWORD } });
  expect(changed.status(), await changed.text()).toBe(204);
  const given = await request.post(`${admin}/admin/users/${USER}/roles`, {
    headers: await session(PASSWORD),
    data: { add: ['developer', 'operator', 'message-bodies'], remove: [] },
  });
  expect(given.status(), await given.text()).toBe(200);
});

/** Logs in as the person with the password he chose. */
async function logIn(page: Page) {
  await page.getByLabel('Name', { exact: true }).fill(USER);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
}

const shown = (page: Page) => page.locator('.rail8-panel').filter({ visible: true });
const menu = (page: Page, name: string) => page.getByRole('navigation', { name: 'Screens' }).getByRole('menuitem', { name, exact: true }).click();

/** Opens the demo's tab from the Packages screen. */
async function openDemo(page: Page) {
  await menu(page, 'Packages');
  await shown(page).getByRole('table', { name: 'Packages' }).getByRole('button', { name: 'demo', exact: true }).click();
  await expect(shown(page).getByRole('heading', { level: 1, name: 'demo' })).toBeVisible();
}

test('@stack the real node: a person greeted, the mapping changed in the studio, committed and deployed, and the change in the reply and in Messages', async ({ page, request }) => {
  const problems = collectProblems(page);

  // 1. the person sent to the flow as the demo deploys it
  const before = await request.post(greet, { data: PERSON });
  expect(before.status(), await before.text()).toBe(200);
  expect(await before.text()).toContain('<Text>Hello, Ann</Text>');

  // 2. the mapping opened in the studio from the package's tab, and again from Quick open, its constant changed, and saved
  await page.goto('/');
  await logIn(page);
  await openDemo(page);
  await shown(page).getByRole('tab', { name: /^Artifacts/ }).click();
  await shown(page).getByRole('table', { name: 'Mappings' }).getByRole('button', { name: 'greeting', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'greeting' })).toBeVisible();
  await page.getByRole('button', { name: 'Close greeting', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'greeting' })).toBeHidden();
  await quickOpen(page, 'greeting', 'demo/mappings/greeting.mapping.json');
  await expect(page.getByRole('heading', { level: 1, name: 'greeting' })).toBeVisible();
  const crumbs = page.getByRole('navigation', { name: 'Breadcrumb' }).filter({ visible: true }).getByRole('listitem');
  await expect(crumbs).toHaveText(['Design', 'Packages', 'demo', 'greeting']);
  await expect(page.getByRole('navigation', { name: 'Screens' }).getByRole('menuitem', { name: 'Packages', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.getByRole('tab', { name: 'Definition', exact: true }).click();
  await edit(page);
  await selectTarget(page, TEXT);
  await setParameters(page, 'constant', { Value: 'Hi, ' });
  await expect(node(page, /^Node function constant/)).toContainText('value Hi,');
  await save(page, 'greeting');

  // 3. Commit and deploy from the package's tab: the check swaps the flow greet, then the deploy
  await openDemo(page);
  await shown(page).getByRole('button', { name: 'Commit…', exact: true }).click();
  const commit = page.getByRole('dialog', { name: 'Commit to main' });
  await commit.getByLabel('Message', { exact: true }).fill('Greet with Hi');
  await commit.getByRole('button', { name: 'Commit and deploy' }).click();
  const deploy = page.getByRole('dialog', { name: /^Deploy [0-9a-f]{7} to / });
  await expect(deploy.getByRole('row', { name: /^Swaps/ })).toContainText('demo/greet');
  await deploy.getByRole('button', { name: 'Deploy', exact: true }).click();
  const head = git('rev-parse', 'HEAD').toString().trim();
  await expect(deploy).toContainText(`runs ${head.slice(0, 7)}, in place of`);
  await expect(deploy.getByRole('row', { name: /^Swapped/ })).toContainText('demo/greet');
  await deploy.getByText('Close', { exact: true }).click();

  // 4. the person sent again: the reply greets with Hi, and so does the payload Messages shows after the mapping
  const after = await request.post(greet, { data: PERSON });
  expect(after.status(), await after.text()).toBe(200);
  expect(await after.text()).toContain('<Text>Hi, Ann</Text>');

  await menu(page, 'Messages');
  await shown(page).getByRole('combobox', { name: 'Flow' }).click();
  await page.getByRole('option', { name: 'demo/greet', exact: true }).click();
  const list = shown(page).getByRole('table', { name: 'Messages' });
  // newest first: the top row is the message just sent
  await list.getByRole('row').filter({ hasText: 'Completed' }).first().getByRole('button').first().click();
  await expect(shown(page).getByRole('table', { name: 'Steps of attempt 1' })).toContainText('greeting');
  await shown(page).getByRole('button', { name: 'Show the payload after step 1 of attempt 1' }).click();
  await expect(shown(page).getByRole('region', { name: 'Payload after step 1 (greeting), attempt 1' })).toContainText('<Text>Hi, Ann</Text>');
  // the browser logs the 401 of the page before the login
  expect(problems.filter((problem) => problem !== 'Failed to load resource: the server responded with a status of 401 (Unauthorized)')).toEqual([]);
});
