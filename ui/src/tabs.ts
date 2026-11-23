/**
 * The open tabs of the shell: the ids of the screens open, in the order the row shows them, how many of them, from the
 * start of the row, are pinned, and the id of the one shown, or null when none is open. Each screen is open in at most
 * one tab.
 */
export interface OpenTabs {
  open: readonly string[];
  pinned: number;
  active: string | null;
}

export const noTabs: OpenTabs = { open: [], pinned: 0, active: null };

/** Shows the screen `id`: a new tab at the end when it is not open yet, else the tab it already has. */
export function openTab(tabs: OpenTabs, id: string): OpenTabs {
  return { ...tabs, open: tabs.open.includes(id) ? tabs.open : [...tabs.open, id], active: id };
}

/**
 * Closes the tab of `id`. When it was the one shown, the tab to its right is shown next, or the one to its left when
 * it was the last. Closing a screen that is not open changes nothing.
 */
export function closeTab(tabs: OpenTabs, id: string): OpenTabs {
  const index = tabs.open.indexOf(id);
  if (index < 0) return tabs;
  const open = tabs.open.filter((other) => other !== id);
  const pinned = index < tabs.pinned ? tabs.pinned - 1 : tabs.pinned;
  if (tabs.active !== id) return { open, pinned, active: tabs.active };
  return { open, pinned, active: open[Math.min(index, open.length - 1)] ?? null };
}

/** Whether the tab of `id` is pinned. */
export function isPinned(tabs: OpenTabs, id: string): boolean {
  const index = tabs.open.indexOf(id);
  return index >= 0 && index < tabs.pinned;
}

/** Moves the tab of `id` to `index` of the row and sets how many tabs are pinned. */
function move(tabs: OpenTabs, id: string, index: number, pinned: number): OpenTabs {
  const open = tabs.open.filter((other) => other !== id);
  open.splice(index, 0, id);
  return { ...tabs, open, pinned };
}

/** Pins the tab of `id`: it moves after the tabs pinned before it. A tab already pinned, or not open, stays as it is. */
export function pinTab(tabs: OpenTabs, id: string): OpenTabs {
  if (!tabs.open.includes(id) || isPinned(tabs, id)) return tabs;
  return move(tabs, id, tabs.pinned, tabs.pinned + 1);
}

/** Unpins the tab of `id`: it moves to the start of the tabs not pinned. A tab not pinned stays as it is. */
export function unpinTab(tabs: OpenTabs, id: string): OpenTabs {
  if (!isPinned(tabs, id)) return tabs;
  return move(tabs, id, tabs.pinned - 1, tabs.pinned - 1);
}

/** What the menu of a tab can close: that tab, the others, those to its left or right, or every tab not pinned. */
export type CloseCommand = 'close' | 'others' | 'left' | 'right' | 'unpinned';

/**
 * The ids of the tabs `command`, chosen on the tab of `id`, closes, in the order of the row. Close names the tab of
 * `id` itself, pinned or not; every other command leaves the pinned tabs alone. A screen that is not open names none.
 */
export function closedBy(tabs: OpenTabs, command: CloseCommand, id: string): string[] {
  const index = tabs.open.indexOf(id);
  if (index < 0) return [];
  if (command === 'close') return [id];
  return tabs.open.filter((other, i) => {
    if (i < tabs.pinned) return false;
    if (command === 'others') return i !== index;
    if (command === 'left') return i < index;
    if (command === 'right') return i > index;
    return true;
  });
}
