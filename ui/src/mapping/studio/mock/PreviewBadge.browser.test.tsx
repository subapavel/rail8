import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { PreviewBadge } from './PreviewBadge';

let root: Root | undefined;

/** The badge of the preview feature "Value mapping", follow-up issue 166, after a button that takes the focus first. */
async function show() {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () =>
    root!.render(
      <>
        <button>Before</button>
        <PreviewBadge feature="Value mapping" issue={166} />
      </>,
    ),
  );
}

afterEach(() => {
  root?.unmount();
  root = undefined;
  document.body.innerHTML = '';
});

const badge = () => page.getByText('Preview', { exact: true });
const tip = () => page.getByRole('tooltip', { name: 'Value mapping: a preview kept in this browser only, never in the mapping file (#166).' });

describe('the Preview badge', () => {
  test('names the feature and its issue while the pointer is on it, and a click on it leaves no tooltip once the pointer moves away', async () => {
    await show();
    await badge().hover();
    await expect.element(tip()).toBeVisible();
    await badge().click();
    await page.getByRole('button', { name: 'Before' }).hover();
    await expect.element(tip()).not.toBeInTheDocument();
  });

  test('names them while it has the keyboard focus', async () => {
    await show();
    await page.getByRole('button', { name: 'Before' }).element().focus();
    await userEvent.tab();
    await expect.element(tip()).toBeVisible();
    await userEvent.tab({ shift: true });
    await expect.element(tip()).not.toBeInTheDocument();
  });
});
