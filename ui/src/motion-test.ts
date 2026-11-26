import { expect } from 'vitest';
import type { Locator } from 'vitest/browser';

/** The classes a dialog or a menu carries while it zooms or slides in: `<motion>-appear` or `<motion>-enter`, and their steps. */
const OPENING = /-(appear|enter)(-|\s|$)/;

/** The element that moves while it opens: a dialog, or a dropdown's menu. */
const MOVING = '.ant-modal, .ant-dropdown';

/**
 * Returns once the dialog or menu that is or holds `locator`'s element is visible and has finished opening. While it opens, its
 * content moves under the pointer: a click pressed on a button there can be released beside it, and then reaches nothing.
 */
export async function opened(locator: Locator) {
  await expect.element(locator).toBeVisible();
  await expect
    .poll(() => OPENING.test(locator.element().closest(MOVING)?.className ?? ''))
    .toBe(false);
}
