import { useEffect } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { page } from 'vitest/browser';
import { chooseLanguage, currentLanguage } from '../language';
import type { Screen } from '../screens';
import { Shell } from '../Shell';
import { useWorkingFiles, type WorkingFiles } from '../workingFiles';
import { EndpointsError, type EndpointFile, type EndpointsApi, type NodeComponents } from './api';
import { filesScreen } from './fake/files';
import { endpointTab } from './screen';
import { HTTP_FORMS, SFTP_FORMS } from './fake/forms';
import type { ComponentForms } from './settings';
import { opened } from '../motion-test';

const drop: ComponentForms = {
  format: 2,
  reach: { in: 'connection', out: 'connection' },
  described: { connection: true, flow: true },
  tabs: [
    { id: 'general', label: 'General', common: true },
    { id: 'polling', label: 'Polling', common: false },
    { id: 'retry', label: 'Retry', common: true },
    { id: 'recording', label: 'Recording', common: true },
    { id: 'security', label: 'Security', common: true },
  ],
  connection: [
    { name: 'host', type: 'text', label: 'Host', help: 'The server.', tab: 'general', required: true, directions: ['in', 'out'] },
    { name: 'port', type: 'integer', label: 'Port', help: "The server's port.", tab: 'general', required: false, directions: ['in', 'out'], default: 22, min: 1, max: 65535 },
    { name: 'password', type: 'secret', label: 'Password', help: 'The password.', tab: 'security', required: false, directions: ['in', 'out'] },
    { name: 'url', type: 'text', label: 'URL', help: 'Where a producer sends.', tab: 'general', required: false, directions: ['out'] },
  ],
  flow: [
    {
      name: 'afterSuccess',
      type: 'choice',
      label: 'After success',
      help: 'What happens to a delivered file.',
      tab: 'polling',
      required: false,
      directions: ['in'],
      default: 'delete',
      choices: [
        { value: 'delete', label: 'Delete' },
        { value: 'move', label: 'Move' },
      ],
    },
    { name: 'moveTo', type: 'text', label: 'Move to', help: 'Where a delivered file goes.', tab: 'polling', required: true, directions: ['in'], visibleWhen: { field: 'afterSuccess', values: ['move'] } },
  ],
  rules: [],
};

const inbox: EndpointFile = {
  path: 'demo/endpoints/inbox.yaml',
  editable: true,
  id: 'inbox',
  scheme: 'drop',
  direction: 'in',
  description: 'The drop of the demo.',
  config: { host: 'sftp', password: 'secret://sftp-password', colour: 'red' },
  environments: [{ name: 'prod', options: ['host'], config: { host: 'sftp-prod' } }],
};

/** A component that takes requests on the node's HTTP port under `/http`, at the address its in endpoints set. */
const web: ComponentForms = {
  format: 2,
  reach: { in: 'none', out: 'connection' },
  described: { connection: true, flow: true },
  tabs: [{ id: 'general', label: 'General', common: true }],
  connection: [{ name: 'url', type: 'text', label: 'URL', help: 'Where a producer sends.', tab: 'general', required: true, directions: ['out'] }],
  flow: [{ name: 'address', type: 'text', label: 'Address', help: 'The path requests arrive at.', tab: 'general', required: false, directions: ['in'] }],
  rules: [],
};

const httpIn: EndpointFile = {
  path: 'demo/endpoints/http-in.yaml',
  editable: true,
  id: 'http-in',
  scheme: 'web',
  direction: 'in',
  description: null,
  config: { address: '/in' },
  environments: [{ name: 'prod', options: ['address'], config: { address: '/orders' } }],
};

const components: NodeComponents = {
  environment: 'dev',
  httpOrigin: 'http://localhost:8080',
  items: [
    { scheme: 'drop', httpPrefix: null, settings: drop },
    { scheme: 'raw', httpPrefix: null, settings: { format: 2, reach: { in: 'connection', out: 'connection' }, described: { connection: false, flow: false }, tabs: drop.tabs.filter((t) => t.common), connection: [], flow: [], rules: [] } },
    { scheme: 'web', httpPrefix: '/http', settings: web },
    { scheme: 'http', httpPrefix: '/http', settings: HTTP_FORMS },
    { scheme: 'sftp', httpPrefix: null, settings: SFTP_FORMS },
  ],
};

/** An API over one working folder holding `files`; every call can be replaced in a test. */
function fakeApi(files: EndpointFile[] = [inbox], overrides: Partial<EndpointsApi> = {}): EndpointsApi {
  const byPath = new Map(files.map((f) => [f.path, f]));
  return {
    list: vi.fn(async () => ({
      configured: true,
      editable: files.every((f) => f.editable),
      truncated: false,
      items: [...files.map(({ path, id, scheme, direction }) => ({ path, id, scheme, direction })), { path: 'demo/endpoints/broken.yaml', problem: 'an endpoint file names its id and scheme' }],
    })),
    read: vi.fn(async (path: string) => ({ file: byPath.get(path)!, etag: '"v1"' })),
    save: vi.fn(async (path: string, config) => ({ file: { ...byPath.get(path)!, config }, etag: '"v2"' })),
    create: vi.fn(),
    components: vi.fn(async () => components),
    texts: vi.fn(async () => null),
    ...overrides,
  };
}

let root: Root | undefined;
let host: HTMLElement | undefined;

let working: WorkingFiles | undefined;

/** Gives the test the working files of the shell it is in. */
function Probe() {
  const files = useWorkingFiles();
  useEffect(() => {
    working = files;
  });
  return null;
}

/** `screen` with the probe of the working files beside what it shows. */
const probed = (screen: Screen): Screen => ({ ...screen, render: (open) => <><Probe />{screen.render(open)}</> });

function render(api: EndpointsApi) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={[probed(filesScreen(() => api.list(), (path, id) => endpointTab(api, path, id)))]} start="files" />);
}

afterEach(() => {
  root?.unmount();
  host?.remove();
  working = undefined;
});

const field = (name: string) => page.getByLabelText(name, { exact: false });
const text = (shown: string) => page.getByText(shown, { exact: false });
const button = (name: string) => page.getByRole('button', { name, exact: true });
const settingsTab = (name: string) => page.getByRole('tab', { name, exact: true });

async function open(api: EndpointsApi, id = 'inbox') {
  render(api);
  await button(id).click();
  await expect.element(page.getByRole('heading', { name: id, level: 1 })).toBeVisible();
}

describe('Endpoints', () => {
  test("opens read-only, with the platform's tabs around the adapter's own and the fields of the endpoint's direction", async () => {
    await open(fakeApi());
    for (const name of ['General', 'Polling', 'Retry', 'Recording', 'Security']) await expect.element(settingsTab(name)).toBeVisible();
    await expect.element(field('Host')).toHaveValue('sftp');
    await expect.element(field('Host')).toHaveAttribute('readonly');
    await expect.element(field('Port')).toHaveAttribute('placeholder', 'Default: 22');
    await expect.element(text('Where a producer sends.')).not.toBeInTheDocument();
    await expect.element(field('colour')).toHaveValue('"red"');
    await expect.element(page.getByText(/^no such setting/)).toBeVisible();
    await expect.element(text('Environments override some of these settings')).toBeVisible();
    await settingsTab('Security').click();
    await expect.element(field('Password')).toHaveAttribute('type', 'password');
    await settingsTab('Retry').click();
    await expect.element(page.getByRole('tabpanel', { name: 'Retry' }).getByText('No settings on this tab for an in endpoint.')).toBeVisible();
    await expect.element(button('Edit')).toBeVisible();
  });

  test('two forms open at once each keep their own labelled fields', async () => {
    const outbox: EndpointFile = { ...inbox, id: 'outbox', path: 'demo/endpoints/outbox.yaml', direction: 'out', config: { host: 'partner' }, environments: [] };
    await open(fakeApi([inbox, outbox]));
    await page.getByRole('tab', { name: 'Files', exact: true }).click();
    await button('outbox').click();
    const panel = page.getByRole('tabpanel', { name: 'outbox' });
    await expect.element(panel.getByLabelText('Host', { exact: false })).toHaveValue('partner');
    await expect.element(panel.getByLabelText('URL', { exact: false })).toBeVisible();
  });

  test('offers no Edit when the working folder is read-only', async () => {
    await open(fakeApi([{ ...inbox, editable: false }]));
    await expect.element(page.getByRole('tabpanel', { name: 'inbox' }).getByText('Read-only')).toBeVisible();
    await expect.element(field('Host')).toHaveValue('sftp');
    expect(button('Edit').elements()).toHaveLength(0);
  });

  test('shows a field while the choice it depends on has its value, and Check names what is missing', async () => {
    await open(fakeApi());
    await button('Edit').click();
    await settingsTab('Polling').click();
    await expect.element(text('Where a delivered file goes.')).not.toBeInTheDocument();
    await field('After success').click();
    await opened(page.getByRole('option', { name: 'Move', exact: true }));
    await page.getByRole('option', { name: 'Move', exact: true }).click();
    await expect.element(field('Move to')).toBeVisible();
    await button('Check').click();
    await expect.element(text('Check found issues')).toBeVisible();
    await expect.element(text('moveTo is required when afterSuccess is move').first()).toBeVisible();
    await field('Move to').fill('/done');
    await button('Check').click();
    await expect.element(page.getByRole('listitem').filter({ hasText: 'colour no such setting' })).toBeVisible();
  });

  test('saves only the options, with the version it read, and keeps editing', async () => {
    const api = fakeApi();
    await open(api);
    await button('Edit').click();
    await field('Port').fill('2222');
    await field('Host').fill('');
    await button('Save').click();
    expect(api.save).toHaveBeenCalledWith('demo/endpoints/inbox.yaml', { password: 'secret://sftp-password', colour: 'red', port: 2222 }, '"v1"');
    await expect.element(text('Endpoint inbox saved')).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
  });

  test('a file changed on disk since it was read opens a dialog, and Reload shows it as it is now', async () => {
    let reads = 0;
    const api = fakeApi([inbox], {
      read: vi.fn(async () => ({ file: reads++ === 0 ? inbox : { ...inbox, config: { host: 'elsewhere' } }, etag: `"v${reads}"` })),
      save: vi.fn(async () => Promise.reject(new EndpointsError(412, 'the file changed since it was read; nothing was saved'))),
    });
    await open(api);
    await button('Edit').click();
    await field('Host').fill('other');
    await button('Save').click();
    await expect.element(text('The file changed on disk')).toBeVisible();
    await opened(button('Reload'));
    await button('Reload').click();
    await expect.element(field('Host')).toHaveValue('elsewhere');
    await expect.element(button('Edit')).toBeVisible();
  });

  test('gives the shell its file, with unsaved changes while its draft differs, and reads it again when the shell asks while it has none', async () => {
    let reads = 0;
    const api = fakeApi([inbox], { read: vi.fn(async () => ({ file: reads++ === 0 ? inbox : { ...inbox, config: { host: 'elsewhere' } }, etag: `"v${reads}"` })) });
    await open(api);
    await expect.poll(() => working?.files().get('demo/endpoints/inbox.yaml')?.dirty).toBe(false);

    await button('Edit').click();
    await field('Host').fill('other');
    expect(working!.files().get('demo/endpoints/inbox.yaml')?.dirty).toBe(true);
    working!.reload(['demo/endpoints/inbox.yaml']);
    await expect.element(field('Host')).toHaveValue('other');
    expect(api.read).toHaveBeenCalledTimes(1);

    await button('Cancel').click();
    await opened(button('Discard changes'));
    await button('Discard changes').click();
    working!.reload(['demo/endpoints/inbox.yaml']);
    await expect.element(field('Host')).toHaveValue('elsewhere');
  });

  test('text that is not a value yet counts as unsaved: the shell hears of it, and a reload leaves it', async () => {
    const raw: EndpointFile = { ...inbox, id: 'raw-in', path: 'demo/endpoints/raw.yaml', scheme: 'raw', environments: [], config: { a: 1 } };
    const api = fakeApi([raw]);
    await open(api, 'raw-in');
    await button('Edit').click();
    await field('Settings').fill('{"a": ');
    await expect.element(text('is not a JSON object')).toBeVisible();

    expect(working!.files().get('demo/endpoints/raw.yaml')?.dirty).toBe(true);
    working!.reload(['demo/endpoints/raw.yaml']);
    await expect.element(field('Settings')).toHaveValue('{"a": ');
    expect(api.read).toHaveBeenCalledTimes(1);
  });

  test('Edit waits for the read a reload started, so the draft starts from the file the read gives, with its tag', async () => {
    let answer: ((read: { file: EndpointFile; etag: string }) => void) | undefined;
    let reads = 0;
    const api = fakeApi([inbox], {
      read: vi.fn(async () => (reads++ === 0 ? { file: inbox, etag: '"v1"' } : new Promise<{ file: EndpointFile; etag: string }>((done) => (answer = done)))),
    });
    await open(api);
    await expect.poll(() => working?.files().get('demo/endpoints/inbox.yaml')?.dirty).toBe(false);

    working!.reload(['demo/endpoints/inbox.yaml']);
    await expect.element(button('Edit')).toBeDisabled();
    answer!({ file: { ...inbox, config: { ...inbox.config, host: 'elsewhere' } }, etag: '"v2"' });
    await expect.element(button('Edit')).toBeEnabled();
    await button('Edit').click();
    await field('Host').fill('other');
    await button('Save').click();

    expect(api.save).toHaveBeenCalledWith('demo/endpoints/inbox.yaml', expect.objectContaining({ host: 'other' }), '"v2"');
  });

  test('Cancel and closing the tab with unsaved changes ask first', async () => {
    const api = fakeApi();
    await open(api);
    await button('Edit').click();
    await field('Host').fill('other');
    await button('Cancel').click();
    await opened(button('Keep editing'));
    await button('Keep editing').click();
    await expect.element(field('Host')).toHaveValue('other');
    await button('Close inbox').click();
    await opened(page.getByRole('dialog', { name: 'Save changes to inbox?' }));
    await button('Close without saving').click();
    await expect.element(page.getByRole('heading', { name: 'inbox', level: 1 })).not.toBeInTheDocument();
    expect(api.save).not.toHaveBeenCalled();
  });

  test('Save and close saves the settings as they are now, then closes the tab', async () => {
    const api = fakeApi();
    await open(api);
    await button('Edit').click();
    await field('Host').fill('other');
    await button('Close inbox').click();
    await opened(button('Save and close'));
    await button('Save and close').click();
    await expect.element(page.getByRole('heading', { name: 'inbox', level: 1 })).not.toBeInTheDocument();
    expect(api.save).toHaveBeenCalledWith('demo/endpoints/inbox.yaml', expect.objectContaining({ host: 'other' }), '"v1"');
  });

  test('a save that fails when the tab closes keeps it open and says why', async () => {
    await open(fakeApi([inbox], { save: vi.fn(async () => Promise.reject(new EndpointsError(500, 'the disk is full'))) }));
    await button('Edit').click();
    await field('Host').fill('other');
    await button('Close inbox').click();
    await opened(button('Save and close'));
    await button('Save and close').click();
    await expect.element(text('The file was not saved')).toBeVisible();
    await expect.element(text('the disk is full')).toBeVisible();
    await expect.element(field('Host')).toHaveValue('other');
  });

  test("shows the connection's fields and the flow's in two titled groups, each field with its label, its name and its help", async () => {
    const erp: EndpointFile = { ...inbox, id: 'erp-out', path: 'demo/endpoints/erp-out.yaml', scheme: 'http', direction: 'out', config: { url: 'https://erp.example/idoc', path: '/orders' }, environments: [] };
    await open(fakeApi([erp]), 'erp-out');
    const connection = page.getByRole('form', { name: 'Connection' });
    const flow = page.getByRole('form', { name: 'In the flow' });
    await expect.element(page.getByRole('heading', { name: 'Connection', level: 2 })).toBeVisible();
    await expect.element(connection.getByRole('textbox', { name: 'URL' })).toHaveValue('https://erp.example/idoc');
    await expect.element(connection.getByText('timeoutMs', { exact: true })).toBeVisible();
    await expect.element(connection.getByRole('textbox', { name: 'Largest reply' })).toBeVisible();
    await expect.element(connection.getByText('The largest reply body taken, in bytes.')).toBeVisible();
    await expect.element(flow.getByRole('textbox', { name: 'Path' })).toHaveValue('/orders');
    await expect.element(flow.getByRole('textbox', { name: 'Address' })).not.toBeInTheDocument();
    await expect.element(flow.getByRole('textbox', { name: 'URL' })).not.toBeInTheDocument();
    await expect.element(connection.getByRole('textbox', { name: 'Path' })).not.toBeInTheDocument();
    await settingsTab('Retry').click();
    await expect.element(page.getByRole('tabpanel', { name: 'Retry' }).getByText('No settings on this tab for an out endpoint.')).toBeVisible();
  });

  test('shows no group that has no field for the endpoint', async () => {
    const orders: EndpointFile = { ...inbox, id: 'orders-in', path: 'demo/endpoints/orders-in.yaml', scheme: 'http', direction: 'in', config: { address: '/orders' }, environments: [] };
    await open(fakeApi([orders]), 'orders-in');
    await expect.element(page.getByRole('form', { name: 'In the flow' }).getByRole('textbox', { name: 'Address' })).toHaveValue('/orders');
    await expect.element(page.getByRole('heading', { name: 'Connection', level: 2 })).not.toBeInTheDocument();
  });

  test("Check takes a user name in the connection's URL, and holds the flow's fields and the reply's limit to their rules", async () => {
    const erp: EndpointFile = { ...inbox, id: 'erp-out', path: 'demo/endpoints/erp-out.yaml', scheme: 'http', direction: 'out', config: { url: 'https://anna:pw@erp.example/idoc', path: 'orders', maxBodyBytes: 0 }, environments: [] };
    await open(fakeApi([erp]), 'erp-out');
    await button('Edit').click();
    await button('Check').click();
    const issues = page.getByRole('list', { name: 'Issues' });
    await expect.element(issues.getByText('path must match /.*')).toBeVisible();
    await expect.element(issues.getByText('maxBodyBytes must be a whole number of at least 1')).toBeVisible();
    expect(issues.getByText(/^url/).elements()).toHaveLength(0);
  });

  test('an endpoint of a direction its component does not serve shows its settings as written, with a warning', async () => {
    const files: EndpointFile = { ...inbox, id: 'files-out', path: 'demo/endpoints/files-out.yaml', scheme: 'sftp', direction: 'out', config: { host: 'sftp' }, environments: [] };
    await open(fakeApi([files]), 'files-out');
    await expect.element(text('The sftp adapter does not serve out endpoints.')).toBeVisible();
    await expect.element(field('Settings')).toHaveValue('{\n  "host": "sftp"\n}');
  });

  test("without the component's description the options are shown as JSON, read-only, with the reason", async () => {
    await open(fakeApi([inbox], { components: vi.fn(async () => Promise.reject(new EndpointsError(502, 'the node does not answer'))) }));
    await expect.element(text("The node's components could not be read: the node does not answer")).toBeVisible();
    await expect.element(field('Settings')).toHaveAttribute('readonly');
    expect(button('Edit').elements()).toHaveLength(0);
  });

  test('a component that describes nothing gets a raw editor of all its options', async () => {
    const raw: EndpointFile = { ...inbox, id: 'raw-in', path: 'demo/endpoints/raw.yaml', scheme: 'raw', environments: [], config: { a: 1 } };
    const api = fakeApi([raw]);
    await open(api, 'raw-in');
    await button('Edit').click();
    await field('Settings').fill('{"a": ');
    await expect.element(text('is not a JSON object')).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
    await field('Settings').fill('{"a": 2, "b": true}');
    await button('Save').click();
    expect(api.save).toHaveBeenCalledWith('demo/endpoints/raw.yaml', { a: 2, b: true }, '"v1"');
  });

  test('Save and close with a setting that is not valid saves nothing and keeps the tab open, saying why', async () => {
    const raw: EndpointFile = { ...inbox, id: 'raw-in', path: 'demo/endpoints/raw.yaml', scheme: 'raw', environments: [], config: { a: 1 } };
    const api = fakeApi([raw]);
    await open(api, 'raw-in');
    await button('Edit').click();
    await field('Settings').fill('{"a": 2}');
    await field('Settings').fill('{"a": ');
    await button('Close raw-in').click();
    await opened(button('Save and close'));
    await button('Save and close').click();
    await expect.element(text('Some settings are not valid. Correct them, then save.')).toBeVisible();
    await expect.element(page.getByRole('tab', { name: 'raw-in', exact: true })).toHaveAttribute('aria-selected', 'true');
    expect(api.save).not.toHaveBeenCalled();
  });

  test("shows under an in endpoint's address a preview of the URL senders call, which follows the draft", async () => {
    await open(fakeApi([httpIn]), 'http-in');
    const address = page.getByRole('textbox', { name: 'Address' });
    await expect.element(text('Senders call http://localhost:8080/http/in.')).toBeVisible();
    await expect.element(text('Preview')).toBeVisible();
    await expect.element(text('in environment dev, not from what is deployed')).toBeVisible();
    await button('Edit').click();
    await address.fill('/invoices');
    await expect.element(text('Senders call http://localhost:8080/http/invoices.')).toBeVisible();
    await address.fill('');
    await expect.element(text('Senders call')).not.toBeInTheDocument();
  });

  test("shows the path alone when the node names no origin, and the address the node's environment sets", async () => {
    await open(fakeApi([httpIn], { components: vi.fn(async () => ({ ...components, httpOrigin: null })) }), 'http-in');
    await expect.element(text("The path /http/in on the node's HTTP port")).toBeVisible();
    await expect.element(text('RAIL8_HTTP_ORIGIN')).toBeVisible();
    root?.unmount();
    host?.remove();
    await open(fakeApi([httpIn], { components: vi.fn(async () => ({ ...components, environment: 'prod' })) }), 'http-in');
    await expect.element(text('Senders call http://localhost:8080/http/orders.')).toBeVisible();
    await expect.element(text('with the address environment prod sets')).toBeVisible();
  });

  test('shows no URL for an out endpoint, nor for a component with no HTTP prefix', async () => {
    await open(fakeApi([{ ...httpIn, direction: 'out' }]), 'http-in');
    await expect.element(text('Senders call')).not.toBeInTheDocument();
    root?.unmount();
    host?.remove();
    await open(fakeApi([httpIn], { components: vi.fn(async () => ({ ...components, items: [{ scheme: 'web', httpPrefix: null, settings: web }] })) }), 'http-in');
    await expect.element(field('Address')).toHaveValue('/in');
    await expect.element(text('Senders call')).not.toBeInTheDocument();
  });
});

describe('the endpoint editor in another language', () => {
  afterEach(() => {
    chooseLanguage('en');
    localStorage.removeItem('rail8.language');
  });

  test('shows its form and the check in the language chosen in the header', async () => {
    render(fakeApi());
    await expect.element(button('inbox')).toBeVisible();
    await page.getByRole('button', { name: 'Language: English' }).click();
    await opened(page.getByRole('menuitem', { name: 'Čeština' }));
    await page.getByRole('menuitem', { name: 'Čeština' }).click();
    expect(currentLanguage()).toBe('cs');

    await button('inbox').click();
    await expect.element(page.getByRole('heading', { name: 'inbox', level: 1 })).toBeVisible();
    await expect.element(field('Port')).toHaveAttribute('placeholder', 'Výchozí: 22');
    await expect.element(text('Prostředí přepisují některá z těchto nastavení')).toBeVisible();
    await button('Upravit').click();
    await button('Zkontrolovat').click();
    await expect.element(text('Kontrola našla nedostatky')).toBeVisible();
    await expect.element(page.getByRole('list', { name: 'Nedostatky' }).getByText('colour: takové nastavení neexistuje')).toBeVisible();
  });
});

/** An in endpoint of the sftp adapter, which describes its texts with their keys. */
const partner: EndpointFile = {
  path: 'demo/endpoints/partner.yaml',
  editable: true,
  id: 'partner',
  scheme: 'sftp',
  direction: 'in',
  description: null,
  config: { host: 'files.example', afterSuccess: 'move', moveTo: '/done' },
  environments: [],
};

/** The sftp adapter's Czech, short of the texts of its port, and the platform's Czech short of its retry tab. */
const CZECH: Record<string, Record<string, string>> = {
  sftp: {
    'sftp.tab.polling': 'Dotazování',
    'sftp.field.host.label': 'Hostitel',
    'sftp.field.host.help': 'Název nebo adresa serveru.',
    'sftp.field.afterSuccess.label': 'Po úspěchu',
    'sftp.field.afterSuccess.choice.move': 'Přesunout',
  },
  rail8: { 'rail8.tab.general': 'Obecné', 'rail8.tab.recording': 'Záznam', 'rail8.tab.security': 'Zabezpečení' },
};

/** The texts `bundles` holds for each scheme, in Czech only. */
const textsOf = (bundles: Record<string, Record<string, string>>) =>
  vi.fn(async (scheme: string, language: string) => (language === 'cs' ? (bundles[scheme] ?? null) : null));

describe("an adapter's form in Czech", () => {
  afterEach(() => {
    chooseLanguage('en');
    localStorage.removeItem('rail8.language');
  });

  async function openInCzech(api: EndpointsApi, id = 'partner') {
    chooseLanguage('cs');
    await open(api, id);
  }

  test("shows the adapter's own Czech and the platform's, and the English of a text they lack", async () => {
    const texts = textsOf(CZECH);
    await openInCzech(fakeApi([partner], { texts }));
    for (const name of ['Obecné', 'Dotazování', 'Retry', 'Záznam', 'Zabezpečení']) await expect.element(settingsTab(name)).toBeVisible();
    await expect.element(field('Hostitel')).toHaveValue('files.example');
    await expect.element(text('Název nebo adresa serveru.')).toBeVisible();
    await expect.element(field('Port')).toHaveAttribute('placeholder', 'Výchozí: 22');
    await expect.element(text("The server's port.")).toBeVisible();
    await settingsTab('Dotazování').click();
    await expect.element(field('Po úspěchu')).toHaveValue('Přesunout');
    await expect.element(text('What becomes of a file once it is taken in.')).toBeVisible();
    expect(texts.mock.calls).toEqual([
      ['sftp', 'cs'],
      ['rail8', 'cs'],
    ]);
  });

  test('a draft keeps what its controls hold when the language changes while it is edited', async () => {
    await open(fakeApi([{ ...partner, config: { ...partner.config, colour: 'red' } }], { texts: textsOf(CZECH) }), 'partner');
    await button('Edit').click();
    await field('colour').fill('{"no json');
    chooseLanguage('cs');
    await expect.element(field('Hostitel')).toHaveValue('files.example');
    await expect.element(field('colour')).toHaveValue('{"no json');
  });

  test('shows every text of the adapter in English when the node has none in Czech, or they cannot be had', async () => {
    for (const texts of [vi.fn(async () => null), vi.fn(async () => Promise.reject(new EndpointsError(502, 'the node does not answer')))]) {
      await openInCzech(fakeApi([partner], { texts }));
      for (const name of ['General', 'Polling', 'Retry', 'Recording', 'Security']) await expect.element(settingsTab(name)).toBeVisible();
      await expect.element(field('Host')).toHaveValue('files.example');
      await expect.element(field('Port')).toHaveAttribute('placeholder', 'Výchozí: 22');
      await expect.element(text("The server's name or address.")).toBeVisible();
      root?.unmount();
      host?.remove();
    }
  });

  test('shows the English of a message that does not format, and a text holding markup as typed', async () => {
    const texts = textsOf({ sftp: { 'sftp.field.host.label': 'Hostitel {n}', 'sftp.field.host.help': 'Pozor: <b>tučně</b> a <i>' } });
    await openInCzech(fakeApi([partner], { texts }));
    await expect.element(field('Host')).toHaveValue('files.example');
    await expect.element(page.getByText('Pozor: <b>tučně</b> a <i>', { exact: true })).toBeVisible();
  });

  test("an adapter named like a module of the UI takes none of the UI's texts", async () => {
    const named: ComponentForms = {
      ...web,
      tabs: [{ id: 'general', label: 'General', labelKey: 'rail8.tab.general', common: true }],
      connection: [{ name: 'replyLimit', type: 'integer', label: 'Reply limit', labelKey: 'endpoints.field.replyLimit.label', help: 'How big a reply may be.', helpKey: 'endpoints.field.replyLimit.help', tab: 'general', required: false, directions: ['out'] }],
      flow: [],
    };
    const own: EndpointFile = { ...partner, id: 'own', path: 'demo/endpoints/own.yaml', scheme: 'endpoints', direction: 'out', config: {} };
    const texts = textsOf({ endpoints: {}, rail8: CZECH.rail8! });
    await openInCzech(fakeApi([own], { texts, components: vi.fn(async () => ({ ...components, items: [{ scheme: 'endpoints', httpPrefix: null, settings: named }] })) }), 'own');
    await expect.element(settingsTab('Obecné')).toBeVisible();
    await expect.element(field('Reply limit')).toBeVisible();
    await expect.element(text('How big a reply may be.')).toBeVisible();
  });
});
