import { useEffect, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import type { Screen } from './screens';
import { Shell } from './Shell';
import { useWorkingFile, useWorkingFiles, type WorkingFiles } from './workingFiles';

let root: Root | undefined;
let host: HTMLElement | undefined;
let files: WorkingFiles | undefined;

afterEach(() => {
  root?.unmount();
  host?.remove();
  files = undefined;
});

function render(node: React.ReactNode) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(node);
}

/** An editor of the file at `path`, titled `name`: Edit and Revert change whether it has unsaved changes, and it counts its reloads. */
function Editor({ path, name }: { path: string; name: string }) {
  const [dirty, setDirty] = useState(false);
  const [reloads, setReloads] = useState(0);
  useWorkingFile(path, () => dirty, () => setReloads((n) => n + 1));
  return (
    <>
      <button onClick={() => setDirty(!dirty)}>{dirty ? `Revert ${name}` : `Edit ${name}`}</button>
      <p>
        {name} reloaded {reloads} times
      </p>
    </>
  );
}

/** What gives the test the working files of the shell it is in. */
function Probe() {
  const working = useWorkingFiles();
  useEffect(() => {
    files = working;
  });
  return <p>Probing the working files</p>;
}

const editorTab = (id: string, name: string, path: string): Screen => ({ id: `edit:${id}`, title: name, render: () => <Editor path={path} name={name} /> });
const one = editorTab('one', 'One', 'orders/a.txt');
const two = editorTab('two', 'Two', 'orders/a.txt');
const three = editorTab('three', 'Three', 'orders/b.txt');

/** The probe's screen, which opens the editors' tabs. */
const probe: Screen = {
  id: 'probe',
  title: 'Probe',
  render: (open) => (
    <>
      <Probe />
      {[one, two, three].map((screen) => (
        <button key={screen.id} onClick={() => open(screen)}>{`Open ${screen.title}`}</button>
      ))}
    </>
  ),
};

const button = (name: string) => page.getByRole('button', { name, exact: true });
const tab = (name: string) => page.getByRole('tab', { name, exact: true });
const stateOf = () => Array.from(files!.files().values(), ({ path, dirty }) => ({ path, dirty }));

/** The shell with the probe's tab, then the tabs of `opened`, each shown once, and the probe's tab shown last. */
async function shell(...opened: Screen[]) {
  render(<Shell screens={[probe]} start="probe" />);
  await expect.element(page.getByText('Probing the working files')).toBeVisible();
  await expect.poll(() => files).toBeDefined();
  for (const screen of opened) {
    await button(`Open ${screen.title}`).click();
    await expect.element(page.getByText(`${screen.title} reloaded 0 times`)).toBeVisible();
    await tab('Probe').click();
  }
}

describe('the working files of the shell', () => {
  test('list each file an open tab holds once, dirty when a tab holding it has unsaved changes at the moment they are asked', async () => {
    await shell(one, two, three);
    expect(stateOf()).toEqual([
      { path: 'orders/a.txt', dirty: false },
      { path: 'orders/b.txt', dirty: false },
    ]);

    await tab('Two').click();
    await button('Edit Two').click();
    await expect.element(button('Revert Two')).toBeVisible();

    expect(stateOf()).toEqual([
      { path: 'orders/a.txt', dirty: true },
      { path: 'orders/b.txt', dirty: false },
    ]);
    await button('Revert Two').click();
    await expect.element(button('Edit Two')).toBeVisible();
    expect(files!.files().get('orders/a.txt')?.dirty).toBe(false);
  });

  test('Show selects the tab with unsaved changes of a file two tabs hold, else the first tab holding it', async () => {
    await shell(one, two);
    files!.files().get('orders/a.txt')!.show();
    await expect.element(tab('One')).toHaveAttribute('aria-selected', 'true');

    await tab('Two').click();
    await button('Edit Two').click();
    await tab('Probe').click();
    files!.files().get('orders/a.txt')!.show();

    await expect.element(tab('Two')).toHaveAttribute('aria-selected', 'true');
  });

  test('reload reads the named files again in each tab without unsaved changes, and leaves a tab with some as it is', async () => {
    await shell(one, two, three);
    await tab('Two').click();
    await button('Edit Two').click();
    await expect.element(button('Revert Two')).toBeVisible();

    files!.reload(['orders/a.txt', 'orders/gone.txt']);

    await expect.element(page.getByText('One reloaded 1 times')).toBeInTheDocument();
    await expect.element(page.getByText('Two reloaded 0 times')).toBeInTheDocument();
    await expect.element(page.getByText('Three reloaded 0 times')).toBeInTheDocument();
  });

  test('forget the file of a tab once it closes', async () => {
    await shell(three);
    expect(stateOf()).toEqual([{ path: 'orders/b.txt', dirty: false }]);

    await page.getByRole('button', { name: 'Close Three' }).click();
    await expect.element(tab('Three')).not.toBeInTheDocument();

    expect(stateOf()).toEqual([]);
  });

  test('outside a shell hold no file, and reload does nothing', async () => {
    render(
      <>
        <Probe />
        <Editor path="orders/a.txt" name="Alone" />
      </>,
    );
    await expect.element(page.getByText('Alone reloaded 0 times')).toBeVisible();
    await expect.poll(() => files).toBeDefined();

    files!.reload(['orders/a.txt']);

    expect(stateOf()).toEqual([]);
    await expect.element(page.getByText('Alone reloaded 0 times')).toBeVisible();
  });
});
