import { describe, expect, it } from 'vitest';
import { closedBy, closeTab, isPinned, noTabs, openTab, pinTab, unpinTab, type OpenTabs } from './tabs';

describe('openTab', () => {
  it('adds a tab at the end and shows it', () => {
    const tabs = openTab(openTab(noTabs, 'home'), 'search');
    expect(tabs).toEqual({ open: ['home', 'search'], pinned: 0, active: 'search' });
  });

  it('shows the tab a screen already has instead of opening a second one', () => {
    const tabs = openTab(openTab(openTab(noTabs, 'home'), 'search'), 'home');
    expect(tabs).toEqual({ open: ['home', 'search'], pinned: 0, active: 'home' });
  });
});

describe('closeTab', () => {
  const three: OpenTabs = { open: ['a', 'b', 'c'], pinned: 0, active: 'b' };

  it('shows the tab to the right of the closed one', () => {
    expect(closeTab(three, 'b')).toEqual({ open: ['a', 'c'], pinned: 0, active: 'c' });
  });

  it('shows the tab to the left when the last one is closed', () => {
    expect(closeTab({ ...three, active: 'c' }, 'c')).toEqual({ open: ['a', 'b'], pinned: 0, active: 'b' });
  });

  it('keeps the shown tab when another one is closed', () => {
    expect(closeTab(three, 'a')).toEqual({ open: ['b', 'c'], pinned: 0, active: 'b' });
  });

  it('shows nothing once the only tab is closed', () => {
    expect(closeTab(openTab(noTabs, 'a'), 'a')).toEqual(noTabs);
  });

  it('changes nothing for a screen that is not open', () => {
    expect(closeTab(three, 'x')).toBe(three);
  });

  it('counts one pinned tab fewer once a pinned tab is closed', () => {
    expect(closeTab({ ...three, pinned: 2 }, 'a')).toEqual({ open: ['b', 'c'], pinned: 1, active: 'b' });
    expect(closeTab({ ...three, pinned: 1 }, 'c')).toEqual({ open: ['a', 'b'], pinned: 1, active: 'b' });
  });
});

describe('pinTab and unpinTab', () => {
  const four: OpenTabs = { open: ['a', 'b', 'c', 'd'], pinned: 1, active: 'c' };

  it('moves a pinned tab after the tabs pinned before it', () => {
    const tabs = pinTab(four, 'd');
    expect(tabs).toEqual({ open: ['a', 'd', 'b', 'c'], pinned: 2, active: 'c' });
    expect(isPinned(tabs, 'd')).toBe(true);
    expect(isPinned(tabs, 'b')).toBe(false);
  });

  it('moves an unpinned tab to the start of the tabs not pinned', () => {
    const tabs = unpinTab({ ...four, pinned: 3 }, 'a');
    expect(tabs).toEqual({ open: ['b', 'c', 'a', 'd'], pinned: 2, active: 'c' });
    expect(isPinned(tabs, 'a')).toBe(false);
  });

  it('leaves a tab already pinned, not pinned, or not open as it is', () => {
    expect(pinTab(four, 'a')).toBe(four);
    expect(pinTab(four, 'x')).toBe(four);
    expect(unpinTab(four, 'b')).toBe(four);
    expect(unpinTab(four, 'x')).toBe(four);
  });
});

describe('closedBy', () => {
  // a and b pinned
  const tabs: OpenTabs = { open: ['a', 'b', 'c', 'd', 'e'], pinned: 2, active: 'a' };

  it('names the tab itself for Close, pinned or not', () => {
    expect(closedBy(tabs, 'close', 'a')).toEqual(['a']);
    expect(closedBy(tabs, 'close', 'd')).toEqual(['d']);
  });

  it('names the tabs not pinned other than the tab for Close other tabs', () => {
    expect(closedBy(tabs, 'others', 'd')).toEqual(['c', 'e']);
    expect(closedBy(tabs, 'others', 'a')).toEqual(['c', 'd', 'e']);
  });

  it('names the tabs not pinned to the left or the right of the tab', () => {
    expect(closedBy(tabs, 'left', 'd')).toEqual(['c']);
    expect(closedBy(tabs, 'right', 'd')).toEqual(['e']);
    expect(closedBy(tabs, 'left', 'c')).toEqual([]);
    expect(closedBy(tabs, 'right', 'b')).toEqual(['c', 'd', 'e']);
  });

  it('names every tab not pinned for Close all but pinned', () => {
    expect(closedBy(tabs, 'unpinned', 'a')).toEqual(['c', 'd', 'e']);
  });

  it('names none for a screen that is not open', () => {
    expect(closedBy(tabs, 'others', 'x')).toEqual([]);
  });
});
