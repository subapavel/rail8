import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import type { Side } from '../artifacts';
import { chooseLanguage } from '../language';
import { Rail8Theme } from '../theme';
import type { EndpointsApi, NodeComponents } from './api';
import { EndpointChange } from './EndpointChange';

const PATH = 'orders/endpoints/partner.yaml';

const endpoint = (url: string, prod: string) => `schemaVersion: 1
kind: endpoint
id: partner
scheme: http
direction: out
config:
  url: ${url}
  retries: 1
environments:
  prod:
    config:
      url: ${prod}
`;

const side = (rev: string, text: string): Side => ({ rev, text, artifacts: async () => [], read: async () => null });

const NODE: NodeComponents = {
  items: [
    {
      scheme: 'http',
      httpPrefix: null,
      settings: {
        format: 1,
        described: true,
        tabs: [{ id: 'general', label: 'General', common: false }],
        fields: [{ name: 'url', type: 'text', help: 'Where it sends.', tab: 'general', required: true, directions: ['out'] }],
        rules: [],
      },
    },
  ],
} as unknown as NodeComponents;

/** The node's components as `components` answers them; nothing else is asked. */
function apiOf(components: () => Promise<NodeComponents>): EndpointsApi {
  const unused = () => Promise.reject(new Error('not asked'));
  return { list: unused, read: unused, save: unused, create: unused, components };
}

let root: Root | undefined;

function draw(api: EndpointsApi, before: Side | null, after: Side | null) {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Rail8Theme>{() => <EndpointChange api={api} path={PATH} before={before} after={after} />}</Rail8Theme>);
}

const rows = () => page.getByRole('table', { name: `Changes of ${PATH}` }).getByRole('row');

afterEach(() => {
  root?.unmount();
  root = undefined;
  document.body.innerHTML = '';
  chooseLanguage('en');
  localStorage.removeItem('rail8.language');
});

describe('the readable change of an endpoint', () => {
  test('lists the scheme first, then a setting under its field title, then an overlay', async () => {
    const before = endpoint('http://a', 'http://prod');
    const after = endpoint('http://b', 'http://prod2').replace('scheme: http', 'scheme: http\n# the partner').replace('retries: 1', 'retries: 2');
    draw(apiOf(async () => NODE), side('a'.repeat(40), before), side('work', after));

    await expect.element(rows().nth(1)).toHaveTextContent('SettingsGeneral › url"http://a""http://b"');
    await expect.element(rows().nth(2)).toHaveTextContent('Settingsretries12');
    await expect.element(rows().nth(3)).toHaveTextContent('Environment prodGeneral › url"http://prod""http://prod2"');
  });

  test('puts a changed scheme before the settings, and lists an added overlay with its tag', async () => {
    const before = endpoint('http://a', 'http://prod').replace(/environments:[\s\S]*/, '');
    const after = endpoint('http://a', 'http://prod').replace('scheme: http', 'scheme: https');
    draw(apiOf(async () => NODE), side('a'.repeat(40), before), side('work', after));

    await expect.element(rows().nth(1)).toHaveTextContent('Endpointscheme"http""https"');
    await expect.element(rows().nth(2)).toHaveTextContent('Environment prodAddedurlnone"http://prod"');
  });

  test('shows the settings under their key paths when the node gives no description, and says so', async () => {
    draw(apiOf(() => Promise.reject(new Error('the node does not answer'))), side('a'.repeat(40), endpoint('http://a', 'p')), side('work', endpoint('http://b', 'p')));

    await expect.element(page.getByText('The node gave no settings descriptions: the settings are shown under their key paths.')).toBeVisible();
    await expect.element(rows().nth(1)).toHaveTextContent('Settingsurl"http://a""http://b"');
  });

  test('says an endpoint was added, and that a version cannot be read', async () => {
    draw(apiOf(async () => NODE), null, side('work', endpoint('http://a', 'p')));
    await expect.element(page.getByText('Added: every setting is new.')).toBeVisible();
    root?.unmount();

    draw(apiOf(async () => NODE), side('a'.repeat(40), 'kind: endpoint\nid: x\n'), side('work', endpoint('http://a', 'p')));
    await expect.element(page.getByText('The older version cannot be read')).toBeVisible();
    await expect.element(page.getByText('an endpoint file names its id and scheme')).toBeVisible();
  });

  test('says why a version cannot be read in the language chosen while it is open', async () => {
    draw(apiOf(async () => NODE), side('a'.repeat(40), 'schemaVersion: 1\nkind: flow\nid: x\n'), side('work', endpoint('http://a', 'p')));
    await expect.element(page.getByText('the file is not an endpoint')).toBeVisible();

    chooseLanguage('cs');
    await expect.element(page.getByText('Starší verzi nelze přečíst')).toBeVisible();
    await expect.element(page.getByText('soubor není koncový bod')).toBeVisible();
  });
});
