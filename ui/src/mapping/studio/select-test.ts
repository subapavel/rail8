import { expect } from 'vitest';
import { page, userEvent, type Locator } from 'vitest/browser';

/** Chooses the visible option in an openable combobox. */
export async function chooseOption(control: Locator, label: string) {
  await control.click();
  await page.getByRole('listbox').getByRole('option', { name: label, exact: true }).click();
}

/** Counts all choices, then restores focus to the closed combobox. */
export async function expectOptions(control: Locator, count: number | string[]) {
  await control.click();
  const options = page.getByRole('listbox').getByRole('option');
  if (typeof count === 'number') await expect.element(options).toHaveLength(count);
  else {
    await expect.element(options).toHaveLength(count.length);
    for (const [i, label] of count.entries()) await expect.element(options.nth(i)).toHaveTextContent(label);
  }
  await userEvent.keyboard('{Escape}');
}
