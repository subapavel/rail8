import { expect } from 'vitest';
import type { Locator } from 'vitest/browser';

/** The classes a dialog, a menu or a select's list carries while it zooms or slides in: `<motion>-appear` or `<motion>-enter`, and their steps. */
const OPENING = /-(appear|enter)(-|\s|$)/;

/** The element that moves while it opens: a dialog, a dropdown's menu, a submenu of it, or a select's list. */
const MOVING = '.ant-modal, .ant-dropdown-menu-submenu-popup, .ant-dropdown, .ant-select-dropdown';

/**
 * Returns once the dialog, menu or list that is or holds `locator`'s element is visible and has finished opening. While it
 * opens, its content moves under the pointer: a click pressed on a button there can be released beside it, and then reaches
 * nothing. A select's options are found in the list's hidden copy for assistive technology, which has no size of its own:
 * the list holding it is what shows.
 */
export async function opened(locator: Locator) {
  await expect.element(locator).toBeInTheDocument();
  // on a busy machine the opening has taken most of a second, the whole of a poll's default wait
  await expect
    .poll(
      () => {
        const moving = locator.element().closest(MOVING);
        return moving !== null && moving.checkVisibility() && !OPENING.test(moving.className);
      },
      { timeout: 5_000 },
    )
    .toBe(true);
}
