import { useEffect, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { home } from './Home';
import { Page } from './Page';
import { screenList, type Find, type Found, type Screen } from './screens';
import { Shell } from './Shell';
import { opened } from './motion-test';

// the screens the application lists, found as main.tsx finds them
const screens = screenList(home, import.meta.glob('./*/module.tsx', { eager: true }));

const two: readonly Screen[] = [
  { id: 'first', title: 'First', render: () => <p>First screen</p> },
  { id: 'second', title: 'Second', render: () => <p>Second screen</p> },
];

let root: Root | undefined;
let host: HTMLElement | undefined;

function render(list: readonly Screen[], start?: string, find?: Find) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={list} start={start} find={find} />);
}

afterEach(async () => {
  root?.unmount();
  host?.remove();
  await page.viewport(1280, 800);
});

const nav = () => page.getByRole('navigation', { name: 'Screens' });
const menuItem = (name: string) => nav().getByRole('menuitem', { name, exact: true });
const navigate = (name: string) => menuItem(name).click();
const menuWidth = () => page.getByRole('complementary').element().getBoundingClientRect().width;
const tab = (name: string) => page.getByRole('tab', { name });
const tabs = () => page.getByRole('tab');
const titles = () => tabs().elements().map((element) => element.textContent?.trim());
const item = (name: string) => page.getByRole('menuitem', { name, exact: true });
const button = (name: string) => page.getByRole('button', { name, exact: true });

/** Plain screens titled `names`, each saying its title. */
const plain = (...names: string[]): Screen[] =>
  names.map((name) => ({ id: name.toLowerCase(), title: name, render: () => <p>{name} screen</p> }));

/**
 * A screen titled `name` with unsaved changes whose save resolves `saved`, as a spy. A save that fails makes the screen
 * say why.
 */
function edited(name: string, saved = true) {
  const save = vi.fn(async () => saved);
  let failed: (() => void) | undefined;
  function Body() {
    const [why, setWhy] = useState<string | null>(null);
    useEffect(() => {
      failed = () => setWhy(`${name} was not saved: the disk is full`);
    }, []);
    return <p>{why ?? `${name} screen`}</p>;
  }
  const screen: Screen = {
    id: name.toLowerCase(),
    title: name,
    render: () => <Body />,
    unsaved: () => ({
      save: async () => {
        const done = await save();
        if (!done) failed?.();
        return done;
      },
    }),
  };
  return { screen, save };
}

/** Renders `list` with every screen of it open in a tab, in its order, and the first one shown. */
async function renderOpen(list: readonly Screen[]) {
  render(list, list[0]!.id);
  for (const screen of list.slice(1)) await navigate(screen.title as string);
  await tab(list[0]!.title as string).click();
}

/** Chooses `name` in the menu of the tab `title`, opened with a right click. */
async function fromMenu(title: string, name: string) {
  await tab(title).click({ button: 'right' });
  await opened(item(name));
  await item(name).click();
  await expect.element(item(name)).not.toBeInTheDocument();
}

describe('Shell', () => {
  test('switches workspace theme without losing the active screen', async () => {
    render(two, 'first');
    await page.getByRole('button', { name: 'Use dark theme' }).click();
    await expect.element(tab('First')).toHaveAttribute('aria-selected', 'true');
    await page.getByRole('button', { name: 'Use light theme' }).click();
    await expect.element(page.getByText('First screen')).toBeVisible();
  });

  test('starts with the Home screen of the application open in a tab', async () => {
    render(screens, screens[0]?.id);
    await expect.element(page.getByRole('banner').getByText('Rail8', { exact: true })).toBeVisible();
    await expect.element(tab('Home')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('heading', { level: 1, name: 'Home' })).toBeVisible();
  });

  test('lists Home, then the screens of the modules in their order', async () => {
    render(screens, screens[0]?.id);
    await expect.element(menuItem('Home')).toBeVisible();
    expect(nav().getByRole('menuitem').elements().map((button) => button.textContent)).toEqual(['Home', 'Messages', 'Packages', 'Users', 'Tokens', 'Audit', 'My tokens']);
  });

  test('opens a screen chosen in the navigation in a new tab, and shows the tab it already has', async () => {
    render(two, 'first');
    await navigate('Second');
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByText('Second screen')).toBeVisible();
    expect(tabs().elements()).toHaveLength(2);

    await navigate('First');
    await expect.element(tab('First')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByText('First screen')).toBeVisible();
    expect(tabs().elements()).toHaveLength(2);
  });

  test('switches and closes tabs from the keyboard', async () => {
    render(two, 'first');
    await navigate('Second');
    await tab('First').click();
    await expect.element(page.getByText('First screen')).toBeVisible();

    await userEvent.tab();
    await expect.element(page.getByRole('button', { name: 'Close First' })).toHaveFocus();
    await userEvent.tab({ shift: true });
    await userEvent.keyboard('{ArrowRight}{Enter}');
    await expect.element(page.getByRole('tab', { name: /Second$/, selected: true })).toHaveFocus();
    await expect.element(page.getByText('Second screen')).toBeVisible();

    await userEvent.tab();
    await userEvent.keyboard('{Enter}');
    await expect.element(tab('First')).toHaveAttribute('aria-selected', 'true');
    expect(tabs().elements()).toHaveLength(1);
  });

  test('closes a tab with its close button and shows the next one, then says when none is open', async () => {
    render(two, 'first');
    await navigate('Second');
    await tab('First').click();

    await page.getByRole('button', { name: 'Close First' }).click();
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');
    expect(tabs().elements()).toHaveLength(1);

    await page.getByRole('button', { name: 'Close Second' }).click();
    await expect.element(page.getByRole('heading', { name: 'No screen open' })).toBeVisible();
    expect(tabs().elements()).toHaveLength(0);

    await navigate('First');
    await expect.element(tab('First')).toHaveAttribute('aria-selected', 'true');
  });

  test('marks the screen of the shown tab in the navigation', async () => {
    render(two, 'first');
    await expect.element(menuItem('First')).toHaveAttribute('aria-current', 'page');
    await menuItem('Second').click();
    await expect.element(menuItem('Second')).toHaveAttribute('aria-current', 'page');
    await expect.element(menuItem('First')).not.toHaveAttribute('aria-current');
  });

  test('marks the screen of the menu the breadcrumb of a tab another screen opened starts from', async () => {
    const grandchild: Screen = { id: 'doc:grandchild', title: 'Grandchild', render: () => <Page title="Grandchild">Grandchild screen</Page> };
    const child: Screen = { id: 'doc:child', title: 'Child', render: (open) => <Page title="Child"><button onClick={() => open(grandchild)}>Open grandchild</button></Page> };
    const opener: Screen = { id: 'opener', title: 'Opener', group: 'Design', render: (open) => <button onClick={() => open(child)}>Open child</button> };
    render([opener, ...plain('Other')], 'opener');
    const marked = () => nav().element().querySelectorAll('.ant-menu-item-selected');
    await button('Open child').click();
    await expect.element(tab('Child')).toHaveAttribute('aria-selected', 'true');
    await expect.element(menuItem('Opener')).toHaveAttribute('aria-current', 'page');
    await expect.element(menuItem('Opener')).toHaveClass('ant-menu-item-selected');
    expect(marked()).toHaveLength(1);

    await button('Open grandchild').click();
    await button('Close Opener').click();
    await expect.element(tab('Grandchild')).toHaveAttribute('aria-selected', 'true');
    await expect.element(menuItem('Opener')).toHaveAttribute('aria-current', 'page');
    await expect.element(menuItem('Opener')).toHaveClass('ant-menu-item-selected');

    await navigate('Other');
    await expect.element(menuItem('Other')).toHaveAttribute('aria-current', 'page');
    await expect.element(menuItem('Opener')).not.toHaveAttribute('aria-current');
    expect(marked()).toHaveLength(1);
  });

  test('opens the tabs a screen asks for, one per id, and forgets one once it is closed', async () => {
    const opener: Screen = {
      id: 'opener',
      title: 'Opener',
      render: (open) => (
        <button type="button" onClick={() => open({ id: 'child', title: 'Child', render: () => <p>Child screen</p> })}>
          Open child
        </button>
      ),
    };
    render([opener], 'opener');
    await page.getByRole('button', { name: 'Open child' }).click();
    await expect.element(tab('Child')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByText('Child screen')).toBeVisible();
    expect(menuItem('Child').elements()).toHaveLength(0);

    await tab('Opener').click();
    await page.getByRole('button', { name: 'Open child' }).click();
    expect(tabs().elements()).toHaveLength(2);

    await page.getByRole('button', { name: 'Close Child' }).click();
    expect(tabs().elements()).toHaveLength(1);
    await page.getByRole('button', { name: 'Open child' }).click();
    await expect.element(page.getByText('Child screen')).toBeVisible();
  });

  test('keeps the menu beside the screens and the tabs in a row of their own under the header', async () => {
    render(two, 'first');
    await expect.element(tab('First')).toBeVisible();
    const header = page.getByRole('banner').element().getBoundingClientRect();
    const menu = page.getByRole('complementary').element().getBoundingClientRect();
    const first = tab('First').element().getBoundingClientRect();
    const screen = page.getByText('First screen').element().getBoundingClientRect();
    expect(menu.top).toBeGreaterThanOrEqual(header.bottom);
    expect(first.top).toBeGreaterThanOrEqual(header.bottom);
    expect(first.left).toBeGreaterThanOrEqual(menu.right);
    expect(screen.top).toBeGreaterThanOrEqual(first.bottom);
    expect(screen.left).toBeGreaterThanOrEqual(menu.right);
  });

  test('collapses the menu to icons with its button, Enter, or Ctrl+B and ⌘B, each item still named', async () => {
    render(two, 'first');
    await expect.element(menuItem('Second')).toBeVisible();
    const wide = menuWidth();
    await button('Collapse menu').click();
    await expect.element(button('Expand menu')).toHaveAttribute('aria-expanded', 'false');
    await expect.poll(menuWidth).toBeLessThan(wide / 2);
    await menuItem('Second').hover();
    await expect.element(page.getByRole('tooltip', { name: 'Second' })).toBeVisible();
    await menuItem('Second').click();
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');

    await userEvent.keyboard('{Control>}b{/Control}');
    await expect.element(button('Collapse menu')).toHaveAttribute('aria-expanded', 'true');
    await expect.poll(menuWidth).toBe(wide);
    await userEvent.keyboard('{Meta>}b{/Meta}');
    await expect.element(button('Expand menu')).toBeVisible();
    button('Expand menu').element().focus();
    await userEvent.keyboard('{Enter}');
    await expect.element(button('Collapse menu')).toHaveFocus();
  });

  test('the breadcrumb of a screen opened from another starts with the group and opens that screen again', async () => {
    const child: Screen = { id: 'doc:child', title: 'Child', render: () => <Page title="Child">Child screen</Page> };
    const opener: Screen = { id: 'opener', title: 'Opener', group: 'Design', render: (open) => <button onClick={() => open(child)}>Open child</button> };
    render([opener, ...plain('Other')], 'opener');
    await button('Open child').click();
    const trail = page.getByRole('navigation', { name: 'Breadcrumb' });
    await expect.element(trail).toBeVisible();
    expect(trail.getByRole('listitem').elements().map((crumb) => crumb.textContent)).toEqual(['Design', 'Opener', 'Child']);
    await expect.element(page.getByRole('heading', { level: 1, name: 'Child' })).toBeVisible();

    await button('Close Opener').click();
    await expect.poll(titles).toEqual(['Child']);
    await trail.getByRole('link', { name: 'Opener' }).click();
    await expect.element(tab('Opener')).toHaveAttribute('aria-selected', 'true');
    await expect.poll(titles).toEqual(['Child', 'Opener']);
  });

  test('the breadcrumb keeps the screens that led to a tab after their tabs close, and each opens again with its own trail', async () => {
    const grandchild: Screen = { id: 'doc:grandchild', title: 'Grandchild', render: () => <Page title="Grandchild">Grandchild screen</Page> };
    const child: Screen = { id: 'doc:child', title: 'Child', render: (open) => <Page title="Child"><button onClick={() => open(grandchild)}>Open grandchild</button></Page> };
    const opener: Screen = { id: 'opener', title: 'Opener', group: 'Design', render: (open) => <button onClick={() => open(child)}>Open child</button> };
    render([opener, ...plain('Other')], 'opener');
    await button('Open child').click();
    await button('Open grandchild').click();
    const trail = page.getByRole('navigation', { name: 'Breadcrumb' });
    const crumbs = () => trail.getByRole('listitem').elements().map((crumb) => crumb.textContent);
    await expect.poll(crumbs).toEqual(['Design', 'Opener', 'Child', 'Grandchild']);

    await button('Close Child').click();
    await button('Close Opener').click();
    await expect.poll(titles).toEqual(['Grandchild']);
    await expect.poll(crumbs).toEqual(['Design', 'Opener', 'Child', 'Grandchild']);

    await trail.getByRole('link', { name: 'Child' }).click();
    await expect.element(tab('Child')).toHaveAttribute('aria-selected', 'true');
    await expect.poll(crumbs).toEqual(['Design', 'Opener', 'Child']);
  });

  test('a screen opened again from a screen it led to keeps the trail it had', async () => {
    const child: Screen = { id: 'doc:child', title: 'Child', render: (open) => <Page title="Child"><button onClick={() => open(grandchild)}>Open grandchild</button></Page> };
    const grandchild: Screen = { id: 'doc:grandchild', title: 'Grandchild', render: (open) => <Page title="Grandchild"><button onClick={() => open(child)}>Back to child</button></Page> };
    const opener: Screen = { id: 'opener', title: 'Opener', group: 'Design', render: (open) => <button onClick={() => open(child)}>Open child</button> };
    render([opener, ...plain('Other')], 'opener');
    await button('Open child').click();
    await button('Open grandchild').click();
    await button('Close Child').click();
    await button('Back to child').click();

    await expect.element(tab('Child')).toHaveAttribute('aria-selected', 'true');
    const crumbs = page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('listitem');
    await expect.poll(() => crumbs.elements().map((crumb) => crumb.textContent)).toEqual(['Design', 'Opener', 'Child']);
  });

  test('quick open finds screens and open documents with the keyboard', async () => {
    const child: Screen = { id: 'doc:orders', title: 'Orders', render: () => <p>Order document</p> };
    const launcher: Screen = {
      ...two[0]!,
      render: (open) => <button onClick={() => open(child)}>Open orders</button>,
    };
    render([launcher, two[1]!], 'first');
    await page.getByRole('button', { name: 'Open orders' }).click();
    await tab('First').click();
    await userEvent.keyboard('{Control>}k{/Control}');
    const search = page.getByRole('textbox', { name: 'Find a screen, a tab or an artifact' });
    await expect.element(search).toHaveFocus();
    await search.fill('missing');
    await expect.element(page.getByText('Nothing matches')).toBeVisible();
    await search.fill('Orders');
    await userEvent.keyboard('{ArrowDown}{Enter}');
    await expect.element(tab('Orders')).toHaveAttribute('aria-selected', 'true');
    expect(tabs().elements()).toHaveLength(2);
    await page.getByRole('button', { name: 'Quick open', exact: true }).click();
    await expect.element(search).toHaveFocus();
    await search.fill('Second');
    await userEvent.keyboard('{Enter}');
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');
  });

  describe('quick open over the artifacts', () => {
    const packages: Screen = { id: 'packages', title: 'Packages', render: () => <p>Packages screen</p> };
    const demo: Screen = { id: 'package:demo', title: 'demo', render: () => <p>The package demo</p> };
    const artifact = (name: string, path: string, kind = 'Flow', screen: Screen = { id: `flow:${path}`, title: name, render: () => <Page title={name}>{`The flow ${name}`}</Page> }): Found =>
      ({ name, path, kind, screen, trail: [packages, demo] });
    const invoice = artifact('invoice', 'demo/flows/invoice.yaml');
    const search = () => page.getByRole('textbox', { name: 'Find a screen, a tab or an artifact' });
    const choice = (name: string) => page.getByRole('dialog', { name: 'Quick open' }).getByRole('button', { name: new RegExp(`^${name}`) });
    const quickOpen = async () => {
      await userEvent.keyboard('{Control>}k{/Control}');
      await expect.element(search()).toHaveFocus();
    };

    test('finds an artifact by its path once something is typed and opens it under its trail, the menu marking where the trail starts', async () => {
      render([...plain('Home'), packages], 'home', async () => [invoice, artifact('order', 'demo/flows/order.yaml')]);
      await quickOpen();
      await expect.element(choice('invoice')).not.toBeInTheDocument();
      await search().fill('flows/inv');
      await expect.element(choice('invoice')).toHaveTextContent('invoiceFlow · demo/flows/invoice.yaml');
      await expect.element(choice('order')).not.toBeInTheDocument();
      await userEvent.keyboard('{Enter}');
      await expect.element(tab('invoice')).toHaveAttribute('aria-selected', 'true');
      await expect.element(page.getByText('The flow invoice')).toBeVisible();
      const crumbs = page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('listitem');
      await expect.poll(() => crumbs.elements().map((crumb) => crumb.textContent)).toEqual(['Workspace', 'Packages', 'demo', 'invoice']);
      await expect.element(menuItem('Packages')).toHaveAttribute('aria-current', 'page');
    });

    test('finds an artifact by its name and lists it once while its tab is open, and a test while its package is open', async () => {
      const test = artifact('order-test', 'demo/tests/order-test.yaml', 'Test', demo);
      render([...plain('Home'), packages], 'home', async () => [invoice, test]);
      await quickOpen();
      await search().fill('order-test');
      await choice('order-test').click();
      await expect.element(tab('demo')).toHaveAttribute('aria-selected', 'true');
      await quickOpen();
      await search().fill('order-test');
      await expect.element(choice('order-test')).toHaveTextContent('order-testTest · demo/tests/order-test.yaml');
      await search().fill('invoice');
      await choice('invoice').click();
      await quickOpen();
      await search().fill('invoice');
      await expect.poll(() => page.getByRole('dialog', { name: 'Quick open' }).getByRole('button', { name: /invoice/ }).elements()).toHaveLength(1);
    });

    test('lists each of two tests of one package, which open the same tab, as a row of its own, with no warning of a repeated key', async () => {
      const error = vi.spyOn(console, 'error');
      try {
        render([...plain('Home'), packages], 'home', async () => [
          artifact('order-test', 'demo/tests/order-test.yaml', 'Test', demo),
          artifact('refund-test', 'demo/tests/refund-test.yaml', 'Test', demo),
        ]);
        await quickOpen();
        await search().fill('-test');
        await expect.element(choice('order-test')).toBeVisible();
        await expect.element(choice('refund-test')).toBeVisible();
        await search().fill('demo/tests');
        await expect.element(choice('refund-test')).toBeVisible();
        expect(page.getByRole('dialog', { name: 'Quick open' }).getByRole('button', { name: /-test/ }).elements()).toHaveLength(2);
        expect(error.mock.calls.filter(([message]) => String(message).includes('same key'))).toEqual([]);
      } finally {
        error.mockRestore();
      }
    });

    test('says while it reads the artifacts, and why it could not, still finding screens', async () => {
      let fail!: (why: Error) => void;
      render([...plain('Home', 'Audit'), packages], 'home', () => new Promise((_, reject) => { fail = reject; }));
      await quickOpen();
      await search().fill('a');
      await expect.element(page.getByRole('status')).toHaveTextContent('Reading the artifacts of the working folder');
      fail(new Error('the node does not answer'));
      await expect.element(page.getByRole('alert')).toHaveTextContent('The artifacts could not be read: the node does not answer');
      await expect.element(choice('Audit')).toBeVisible();
    });

    test('lists at most twenty artifacts and says how many more match', async () => {
      render(plain('Home'), 'home', async () => Array.from({ length: 23 }, (_, n) => artifact(`flow-${n}`, `demo/flows/flow-${n}.yaml`)));
      await quickOpen();
      await search().fill('flow-');
      await expect.element(page.getByText('3 more artifacts match: type more of the name or the path.')).toBeVisible();
      expect(page.getByRole('dialog', { name: 'Quick open' }).getByRole('button', { name: /^flow-/ }).elements()).toHaveLength(20);
    });
  });

  test('a dismissed editor dialog allows Quick open, while a visible one keeps its shortcut', async () => {
    render(two, 'first');
    await expect.element(tab('First')).toBeVisible();
    const dialog = document.createElement('div');
    host!.append(dialog);
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    await userEvent.keyboard('{Meta>}k{/Meta}');
    expect(page.getByRole('textbox').elements()).toHaveLength(0);
    dialog.hidden = true;
    await userEvent.keyboard('{Meta>}k{/Meta}');
    await expect.element(page.getByRole('textbox', { name: 'Find a screen, a tab or an artifact' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
  });

  test('below 1280 pixels wide collapses the menu by itself, and opens it again from 1280', async () => {
    await page.viewport(1279, 800);
    render(two, 'first');
    await expect.element(button('Expand menu')).toHaveAttribute('aria-expanded', 'false');
    await navigate('Second');
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');
    await page.viewport(1280, 800);
    await expect.element(button('Collapse menu')).toHaveAttribute('aria-expanded', 'true');
  });

  test.each([
    ['Close', ['A', 'B', 'D', 'E']],
    ['Close other tabs', ['C']],
    ['Close tabs to the left', ['C', 'D', 'E']],
    ['Close tabs to the right', ['A', 'B', 'C']],
    ['Close all but pinned', []],
  ])('%s in the menu of a tab closes exactly the tabs it names', async (name, left) => {
    await renderOpen(plain('A', 'B', 'C', 'D', 'E'));
    await fromMenu('C', name);
    await expect.poll(titles).toEqual(left);
  });

  test('disables the items of a tab menu that would close nothing', async () => {
    await renderOpen(plain('A', 'B'));
    await tab('A').click({ button: 'right' });
    await expect.element(item('Close tabs to the left')).toHaveAttribute('aria-disabled', 'true');
    await expect.element(item('Close tabs to the right')).not.toHaveAttribute('aria-disabled', 'true');
    await userEvent.keyboard('{Escape}');
    await expect.element(item('Close tabs to the right')).not.toBeInTheDocument();
    await tab('B').click({ button: 'right' });
    await expect.element(item('Close tabs to the right')).toHaveAttribute('aria-disabled', 'true');
    await expect.element(item('Close other tabs')).not.toHaveAttribute('aria-disabled', 'true');
  });

  test('a pinned tab is marked, moves to the start and survives every action that closes several tabs', async () => {
    await renderOpen(plain('A', 'B', 'C', 'D', 'E'));
    await fromMenu('C', 'Pin tab');
    await expect.poll(titles).toEqual(['C', 'A', 'B', 'D', 'E']);
    await expect.element(tab('C pinned')).toBeVisible();

    await tab('A').click({ button: 'right' });
    await expect.element(item('Close tabs to the left')).toHaveAttribute('aria-disabled', 'true');
    await userEvent.keyboard('{Escape}');
    await expect.element(item('Close tabs to the left')).not.toBeInTheDocument();
    await fromMenu('B', 'Close tabs to the left');
    await expect.poll(titles).toEqual(['C', 'B', 'D', 'E']);
    await fromMenu('D', 'Close other tabs');
    await expect.poll(titles).toEqual(['C', 'D']);
    await navigate('E');
    await fromMenu('E', 'Close all but pinned');
    await expect.poll(titles).toEqual(['C']);
    await tab('C pinned').click({ button: 'right' });
    for (const name of ['Close other tabs', 'Close tabs to the left', 'Close tabs to the right', 'Close all but pinned']) {
      await expect.element(item(name)).toHaveAttribute('aria-disabled', 'true');
    }
    await item('Close').click();
    await expect.element(page.getByRole('heading', { name: 'No screen open' })).toBeVisible();
  });

  test('Unpin tab moves the tab after those still pinned and lets the actions close it again', async () => {
    await renderOpen(plain('A', 'B', 'C'));
    await fromMenu('B', 'Pin tab');
    await fromMenu('C', 'Pin tab');
    await expect.poll(titles).toEqual(['B', 'C', 'A']);
    await fromMenu('B pinned', 'Unpin tab');
    await expect.poll(titles).toEqual(['C', 'B', 'A']);
    await expect.element(tab('B')).toBeVisible();
    await fromMenu('A', 'Close all but pinned');
    await expect.poll(titles).toEqual(['C']);
  });

  test('the menu key or Shift+F10 on a focused tab opens its menu, and Escape closes it', async () => {
    await renderOpen(plain('A', 'B'));
    await tab('A').click();
    await userEvent.keyboard('{ContextMenu}');
    await expect.element(item('Close other tabs')).toBeVisible();
    await expect.element(item('Close')).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    await expect.element(item('Close other tabs')).not.toBeInTheDocument();
    // a focused tab's name starts with its place in the row
    await expect.element(page.getByRole('tab', { name: /A$/ })).toHaveFocus();

    await userEvent.keyboard('{Shift>}{F10}{/Shift}');
    await expect.element(item('Close')).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    await expect.element(item('Close other tabs')).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    await expect.poll(titles).toEqual(['A']);
  });

  test('when the row is too narrow, the three dots list the hidden tabs, and choosing one shows it', async () => {
    const list = plain(...'ABCDEFGHIJ'.split('').map((letter) => `Screen ${letter}`));
    render(list, list[0]!.id);
    for (const screen of list.slice(1)) await navigate(screen.title as string);
    await button('Hidden tabs').hover();
    const hidden = page.getByRole('listbox').getByRole('option');
    await expect.element(hidden.first()).toBeVisible();
    // the last tab opened is shown, so the row hides some of the first ones
    const listed = hidden.elements().map((option) => option.textContent);
    expect(listed).toContain('Screen A');
    expect(listed).not.toContain('Screen J');
    await hidden.filter({ hasText: 'Screen A' }).click();
    await expect.element(tab('Screen A')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByText('Screen A screen')).toBeVisible();
  });

  test('closing one tab with unsaved changes asks once: Cancel keeps it, Close without saving closes it unsaved', async () => {
    const draft = edited('Draft');
    await renderOpen([...plain('A'), draft.screen]);
    const question = page.getByRole('dialog', { name: 'Save changes to Draft?' });

    await button('Close Draft').click();
    await expect.element(question).toMatchTextContent(/Draft has unsaved changes\./);
    await question.getByRole('button', { name: 'Cancel' }).click();
    await expect.element(question).not.toBeInTheDocument();
    await expect.poll(titles).toEqual(['A', 'Draft']);

    await fromMenu('Draft', 'Close');
    await question.getByRole('button', { name: 'Close without saving' }).click();
    await expect.poll(titles).toEqual(['A']);
    expect(draft.save).not.toHaveBeenCalled();
  });

  test('Save and close saves the screen of one tab, then closes it', async () => {
    const draft = edited('Draft');
    await renderOpen([...plain('A'), draft.screen]);
    await button('Close Draft').click();
    await page.getByRole('dialog', { name: 'Save changes to Draft?' }).getByRole('button', { name: 'Save and close' }).click();
    await expect.poll(titles).toEqual(['A']);
    expect(draft.save).toHaveBeenCalledOnce();
  });

  test('closing several tabs closes those without unsaved changes at once and asks one question for the others', async () => {
    const one = edited('One');
    const two = edited('Two');
    await renderOpen([...plain('A'), one.screen, ...plain('B'), two.screen]);
    const question = page.getByRole('dialog', { name: 'Save changes to 2 tabs?' });

    await fromMenu('A', 'Close other tabs');
    await expect.element(question.getByRole('list', { name: 'Tabs with unsaved changes' })).toMatchTextContent('OneTwo');
    await expect.poll(titles).toEqual(['A', 'One', 'Two']);
    await question.getByRole('button', { name: 'Cancel' }).click();
    await expect.poll(titles).toEqual(['A', 'One', 'Two']);

    await fromMenu('A', 'Close other tabs');
    await question.getByRole('button', { name: 'Close without saving' }).click();
    await expect.poll(titles).toEqual(['A']);
    expect(one.save).not.toHaveBeenCalled();
    expect(two.save).not.toHaveBeenCalled();
  });

  test('Save all and close saves every listed screen, then closes their tabs', async () => {
    const one = edited('One');
    const two = edited('Two');
    await renderOpen([...plain('A'), one.screen, two.screen]);
    await fromMenu('A', 'Close all but pinned');
    await page.getByRole('dialog', { name: 'Save changes to 2 tabs?' }).getByRole('button', { name: 'Save all and close' }).click();
    await expect.element(page.getByRole('heading', { name: 'No screen open' })).toBeVisible();
    expect(one.save).toHaveBeenCalledOnce();
    expect(two.save).toHaveBeenCalledOnce();
  });

  test('a tab whose save fails stays open and is shown, saying why, while the others close', async () => {
    const failing = edited('Failing', false);
    const fine = edited('Fine');
    await renderOpen([...plain('A'), failing.screen, fine.screen]);
    await fromMenu('A', 'Close tabs to the right');
    await page.getByRole('dialog', { name: 'Save changes to 2 tabs?' }).getByRole('button', { name: 'Save all and close' }).click();
    await expect.element(tab('Failing')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByText('Failing was not saved: the disk is full')).toBeVisible();
    await expect.poll(titles).toEqual(['A', 'Failing']);

    await button('Close Failing').click();
    await page.getByRole('dialog', { name: 'Save changes to Failing?' }).getByRole('button', { name: 'Save and close' }).click();
    await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
    await expect.poll(titles).toEqual(['A', 'Failing']);
  });
});
