import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';
import { deployPort, deployWorkspace } from './stack-workspace';

test.use({ viewport: { width: 1440, height: 900 }, baseURL: `http://localhost:${deployPort}` });

const NAME = 'e2e-deploy';
const repository = join(deployWorkspace, NAME);
const admin = process.env.RAIL8_ADMIN_URL ?? 'http://127.0.0.1:8081';
const token = process.env.RAIL8_TOKEN ?? '';
// the node's HTTP port, where the quickstart publishes every inbound HTTP endpoint
const inbound = 'http://127.0.0.1:8080';

/** The package's files: a sync flow behind its own HTTP endpoint, which answers with what its one jq step makes. */
const FILES: Record<string, string> = {
  'package.yaml': `schemaVersion: 1\nkind: package\nname: ${NAME}\nversion: 0.1.0\ndescription: The deploy scenario's package.\ndependencies: []\n`,
  'endpoints/e2e-deploy-in.yaml': 'schemaVersion: 1\nkind: endpoint\nid: e2e-deploy-in\nscheme: http\ndirection: in\nconfig:\n  address: /e2e-deploy\n',
  'flows/greet.yaml': [
    'schemaVersion: 1',
    'kind: flow',
    'id: greet',
    'version: 0.1.0',
    'description: Greets the name it is sent.',
    'from:',
    '  endpoint: e2e-deploy-in',
    'delivery:',
    '  mode: sync',
    'recording:',
    '  level: all',
    '  payload: keep',
    'limits:',
    '  maxBodyBytes: 1048576',
    'steps:',
    '  - type: jq',
    '    name: greeting',
    `    filter: '{greeting: ("hello " + .name)}'`,
    '',
  ].join('\n'),
};

const git = (...args: string[]) => execFileSync('git', args, { cwd: repository, stdio: 'pipe' });

// A fresh repository of the package in the quickstart's working folder, with one commit, and the package undeployed from
// the node, so that each run starts where the last one did not leave anything.
test.beforeAll(async ({ request }) => {
  const undeployed = await request.delete(`${admin}/admin/packages/${NAME}`, { headers: { authorization: `Bearer ${token}` } });
  expect([200, 204, 404], await undeployed.text()).toContain(undeployed.status());
  rmSync(repository, { recursive: true, force: true });
  for (const [path, text] of Object.entries(FILES)) {
    mkdirSync(dirname(join(repository, path)), { recursive: true });
    writeFileSync(join(repository, path), text);
  }
  git('init', '-q', '-b', 'main');
  git('config', 'user.name', 'Stack Scenario');
  git('config', 'user.email', 'stack@example.com');
  git('add', '-A');
  git('commit', '-q', '-m', 'Start the deploy scenario package');
});

test('@stack the real node: edit a flow, commit it, deploy the commit, and a message runs through the new flow', async ({ page, request }) => {
  const problems = collectProblems(page);
  await page.goto('/');

  // edit the flow, from the Artifacts of its package's tab: its jq step now says hi
  await page.getByRole('menuitem', { name: 'Packages' }).click();
  await page.getByRole('table', { name: 'Packages' }).getByRole('button', { name: NAME, exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: NAME })).toBeVisible();
  await page.getByRole('tab', { name: /^Artifacts \(\d+\)$/ }).click();
  await page.getByRole('table', { name: 'Artifacts' }).getByRole('button', { name: 'greet', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'greet' })).toBeVisible();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByTestId('flow-canvas').getByText('greeting', { exact: true }).click();
  await page.getByRole('textbox', { name: 'filter', exact: true }).fill('{greeting: ("hi " + .name)}');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();

  // commit it and deploy the commit, in the package's tab: the check first, then the deploy
  await page.getByRole('tab', { name: NAME, exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: NAME })).toBeVisible();
  await page.getByRole('button', { name: 'Commit…', exact: true }).click();
  const commit = page.getByRole('dialog', { name: 'Commit to main' });
  await commit.getByLabel('Message', { exact: true }).fill('Say hi');
  await commit.getByRole('button', { name: 'Commit and deploy' }).click();

  const deploy = page.getByRole('dialog', { name: /^Deploy [0-9a-f]{7} to / });
  await expect(deploy.getByText(/^The check passed\. .* runs nothing of this repository yet\.$/)).toBeVisible();
  await expect(deploy.getByRole('row', { name: /^Adds/ })).toContainText(`${NAME}/greet`);
  await deploy.getByRole('button', { name: 'Deploy', exact: true }).click();
  const head = git('rev-parse', 'HEAD').toString().trim();
  await expect(deploy.getByText(new RegExp(`runs ${head.slice(0, 7)}\\.$`))).toBeVisible();
  await deploy.getByText('Close', { exact: true }).click();
  await expect(page.getByLabel(NAME, { exact: true }).getByText('Deployed', { exact: true }).last()).toBeVisible();

  // a message through the flow as the node now runs it
  const answer = await request.post(`${inbound}/http/e2e-deploy`, { data: { name: 'Ann' } });
  expect(answer.status(), await answer.text()).toBe(200);
  expect(await answer.json()).toEqual({ greeting: 'hi Ann' });
  expect(problems).toEqual([]);
});
