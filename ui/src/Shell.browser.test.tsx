import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { home } from './Home';
import { screenList, type Screen } from './screens';
import { Shell } from './Shell';

// the screens the application lists, found as main.tsx finds them
const screens = screenList(home, import.meta.glob('./*/module.tsx', { eager: true }));

const two: readonly Screen[] = [
  { id: 'first', title: 'First', render: () => <p>First screen</p> },
  { id: 'second', title: 'Second', render: () => <p>Second screen</p> },
];

let root: Root | undefined;
let host: HTMLElement | undefined;

function render(list: readonly Screen[], start?: string) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={list} start={start} />);
}

afterEach(async () => {
  root?.unmount();
  host?.remove();
  await page.viewport(1280, 800);
});

const nav = () => page.getByRole('navigation', { name: 'Screens' });
const expand = () => page.getByRole('button', { name: 'Expand navigation' });
const menuItem = (name: string) => nav().getByRole('menuitem', { name, exact: true });
async function navigate(name: string) {
  await expand().click();
  await menuItem(name).click();
}
const tab = (name: string) => page.getByRole('tab', { name });
const tabs = () => page.getByRole('tab');

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
    await expect.element(page.getByRole('banner').getByRole('img', { name: 'Rail8' })).toBeVisible();
    await expect.element(tab('Home')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('heading', { level: 1, name: 'Rail8' })).toBeVisible();
  });

  test('lists Home, then the screens of the modules in their order', async () => {
    render(screens, screens[0]?.id);
    await expand().click();
    await expect.element(menuItem('Home')).toBeVisible();
    expect(nav().getByRole('menuitem').elements().map((button) => button.textContent)).toEqual(['Home', 'Messages', 'Endpoints', 'Flows', 'Mappings']);
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
    await expand().click();
    await expect.element(menuItem('First')).toHaveAttribute('aria-current', 'page');
    await menuItem('Second').click();
    await expand().click();
    await expect.element(menuItem('Second')).toHaveAttribute('aria-current', 'page');
    await expect.element(menuItem('First')).not.toHaveAttribute('aria-current');
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
    await expand().click();
    expect(menuItem('Child').elements()).toHaveLength(0);
    await userEvent.keyboard('{Escape}');

    await tab('Opener').click();
    await page.getByRole('button', { name: 'Open child' }).click();
    expect(tabs().elements()).toHaveLength(2);

    await page.getByRole('button', { name: 'Close Child' }).click();
    expect(tabs().elements()).toHaveLength(1);
    await page.getByRole('button', { name: 'Open child' }).click();
    await expect.element(page.getByText('Child screen')).toBeVisible();
  });

  test('asks a screen before its tab closes, and closes it only when the screen says so', async () => {
    let closeLater: (() => void) | undefined;
    const guarded: Screen = {
      id: 'guarded',
      title: 'Guarded',
      render: () => <p>Guarded screen</p>,
      beforeClose: (close) => (closeLater = close),
    };
    render([guarded, two[0]!], 'guarded');
    await navigate('First');
    await tab('Guarded').click();

    await page.getByRole('button', { name: 'Close Guarded' }).click();
    await expect.element(page.getByText('Guarded screen')).toBeVisible();
    expect(tabs().elements()).toHaveLength(2);

    closeLater!();
    await expect.element(tab('First')).toHaveAttribute('aria-selected', 'true');
    expect(tabs().elements()).toHaveLength(1);
  });

  test('keeps the tabs in the toolbar row and overlays the navigation without moving the screen', async () => {
    render(two, 'first');
    await expect.element(tab('First')).toBeVisible();
    const bounds = () => page.getByRole('main').element().getBoundingClientRect();
    const before = bounds();
    const header = page.getByRole('banner').element().getBoundingClientRect();
    const first = tab('First').element().getBoundingClientRect();
    expect(first.top).toBeLessThan(header.bottom);
    expect(first.bottom).toBeGreaterThan(header.top);
    const trigger = expand().element();
    const background = getComputedStyle(trigger).backgroundColor;
    expect(background).not.toBe('rgba(0, 0, 0, 0)');
    expect(background).toBe(getComputedStyle(trigger.closest('aside')!).backgroundColor);
    expect(nav().elements()).toHaveLength(0);
    await expand().click();
    await expect.element(menuItem('First')).toBeVisible();
    expect(bounds().toJSON()).toEqual(before.toJSON());
    await userEvent.keyboard('{Escape}');
    await expect.element(expand()).toHaveFocus();
    await expand().click();
    await page.getByText('First screen').click();
    await expect.element(expand()).toHaveAttribute('aria-expanded', 'false');
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
    const search = page.getByRole('textbox', { name: 'Find a screen or open tab' });
    await expect.element(search).toHaveFocus();
    await search.fill('missing');
    await expect.element(page.getByText('No matching screens or tabs')).toBeVisible();
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
    await expect.element(page.getByRole('textbox', { name: 'Find a screen or open tab' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
  });

  test('on a narrow window hides the navigation until the Navigation button shows it', async () => {
    await page.viewport(414, 896);
    render(two, 'first');
    await expect.element(expand()).toHaveAttribute('aria-expanded', 'false');
    await navigate('Second');
    await expect.element(tab('Second')).toHaveAttribute('aria-selected', 'true');
  });
});
