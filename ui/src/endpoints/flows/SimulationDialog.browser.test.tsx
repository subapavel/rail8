import { createRoot, type Root } from 'react-dom/client';
import { afterEach, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { SimulationDialog } from './SimulationDialog';

let host: HTMLElement;
let root: Root;

function mount(initiallyOpen = true, startLabel = 'Validate', endLabel = 'Send invoice', stepCount = 2) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  const render = (open: boolean) => root.render(<>
    <button onClick={() => render(true)}>Open input</button>
    <SimulationDialog open={open} onClose={() => render(false)} startLabel={startLabel} endLabel={endLabel} stepCount={stepCount} />
  </>);
  render(initiallyOpen);
}

afterEach(() => { root?.unmount(); host?.remove(); });

const dialog = () => page.getByRole('dialog', { name: 'Simulation preview' });
const tab = (name: string) => dialog().getByRole('tab', { name, exact: true });
const button = (name: string) => dialog().getByRole('button', { name, exact: true });
const field = (name: string) => dialog().getByRole('textbox', { name, exact: true });

test('previews the chosen range and all four parts of the input, and keeps the draft when reopened', async () => {
  mount();
  await expect.element(dialog()).toMatchTextContent('Validate');
  await expect.element(dialog()).toMatchTextContent('Send invoice');
  await field('Message body').fill('<order id="41"/>');
  await tab('Attachments').click();
  await dialog().getByLabelText('Add attachments').upload(new File(['abc'], 'invoice.txt', { type: 'text/plain' }));
  await tab('Properties').click();
  await button('Add property').click();
  await field('Property name 1').fill('partner');
  await field('Property value 1').fill('Northwind');
  await tab('Headers').click();
  await button('Add header').click();
  await field('Header name 1').fill('Content-Type');
  await field('Header value 1').fill('application/xml');
  await button('Preview input').click();
  const preview = dialog().getByRole('region', { name: 'Input preview' });
  for (const value of ['<order id="41"/>', 'invoice.txt', 'text/plain', 'partner', 'Northwind', 'Content-Type', 'application/xml']) {
    await expect.element(preview).toMatchTextContent(value);
  }
  await button('Back to input').click();
  await tab('Attachments').click();
  await button('Remove invoice.txt').click();
  await button('Close preview').click();
  await expect.element(dialog()).not.toBeInTheDocument();
  await page.getByRole('button', { name: 'Open input' }).click();
  await tab('Body').click();
  await expect.element(field('Message body')).toHaveValue('<order id="41"/>');
  await tab('Attachments').click();
  await expect.element(button('Remove invoice.txt')).not.toBeInTheDocument();
});

test('names a single step once and returns keyboard focus to the opener on Escape', async () => {
  mount(false, 'Validate', 'Validate', 1);
  const opener = page.getByRole('button', { name: 'Open input' });
  await opener.click();
  await expect.element(dialog()).toMatchTextContent('Validate');
  await expect.element(dialog()).not.toMatchTextContent('Validate → Validate');
  await field('Message body').click();
  await userEvent.keyboard('{Escape}');
  await expect.element(dialog()).not.toBeInTheDocument();
  await expect.element(opener).toHaveFocus();
});

test('shows both bounds when a multi-step range starts and ends with the same label', async () => {
  mount(true, 'Validate', 'Validate', 2);
  await expect.element(dialog()).toMatchTextContent('Validate → Validate');
});

test('refuses duplicate header names across tabs and lets the user correct them', async () => {
  mount();
  await tab('Headers').click();
  for (const [index, name] of ['X-Order', 'x-order'].entries()) {
    await button('Add header').click();
    await field(`Header name ${index + 1}`).fill(name);
  }
  await tab('Body').click();
  await button('Preview input').click();
  await expect.element(tab('Headers')).toHaveAttribute('aria-selected', 'true');
  await expect.element(dialog().getByRole('alert')).toMatchTextContent(/duplicate/i);
  await button('Remove header 2').click();
  await button('Preview input').click();
  await expect.element(dialog().getByRole('region', { name: 'Input preview' })).toMatchTextContent('X-Order');
});

test('rejects an oversized attachment without losing previously selected files or uploading them', async () => {
  mount();
  await tab('Attachments').click();
  const input = dialog().getByLabelText('Add attachments');
  const send = vi.spyOn(XMLHttpRequest.prototype, 'send');
  try {
    await input.upload(new File(['abc'], 'keep.txt', { type: 'text/plain' }));
    await input.upload(new File([new Uint8Array(1024 * 1024 + 1)], 'large.bin'));
    await expect.element(dialog().getByRole('alert')).toMatchTextContent(/1 MiB/);
    await expect.element(button('Remove keep.txt')).toBeVisible();
    await expect.element(button('Remove large.bin')).not.toBeInTheDocument();
    expect(send).not.toHaveBeenCalled();
  } finally {
    send.mockRestore();
  }
});
