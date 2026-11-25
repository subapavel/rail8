// Takes the pictures of the application's frame with the Playwright the repository already has: each scene at 1440×900
// and 1920×1080, in the light and the dark theme, over the fake world of scripts/pictures/world.ts and the fake mapping
// service. With --pro it also takes the matching pages of the Ant Design Pro demo, the reference the frame follows,
// which needs the network. Run it from ui/:
//
//   node scripts/pictures.mjs [--out <folder>] [--scene <name>[,<name>…]] [--pro]
//
// It builds the page scripts/pictures/index.html, serves the build on port 4175 and writes one PNG per picture to the
// folder (../docs/ui/frame by default), named <scene>-<width>x<height>-<theme>.png, and the demo's pages to its
// subfolder pro/, named <page>-<width>x<height>-<theme>.png.
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { chromium } from 'playwright';
import { build, preview } from 'vite';

const config = resolve(import.meta.dirname, 'pictures/vite.config.ts');
const { values } = parseArgs({
  options: { out: { type: 'string', default: '../docs/ui/frame' }, scene: { type: 'string' }, pro: { type: 'boolean', default: false } },
});

const SIZES = [
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
];
const THEMES = ['light', 'dark'];

/** Chooses the language `name`, as the language lists itself, with the header's language switch. */
const speak = (name) => async (page) => {
  await page.getByRole('button', { name: /^Language: / }).click();
  await page.getByRole('menuitem', { name, exact: true }).click();
};
/** Opens the screen `name` from the side menu. */
const menu = (name) => (page) => page.getByRole('navigation', { name: 'Screens' }).getByRole('menuitem', { name, exact: true }).click();
/** Opens the package `name` from its row on the Packages screen. */
const pkg = (name) => (page) => shown(page).getByRole('table', { name: 'Packages' }).getByRole('button', { name, exact: true }).click();
/** Chooses the page's own tab whose name starts with `name`. */
const tab = (name) => (page) => shown(page).getByRole('tab', { name: new RegExp(`^${name}`) }).click();
/** Expands the row of the table `table` that holds `text`. */
const expand = (table, text) => (page) =>
  shown(page).getByRole('table', { name: table }).getByRole('row').filter({ hasText: text }).getByRole('button', { name: /expand row/i }).click();
/** Presses the page's button `name`, in the shown tab or in a dialog. */
const press = (name) => (page) => page.getByRole('button', { name, exact: true }).click();
/** Collapses the side menu to its icons. */
const collapse = (page) => page.getByRole('button', { name: 'Collapse menu' }).click();
/** Opens the page again behind the session's gate, with `scene` as its `session` (out, first, in, none, admin or failed). */
const visit = (scene) => (page) => page.goto(`${page.url().split('?')[0]}?session=${scene}`);
/** Types `text` into the field labelled `label`. */
const fill = (label, text) => (page) => page.getByLabel(label, { exact: true }).fill(text);
/** Presses `action` in the row of the user `name` on the Users screen. */
const row = (name, action) => (page) =>
  shown(page).getByRole('table', { name: 'Users' }).getByRole('row').filter({ hasText: name }).first().getByRole('button', { name: action, exact: true }).click();
/** Presses `action` in the popover that asks about `name`. */
const confirmIn = (name, action) => (page) => page.getByRole('tooltip').filter({ hasText: `${name}?` }).getByRole('button', { name: action, exact: true }).click();
/** Ticks the checkbox labelled `label` in the open dialog. */
const check = (label) => (page) => page.getByRole('dialog').getByLabel(label, { exact: true }).check();
/** Ticks the row of `file` in the Changes table. */
const pick = (file) => (page) => shown(page).getByRole('table', { name: 'Changes' }).getByRole('checkbox', { name: `Choose ${file}`, exact: true }).check();
/** Ends the session of the page, so the login shows over its tabs. */
const end = (page) => page.evaluate(() => window.endSession());
/** Opens the message `id` from its row on the Messages screen. */
const message = (id) => (page) => shown(page).getByRole('table', { name: 'Messages' }).getByRole('button', { name: id, exact: true }).click();
/** Opens the first mapping of the Mappings screen in its studio. */
const studio = (page) => shown(page).getByRole('button', { name: 'Open in studio' }).first().click();

/** The steps that show each scene, from the application as it starts, with Home open. */
const SCENES = {
  home: [],
  packages: [menu('Packages')],
  'package-changes': [menu('Packages'), pkg('orders'), expand('Changes', 'tests/orders.test.yaml')],
  'package-artifacts': [menu('Packages'), pkg('orders'), tab('Artifacts')],
  'package-history': [menu('Packages'), pkg('orders'), tab('History')],
  'package-dependencies': [menu('Packages'), pkg('orders'), tab('Dependencies')],
  'package-used-by': [menu('Packages'), pkg('customers'), tab('Used by')],
  'package-deploy': [menu('Packages'), pkg('orders'), press('Deploy…')],
  'package-deployed': [menu('Packages'), pkg('orders'), press('Deploy…'), press('Deploy')],
  'package-commit': [menu('Packages'), pkg('orders'), press('Commit…')],
  'package-discard': [menu('Packages'), pkg('orders'), pick('flows/order-to-idoc.xsl'), pick('tests/legacy.test.yaml'), pick('fixtures/order-4711.xml'), press('Discard changes…'), fill('Type 3 to discard 3 files. They cannot be brought back.', '3')],
  'package-discard-one': [menu('Packages'), pkg('orders'), pick('mappings/order-to-idoc.mapping.json'), press('Discard changes…')],
  'package-push-refused': [menu('Packages'), pkg('orders'), press('Push')],
  'packages-get': [menu('Packages'), press('Get a package…'), fill('Address', 'https://git.example.com/acme/invoices.git')],
  'packages-get-refused': [menu('Packages'), press('Get a package…'), fill('Address', 'https://git.example.com/acme/payroll.git'), press('Get')],
  'packages-got': [menu('Packages'), press('Get a package…'), fill('Address', 'https://git.example.com/acme/invoices.git'), press('Get')],
  'packages-new': [menu('Packages'), press('New package'), fill('Repository', 'invoices'), fill('Package name', 'Invoices')],
  'packages-new-refused': [menu('Packages'), press('New package'), fill('Repository', 'orders'), fill('Package name', 'orders'), press('Create')],
  'menu-collapsed': [menu('Packages'), pkg('orders'), collapse],
  canvas: [menu('Mappings'), studio, tab('Definition')],
  login: [visit('out')],
  'login-refused': [visit('out'), fill('Name', 'anna'), fill('Password', 'not the password'), press('Log in')],
  'first-password': [visit('first')],
  'session-home': [visit('in')],
  'session-asking': [visit('in'), menu('Packages'), end],
  'session-failed': [visit('failed')],
  'session-refused-tab': [visit('in&start=mappings')],
  'session-no-body': [visit('in'), menu('Messages'), message('m-7f3a')],
  'message-no-audit': [visit('in'), menu('Messages'), message('m-7f39')],
  'session-no-deployments': [visit('none'), menu('Packages')],
  'session-no-deployment': [visit('none'), menu('Packages'), pkg('orders')],
  users: [visit('admin'), menu('Users'), expand('Users', 'anna')],
  'users-roles': [visit('admin'), menu('Users'), row('alice', 'Roles…'), check('Message bodies')],
  'users-new': [visit('admin'), menu('Users'), press('New user'), fill('Name', 'dana'), check('Operator'), press('Create')],
  'users-refused': [visit('admin'), menu('Users'), row('alice', 'Block'), confirmIn('alice', 'Block')],
  'users-tokens': [visit('admin'), menu('Users'), expand('Users', 'ci-deploy')],
  'users-token-new': [visit('admin'), menu('Users'), row('ci-deploy', 'New token…'), fill('Name', 'release')],
  'my-tokens': [visit('admin'), menu('My tokens')],
  'my-tokens-new': [visit('admin'), menu('My tokens'), press('New token'), fill('Name', 'build-server'), fill('Your password', 'alice password')],
  'my-tokens-issued': [visit('admin'), menu('My tokens'), press('New token'), fill('Name', 'build-server'), fill('Your password', 'alice password'), press('Issue')],
  'my-tokens-wrong-password': [visit('admin'), menu('My tokens'), press('New token'), fill('Name', 'build-server'), fill('Your password', 'not it'), press('Issue')],
  tokens: [visit('admin'), menu('Tokens')],
  audit: [visit('admin'), menu('Audit')],
  'audit-filtered': [visit('admin'), menu('Audit'), fill('User', 'anna'), press('Query')],
  'tokens-ceiling': [visit('admin'), menu('Tokens'), press('Change…'), fill('Longest life of a token', '30')],
  'login-de': [visit('out'), speak('Deutsch')],
  'login-cs': [visit('out'), speak('Čeština')],
  'login-ru': [visit('out'), speak('Русский')],
  'users-de': [visit('admin'), menu('Users'), expand('Users', 'anna'), speak('Deutsch')],
  'users-cs': [visit('admin'), menu('Users'), expand('Users', 'anna'), speak('Čeština')],
  'users-ru': [visit('admin'), menu('Users'), expand('Users', 'anna'), speak('Русский')],
  endpoints: [menu('Endpoints')],
  'endpoints-cs': [menu('Endpoints'), speak('Čeština')],
  flow: [menu('Flows'), (page) => shown(page).getByRole('button', { name: 'orders', exact: true }).click()],
  'flow-de': [menu('Flows'), (page) => shown(page).getByRole('button', { name: 'orders', exact: true }).click(), speak('Deutsch')],
  'flow-cs': [menu('Flows'), (page) => shown(page).getByRole('button', { name: 'orders', exact: true }).click(), speak('Čeština')],
  'flow-ru': [menu('Flows'), (page) => shown(page).getByRole('button', { name: 'orders', exact: true }).click(), speak('Русский')],
  'git-refused-home': [],
  'git-refused-packages': [menu('Packages')],
};

/** What the node answers for its check of the git credentials in the scenes whose name starts with `git-refused`. */
const REFUSED = {
  credentials: 'REFUSED',
  repository: 'orders',
  reason: 'orders at refs/heads/release: credentials refused',
  checkedAt: '2026-11-24T08:15:00Z',
};

/** The page of the Ant Design Pro demo that matches each picture taken of it, and whether its menu is collapsed. */
const PRO = {
  workplace: { path: '/dashboard/workplace' },
  'table-list': { path: '/list/table-list' },
  'profile-advanced': { path: '/profile/advanced' },
  'profile-advanced-collapsed': { path: '/profile/advanced', collapsed: true },
};
const DEMO = 'https://preview.pro.ant.design';

/** The panel of the workspace tab that is shown. */
const shown = (page) => page.locator('.rail8-panel').filter({ visible: true });

/** Waits until nothing on `page` is loading and its fonts are in. */
async function settled(page) {
  await page.locator('.ant-spin-spinning, .ant-skeleton-active').first().waitFor({ state: 'detached', timeout: 10_000 }).catch(() => {});
  await page.evaluate(() => document.fonts.ready);
  // the menu and the tabs move their ink bar and width in a short transition
  await page.waitForTimeout(400);
}

/** Takes the pictures of `scenes` of the application served at `base` into `out`. */
async function application(browser, base, scenes, out) {
  for (const scene of scenes) {
    for (const size of SIZES) {
      for (const theme of THEMES) {
        const context = await browser.newContext({ viewport: size, deviceScaleFactor: 1, colorScheme: theme });
        if (scene.startsWith('git-refused')) await context.route('**/admin/git', (route) => route.fulfill({ json: REFUSED }));
        const page = await context.newPage();
        await page.goto(`${base}scripts/pictures/index.html`);
        await settled(page);
        for (const step of SCENES[scene]) {
          await step(page);
          await settled(page);
        }
        // the pointer rests on an empty corner of the content, so no control shows its hover
        await page.mouse.move(size.width - 1, size.height - 1);
        await settled(page);
        const file = `${scene}-${size.width}x${size.height}-${theme}.png`;
        await page.screenshot({ path: resolve(out, file), animations: 'disabled' });
        console.log(file);
        await context.close();
      }
    }
  }
}

/** Takes the pictures of the Ant Design Pro demo into `out`, signed in with the account its sign-in page shows. */
async function pro(out) {
  const proxy = process.env.HTTPS_PROXY ?? process.env.https_proxy;
  const browser = await chromium.launch(proxy ? { proxy: { server: proxy } } : {});
  try {
    for (const size of SIZES) {
      for (const theme of THEMES) {
        const page = await browser.newPage({ viewport: size, deviceScaleFactor: 1 });
        await page.goto(`${DEMO}/user/login`, { waitUntil: 'domcontentloaded', timeout: 60_000 });
        await page.locator('#username').fill('admin');
        await page.locator('#password').fill('ant.design');
        await page.getByRole('button', { name: 'Login' }).click();
        await page.waitForURL((url) => !url.pathname.includes('/user/login'), { timeout: 30_000 });
        for (const [name, { path, collapsed }] of Object.entries(PRO)) {
          await page.goto(`${DEMO}${path}`, { waitUntil: 'domcontentloaded', timeout: 60_000 });
          await page.locator('.ant-pro-layout-content').waitFor({ timeout: 30_000 });
          if (theme === 'dark') await darken(page);
          if (collapsed) await page.locator('.ant-pro-sider-collapsed-button').click();
          // the demo draws its charts and tables after it loads
          await page.waitForTimeout(4000);
          const file = `${name}-${size.width}x${size.height}-${theme}.png`;
          await page.screenshot({ path: resolve(out, file), animations: 'disabled' });
          console.log(`pro/${file}`);
        }
        await page.close();
      }
    }
  } finally {
    await browser.close();
  }
}

/** Chooses the dark page style in the demo's settings drawer, then closes the drawer by a click beside it. */
async function darken(page) {
  await page.locator('.ant-pro-setting-drawer-handle').click();
  await page.locator('.ant-pro-setting-drawer-block-checkbox-item-realDark').click();
  const mask = page.locator('.ant-drawer-mask');
  await mask.click({ position: { x: 8, y: 8 } });
  await mask.waitFor({ state: 'hidden' });
}

const wanted = values.scene === undefined ? Object.keys(SCENES) : values.scene.split(',').filter((name) => name !== '');
const unknown = wanted.filter((name) => !(name in SCENES));
if (unknown.length > 0) throw new Error(`no scene ${unknown.join(', ')}: the scenes are ${Object.keys(SCENES).join(', ')}`);

const out = resolve(process.cwd(), values.out);
await mkdir(out, { recursive: true });
await build({ configFile: config, logLevel: 'warn' });
const server = await preview({ configFile: config });
const browser = await chromium.launch();
try {
  await application(browser, server.resolvedUrls.local[0], wanted, out);
} finally {
  await browser.close();
  await new Promise((done) => server.httpServer.close(done));
}
if (values.pro) {
  await mkdir(resolve(out, 'pro'), { recursive: true });
  await pro(resolve(out, 'pro'));
}
