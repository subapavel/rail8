import { Button, Flex } from 'antd';
import { useEffect, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { chooseLanguage } from '../language';
import type { Screen } from '../screens';
import { Shell } from '../Shell';
import { useWorkingFiles, type WorkingFiles } from '../workingFiles';
import type { ApiClient } from './api/client';
import { fakeApiClient } from './api/fake/fake-api';
import { mappingKind } from './screen';
import type { BrowserStateStore } from './studio/browser-state/browser-state';

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

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

function memoryBrowserState(): BrowserStateStore {
  const values = new Map<string, unknown>();
  return { read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

/** The buttons of the mappings of the working folder, each opening its studio, and New mapping in `folder` beside `beside`. */
function Mappings({ api, open, folder, beside }: { api: ApiClient; open: (screen: Screen) => void; folder: string; beside?: string }) {
  const [kind] = useState(() => mappingKind(api, memoryBrowserState()));
  const [ids, setIds] = useState<string[]>([]);
  useEffect(() => void api.listMappings().then(({ mappings }) => setIds(mappings.map(({ id }) => id))), [api]);
  const New = kind.New!;
  return (
    <Flex gap="small" wrap>
      {ids.map((id) => (
        <Button key={id} onClick={() => open(kind.open(`${id}.mapping.json`))}>
          {id.slice(id.lastIndexOf('/') + 1)}
        </Button>
      ))}
      <New folder={folder} beside={beside} open={open} onCreated={() => {}} />
    </Flex>
  );
}

function render(api: ApiClient, folder = 'mappings', beside = 'mappings/order-id-copy.mapping.json') {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  // the screen the tests open mappings from, as a folder's tab opens them
  const files: Screen = { id: 'files', title: 'Files', render: (open) => <Mappings api={api} open={open} folder={folder} beside={beside} /> };
  root.render(<Shell screens={[probed(files)]} start="files" />);
}

// the shell's tabs; the studio's own tabs are named Overview and Signature
const shellTab = (name: string) => page.getByRole('tab', { name, exact: true });
const shellTabs = () => page.getByRole('tab').filter({ hasNotText: /^(Overview|Definition|Test|Signature|Functions)$/ });
// a role query finds what is shown only: the tabs not shown are hidden
const description = () => page.getByRole('textbox', { name: 'Description' });
const button = (name: string) => page.getByRole('button', { name, exact: true });
const saveQuestion = (name: string) => page.getByRole('dialog', { name: `Save changes to ${name}?` });

/** Opens the mapping `name` from the Files tab and waits for its studio. */
async function openMapping(name: string) {
  await shellTab('Files').click();
  await button(name).click();
  await expect.element(shellTab(name)).toHaveAttribute('aria-selected', 'true');
  await expect.element(page.getByRole('heading', { level: 1, name })).toBeVisible();
}

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  root?.unmount();
  host?.remove();
  root = undefined;
  working = undefined;
  chooseLanguage('en');
  localStorage.removeItem('rail8.language');
  // every test fails on console.error
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('mappings in the shell', () => {
  test('opens each mapping in a tab of its own, several at once, each keeping its own edits', async () => {
    render(fakeApiClient());
    await openMapping('order-id-copy');
    await button('Edit').click();
    await description().fill('Copies the order id.');

    await openMapping('order-unmapped');
    await expect.element(button('Edit')).toBeVisible();
    await expect.element(description()).toHaveValue('');
    expect(shellTabs().elements()).toHaveLength(3);

    await shellTab('order-id-copy').click();
    await expect.element(description()).toHaveValue('Copies the order id.');
    await expect.element(button('Save')).toBeEnabled();

    await openMapping('order-id-copy');
    expect(shellTabs().elements()).toHaveLength(3);
  });

  test('a mapping just created opens in its tab in edit mode, on the Signature tab', async () => {
    render(fakeApiClient());
    await page.getByRole('button', { name: 'New mapping' }).click();
    await page.getByRole('dialog', { name: 'New mapping' }).getByRole('textbox', { name: 'Name' }).fill('fresh');
    await page.getByRole('button', { name: 'Create' }).click();

    await expect.element(shellTab('fresh')).toHaveAttribute('aria-selected', 'true');
    await expect.element(button('Save')).toBeDisabled();
    await expect.element(button('Cancel')).toBeVisible();
    await expect.element(shellTab('Signature')).toHaveAttribute('aria-selected', 'true');
    await expect.element(button('Add source message')).toBeEnabled();
  });

  test('New mapping starts in the folder of the mapping beside it, below the folder it shows, and makes the id of them', async () => {
    const fake = fakeApiClient();
    const createMapping = vi.fn(fake.createMapping);
    render({ ...fake, createMapping }, 'orders', 'orders/mappings/in/order.mapping.json');
    await page.getByRole('button', { name: 'New mapping' }).click();
    const dialog = page.getByRole('dialog', { name: 'New mapping' });
    await expect.element(dialog.getByRole('textbox', { name: 'Folder' })).toHaveValue('mappings/in');
    await expect.element(dialog.getByText('orders/', { exact: true })).toBeVisible();
    await dialog.getByRole('textbox', { name: 'Name' }).fill('fresh');
    await page.getByRole('button', { name: 'Create' }).click();
    await expect.element(shellTab('fresh')).toHaveAttribute('aria-selected', 'true');
    expect(createMapping).toHaveBeenLastCalledWith({ id: 'orders/mappings/in/fresh' });

    await shellTab('Files').click();
    await page.getByRole('button', { name: 'New mapping' }).click();
    await dialog.getByRole('textbox', { name: 'Folder' }).fill('');
    await dialog.getByRole('textbox', { name: 'Name' }).fill('top');
    await page.getByRole('button', { name: 'Create' }).click();
    await expect.element(shellTab('top')).toHaveAttribute('aria-selected', 'true');
    expect(createMapping).toHaveBeenLastCalledWith({ id: 'orders/top' });
  });

  test('closing the tab of a mapping with unsaved changes asks; Cancel keeps it, Close without saving closes it', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.fn(fake.saveMapping);
    render({ ...fake, saveMapping });
    await openMapping('order-id-copy');
    await button('Edit').click();
    await description().fill('Copies the order id.');

    await page.getByRole('button', { name: 'Close order-id-copy' }).click();
    await expect.element(saveQuestion('order-id-copy')).toMatchTextContent(/order-id-copy has unsaved changes\./);
    await saveQuestion('order-id-copy').getByRole('button', { name: 'Cancel' }).click();
    await expect.element(saveQuestion('order-id-copy')).not.toBeInTheDocument();
    await expect.element(description()).toHaveValue('Copies the order id.');

    await page.getByRole('button', { name: 'Close order-id-copy' }).click();
    await saveQuestion('order-id-copy').getByRole('button', { name: 'Close without saving' }).click();

    await expect.element(shellTab('order-id-copy')).not.toBeInTheDocument();
    await expect.element(shellTab('Files')).toHaveAttribute('aria-selected', 'true');
    expect(saveMapping).not.toHaveBeenCalled();
  });

  test('Save and close saves the mapping as it is now, then closes its tab', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.fn(fake.saveMapping);
    render({ ...fake, saveMapping });
    await openMapping('order-id-copy');
    await button('Edit').click();
    await description().fill('Copies');
    await description().fill('Copies the order id.');

    await page.getByRole('button', { name: 'Close order-id-copy' }).click();
    await saveQuestion('order-id-copy').getByRole('button', { name: 'Save and close' }).click();

    await expect.element(shellTab('order-id-copy')).not.toBeInTheDocument();
    expect(saveMapping).toHaveBeenCalledOnce();
    expect(saveMapping.mock.calls[0]?.[1].description).toBe('Copies the order id.');
  });

  test('a mapping whose save fails keeps its tab open and says why', async () => {
    const fake = fakeApiClient();
    render({ ...fake, saveMapping: vi.fn(async () => Promise.reject(new Error('The service does not answer'))) });
    await openMapping('order-id-copy');
    await button('Edit').click();
    await description().fill('Copies the order id.');
    await shellTab('Files').click();

    await page.getByRole('button', { name: 'Close order-id-copy' }).click();
    await saveQuestion('order-id-copy').getByRole('button', { name: 'Save and close' }).click();

    await expect.element(shellTab('order-id-copy')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('heading', { name: 'The service does not answer' })).toBeVisible();
    await expect.element(description()).toHaveValue('Copies the order id.');
  });

  test('a mapping gives the shell its file, with unsaved changes while its working copy differs, and is read again when the shell asks while it has none', async () => {
    const fake = fakeApiClient();
    render(fake);
    await openMapping('order-id-copy');
    const file = 'mappings/order-id-copy.mapping.json';
    await expect.poll(() => working?.files().get(file)?.dirty).toBe(false);

    await button('Edit').click();
    await shellTab('Overview').click();
    await description().fill('Copies the order id.');
    expect(working!.files().get(file)?.dirty).toBe(true);
    // a hook rewrites the file
    const stored = await fake.getMapping('mappings/order-id-copy');
    await fake.saveMapping('mappings/order-id-copy', { ...stored.model, description: 'Rewritten by a hook.' }, stored.etag);
    working!.reload([file]);
    await expect.element(description()).toHaveValue('Copies the order id.');

    await button('Cancel').click();
    await button('Discard changes').click();
    working!.reload([file]);
    await expect.element(page.getByText('Rewritten by a hook.')).toBeVisible();
  });

  test('the tab of a mapping without unsaved changes closes at once', async () => {
    render(fakeApiClient());
    await openMapping('order-id-copy');

    await page.getByRole('button', { name: 'Close order-id-copy' }).click();

    await expect.element(shellTab('order-id-copy')).not.toBeInTheDocument();
    await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
  });

  test('Ctrl+S saves the mapping of the tab shown and no other', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.fn(fake.saveMapping);
    render({ ...fake, saveMapping });
    await openMapping('order-unmapped');
    await button('Edit').click();
    await description().fill('Not shown.');
    await openMapping('order-id-copy');
    await button('Edit').click();
    await description().fill('Copies the order id.');

    await userEvent.keyboard('{Control>}s{/Control}');

    await expect.element(page.getByRole('heading', { name: /Mapping order-id-copy saved/ })).toBeVisible();
    expect(saveMapping.mock.calls.map(([id]) => id)).toEqual(['mappings/order-id-copy']);
  });
});

describe('New mapping and the studio in another language', () => {
  test('show their texts and Ant Design in the language chosen', async () => {
    render(fakeApiClient());
    await expect.element(button('order-id-copy')).toBeVisible();

    chooseLanguage('cs');

    await page.getByRole('button', { name: 'Nové mapování' }).click();
    const dialog = page.getByRole('dialog', { name: 'Nové mapování' });
    await expect.element(dialog.getByRole('textbox', { name: 'Popis' })).toBeVisible();
    await expect.element(dialog).toMatchTextContent(/Nejvýše 4\s000 znaků\./);
    await dialog.getByRole('textbox', { name: 'Název' }).fill('.bad');
    await expect.element(dialog).toMatchTextContent(/Použijte písmena, číslice/);
    await dialog.getByRole('button', { name: 'Zavřít', exact: true }).click();
    await expect.element(dialog).not.toBeInTheDocument();

    await button('order-id-copy').click();
    await expect.element(page.getByRole('heading', { level: 1, name: 'order-id-copy' })).toBeVisible();
    await expect.element(page.getByRole('heading', { name: 'Podrobnosti mapování' })).toBeVisible();
    await expect.element(page.getByRole('tab', { name: 'Přehled', exact: true })).toHaveAttribute('aria-selected', 'true');
    await button('Upravit').click();
    await expect.element(button('Zrušit')).toBeVisible();
    await page.getByRole('tab', { name: 'Signatura', exact: true }).click();
    await expect.element(page.getByRole('region', { name: 'Zdrojové zprávy' })).toBeVisible();
  });
});

describe('the mapping studio in the shell', () => {
  const fields = (side: 'source' | 'target') => page.getByRole('region', { name: `${side} message` }).getByRole('table', { name: `${side} fields` });
  const canvasNode = (name: RegExp) => page.getByRole('group', { name: 'Mapping canvas' }).getByRole('group', { name });
  const nodeFooter = () => page.getByRole('region', { name: 'Field mapping canvas' }).getByText(/ nodes? · /);
  /** How far the document scrolls vertically: 0 when the page fits the window. */
  const verticalScroll = () => document.documentElement.scrollHeight - document.documentElement.clientHeight;
  /** Whether `element` is wholly inside the window. */
  const wholly = (element: Element) => {
    const box = element.getBoundingClientRect();
    return box.top >= 0 && box.left >= 0 && box.bottom <= window.innerHeight && box.right <= window.innerWidth && box.height > 0;
  };

  test('the canvas counts its nodes and connections in the language chosen while it is open', async () => {
    render(fakeApiClient());
    await openMapping('order-id-copy');
    await shellTab('Definition').click();
    await fields('target').getByRole('button', { name: 'OrderId', exact: true }).click();
    await expect.element(nodeFooter()).toHaveTextContent('2 nodes · 1 connection');

    chooseLanguage('cs');

    await expect.element(page.getByRole('region', { name: 'Plocha mapování pole' }).getByText('2 uzly · 1 spojení')).toBeVisible();
  });

  test('at 1440 x 900 the studio fills the window without a page scroll, on the Definition and on the Test tab', async () => {
    await page.viewport(1440, 900);
    render(fakeApiClient());
    await openMapping('order-id-copy');
    await shellTab('Definition').click();
    await fields('target').getByRole('button', { name: 'OrderId', exact: true }).click();

    await expect.element(nodeFooter()).toHaveTextContent('2 nodes · 1 connection');
    for (const part of [
      fields('source'),
      fields('target'),
      page.getByRole('group', { name: 'Mapping canvas' }),
      page.getByRole('region', { name: 'Function catalogue' }),
    ]) {
      await expect.element(part).toBeInViewport();
    }
    // the canvas fits its pane: its tools and its footer are fully shown, not scrolled away
    expect(wholly(page.getByRole('toolbar', { name: 'Mapping tools' }).element())).toBe(true);
    expect(wholly(nodeFooter().element())).toBe(true);
    expect(verticalScroll()).toBe(0);

    await shellTab('Test').click();
    await expect.element(page.getByRole('button', { name: 'Run simulation' })).toBeInViewport();
    // the result region is wholly on screen, not pushed below the fold, and so is the processing log's head
    expect(wholly(page.getByRole('region', { name: 'Result', exact: true }).element())).toBe(true);
    expect(wholly(page.getByRole('heading', { name: 'Processing log' }).element())).toBe(true);
    expect(verticalScroll()).toBe(0);
  });

  test('a wire, a parameter and a moved box survive Save, closing the tab and opening the mapping again', async () => {
    await page.viewport(1440, 900);
    render(fakeApiClient());
    await openMapping('order-unmapped');
    await button('Edit').click();
    await shellTab('Definition').click();
    await fields('target').getByRole('button', { name: 'OrderId', exact: true }).click();
    await fields('source').getByRole('button', { name: 'OrderId', exact: true }).dblClick();
    await expect.element(canvasNode(/^Source field OrderId\. Complete/)).toBeVisible();

    const catalogue = page.getByRole('region', { name: 'Function catalogue' });
    await catalogue.getByRole('button', { name: /^Text/ }).click();
    await catalogue.getByRole('button', { name: /^substring\b/ }).click();
    await canvasNode(/^Source field OrderId/).click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'Connect to', exact: true }).click();
    await page.getByRole('menuitem', { name: 'substring · value', exact: true }).click();
    await canvasNode(/^Node function substring/).click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'Connect to', exact: true }).click();
    await page.getByRole('menuitem', { name: 'OrderId · OrderId', exact: true }).click();
    await expect.element(canvasNode(/^Node function substring\. Complete/)).toBeVisible();

    await canvasNode(/^Node function substring/)
      .getByRole('button', { name: 'Parameters for substring' })
      .click();
    const parameters = page.getByRole('dialog', { name: 'Function parameters · substring' });
    await parameters.getByLabelText('Character Count').fill('3');
    await parameters.getByRole('button', { name: 'Apply' }).click();
    await expect.element(parameters).not.toBeInTheDocument();

    const transform = () => (canvasNode(/^Node function substring/).element() as HTMLElement).style.transform;
    const before = transform();
    await userEvent.dragAndDrop(canvasNode(/^Node function substring/), canvasNode(/^Target field OrderId/), { steps: 5 });
    await expect.poll(transform).not.toBe(before);
    const moved = transform();
    await expect.element(nodeFooter()).toHaveTextContent('3 nodes · 2 connections');

    await button('Save').click();
    await expect.element(page.getByRole('heading', { name: /Mapping order-unmapped saved/ })).toBeVisible();
    await page.getByRole('button', { name: 'Close order-unmapped' }).click();
    await expect.element(shellTab('order-unmapped')).not.toBeInTheDocument();

    await openMapping('order-unmapped');
    await shellTab('Definition').click();
    await fields('target').getByRole('button', { name: 'OrderId', exact: true }).click();
    await expect.element(nodeFooter()).toHaveTextContent('3 nodes · 2 connections');
    await expect.element(canvasNode(/^Node function substring\. Complete/)).toBeVisible();
    await expect.element(canvasNode(/^Node function substring/)).toMatchTextContent(/length 3/);
    expect(transform()).toBe(moved);
    await expect.element(button('Edit')).toBeVisible();
    await expect.element(button('Save')).not.toBeInTheDocument();
  });
});
