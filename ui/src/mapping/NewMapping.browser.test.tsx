import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import type { ApiClient } from './api/client';
import { fakeApiClient } from './api/fake/fake-api';
import { NewMapping } from './NewMapping';

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

/** New mapping in the folder `folder`, with nothing beside it, telling `onCreated` and `openMapping` what it made. */
function render(api: ApiClient, openMapping: (id: string) => void = () => undefined, onCreated: () => void = () => undefined, folder = 'mappings') {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<NewMapping api={api} folder={folder} onCreated={onCreated} openMapping={openMapping} />);
}

/** The fake with a spy on createMapping, so a test can read the request the modal sent. */
function spied(api: ApiClient = fakeApiClient()) {
  const createMapping = vi.fn(api.createMapping);
  return { api: { ...api, createMapping }, createMapping };
}

const modal = () => page.getByRole('dialog', { name: 'New mapping' });
const field = (name: string) => modal().getByRole('textbox', { name });
const helper = (text: string) => modal().getByText(text, { exact: true });
const open = () => page.getByRole('button', { name: 'New mapping' }).click();

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  root?.unmount();
  host?.remove();
  root = undefined;
  // every test fails on console.error
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the New mapping modal', () => {
  test('has Name, Folder and Description and no message choice; Create sends the folders and the name with the description, then opens it', async () => {
    const { api, createMapping } = spied();
    const openMapping = vi.fn();
    const onCreated = vi.fn();
    render(api, openMapping, onCreated, 'orders');

    await open();
    await expect.element(modal().getByRole('textbox')).toHaveLength(3);
    await expect.element(field('Folder')).toHaveValue('mappings');
    await field('Name').fill('order-to-invoice');
    await field('Folder').fill('inbound/eu');
    await field('Description').fill('Turns an order into an invoice.');
    await modal().getByRole('button', { name: 'Create' }).click();

    await expect.poll(() => openMapping.mock.calls).toEqual([['orders/inbound/eu/order-to-invoice']]);
    expect(onCreated).toHaveBeenCalledOnce();
    expect(createMapping.mock.calls).toEqual([[{ id: 'orders/inbound/eu/order-to-invoice', description: 'Turns an order into an invoice.' }]]);
    await expect.element(modal()).not.toBeInTheDocument();
  });

  test('with no folders typed the mapping is in the top folder; an empty description is not sent; spaces around name and folders are dropped', async () => {
    const { api, createMapping } = spied();
    const openMapping = vi.fn();
    render(api, openMapping);
    await open();

    await field('Name').fill('  top  ');
    await field('Folder').fill('   ');
    await userEvent.keyboard('{Enter}');

    await expect.poll(() => openMapping.mock.calls).toEqual([['mappings/top']]);
    expect(createMapping.mock.calls).toEqual([[{ id: 'mappings/top' }]]);
  });

  test('Create without a name asks for one and sends nothing', async () => {
    const { api, createMapping } = spied();
    render(api);
    await open();
    await expect.element(helper('Enter a name.')).not.toBeInTheDocument();

    await modal().getByRole('button', { name: 'Create' }).click();

    await expect.element(helper('Enter a name.')).toBeVisible();
    await expect.element(field('Name')).toHaveAttribute('aria-invalid', 'true');
    expect(createMapping).not.toHaveBeenCalled();
  });

  test.each([
    ['Name', 'a/b', 'Use letters, digits, ".", "_" and "-", up to 64, not starting with ".".'],
    ['Name', '.hidden', 'Use letters, digits, ".", "_" and "-", up to 64, not starting with ".".'],
    ['Name', 'x'.repeat(65), 'Use letters, digits, ".", "_" and "-", up to 64, not starting with ".".'],
    ['Folder', 'a//b', 'Use folder names of letters, digits, ".", "_" and "-", separated by "/".'],
    ['Folder', '../up', 'Use folder names of letters, digits, ".", "_" and "-", separated by "/".'],
    ['Folder', Array.from({ length: 15 }, () => 'p').join('/'), 'Use at most 14 folders.'],
  ])('a %s of "%s" says what is allowed and Create sends nothing', async (label, value, message) => {
    const { api, createMapping } = spied();
    render(api);
    await open();
    await field('Name').fill('valid');

    await field(label).fill(value);

    await expect.element(helper(message)).toBeVisible();
    await expect.element(field(label)).toHaveAttribute('aria-invalid', 'true');
    await modal().getByRole('button', { name: 'Create' }).click();
    expect(createMapping).not.toHaveBeenCalled();
  });

  test('14 folders below the top folder, 16 segments with the top folder and the name, are allowed', async () => {
    const { api, createMapping } = spied();
    const folders = Array.from({ length: 14 }, () => 'p').join('/');
    render(api);
    await open();
    await field('Name').fill('deep');
    await field('Folder').fill(folders);

    await expect.element(field('Folder')).toHaveAttribute('aria-invalid', 'false');
    await modal().getByRole('button', { name: 'Create' }).click();
    await expect.poll(() => createMapping.mock.calls).toEqual([[{ id: `mappings/${folders}/deep` }]]);
  });

  test('a description of more than 4,000 characters, counted by code point, is refused before it is sent', async () => {
    const { api, createMapping } = spied();
    render(api);
    await open();
    await field('Name').fill('valid');

    // 4,000 astral characters are 8,000 UTF-16 units and still allowed
    await field('Description').fill('😀'.repeat(4000));
    await expect.element(helper('Up to 4,000 characters.')).toBeVisible();
    await field('Description').fill('x'.repeat(4001));

    await expect.element(helper('Use at most 4,000 characters; this has 4,001.')).toBeVisible();
    await modal().getByRole('button', { name: 'Create' }).click();
    expect(createMapping).not.toHaveBeenCalled();
  });

  test('an id that exists shows the 409 problem in the modal and opens nothing', async () => {
    const openMapping = vi.fn();
    const onCreated = vi.fn();
    render(fakeApiClient(), openMapping, onCreated);
    await open();

    await field('Name').fill('order-id-copy');
    await field('Folder').fill('');
    await modal().getByRole('button', { name: 'Create' }).click();

    await expect.element(modal().getByRole('heading', { name: 'A mapping with this id exists.' })).toBeVisible();
    expect(openMapping).not.toHaveBeenCalled();
    expect(onCreated).not.toHaveBeenCalled();
  });

  test('Create is disabled while the service has not answered', async () => {
    const fake = fakeApiClient();
    let answer: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const openMapping = vi.fn();
    render({ ...fake, createMapping: async (request) => answered.then(() => fake.createMapping(request)) }, openMapping);
    await open();
    await field('Name').fill('slow');

    await modal().getByRole('button', { name: 'Create' }).click();

    await expect.element(modal().getByRole('button', { name: /Create$/ })).toBeDisabled();
    answer();
    await expect.poll(() => openMapping.mock.calls).toEqual([['mappings/mappings/slow']]);
  });

  test('Cancel closes the modal and sends nothing; it opens empty again', async () => {
    const { api, createMapping } = spied();
    render(api);
    await open();
    await field('Name').fill('draft');

    await modal().getByRole('button', { name: 'Cancel' }).click();

    await expect.element(modal()).not.toBeInTheDocument();
    await expect.element(page.getByRole('button', { name: 'New mapping' })).toHaveFocus();
    expect(createMapping).not.toHaveBeenCalled();
    await open();
    await expect.element(field('Name')).toHaveValue('');
  });

  test('Escape closes the modal as Cancel does', async () => {
    render(fakeApiClient());
    await open();
    await expect.element(modal()).toBeVisible();

    await userEvent.keyboard('{Escape}');

    await expect.element(modal()).not.toBeInTheDocument();
    await expect.element(page.getByRole('button', { name: 'New mapping' })).toHaveFocus();
  });
});
