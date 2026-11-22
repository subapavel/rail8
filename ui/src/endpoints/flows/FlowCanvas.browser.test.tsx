import { ReactFlowProvider } from '@xyflow/react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { Rail8Theme } from '../../theme';
import { FlowCanvas } from './FlowCanvas';
import { view } from './view';

it('folds, inspects and resizes the real flow, and marks an insertion edge for simulation', async () => {
  const host = document.createElement('div');
  Object.assign(host.style, { width: '1080px', height: '480px' });
  document.body.append(host);
  const root = createRoot(host);
  const inspect = vi.fn();
  const remove = vi.fn();
  const rangeChanged = vi.fn();
  const source = `schemaVersion: 1
kind: flow
id: requests
from: { endpoint: inbox }
steps:
  - type: xsd
    name: Validate
  - type: to
    endpoint: depot
`;
  const parse = (yaml: string) => view(yaml, { endpoints: [], flows: [], steps: [] });
  const flow = parse(source);
  const button = (name: string) => page.getByRole('button', { name, exact: true });
  const render = (sequence: number, picture = flow) => root.render(<Rail8Theme>{() => <ReactFlowProvider>
    <div style={{ width: '1080px', height: '480px' }}>
      <FlowCanvas view={picture} selected="/steps/0" onSelect={vi.fn()} onRemove={remove} inspectSequence={sequence}
        title="Requests" description="Check each order." onFlowSelect={inspect} onInsert={vi.fn()} onRangeChange={rangeChanged} />
    </div>
  </ReactFlowProvider>}</Rail8Theme>);
  try {
    render(0);
    await button('Flow properties').click();
    expect(inspect).toHaveBeenCalledOnce();
    await button('Collapse flow').click();
    await expect.element(page.getByText('Check each order.')).toBeVisible();
    await expect.element(page.getByText('depot', { exact: true })).toBeVisible();
    await expect.element(page.getByText('Validate', { exact: true })).not.toBeInTheDocument();
    await button('Expand flow').click();
    const frame = () => host.querySelector('[data-id="flow-frame"]')!;
    const width = frame().getBoundingClientRect().width;
    await button('Resize flow frame').click();
    await userEvent.keyboard('{ArrowRight}');
    await expect.poll(() => frame().getBoundingClientRect().width).toBeGreaterThan(width);
    await button('Actions before Validate').click();
    await page.getByRole('menuitem', { name: 'Set simulation start here' }).click();
    await expect.element(button('Actions before Validate')).toHaveAttribute('title', 'Simulation starts before Validate');
    await button('Actions before depot').click();
    await page.getByRole('menu', { name: 'Actions before depot' }).getByRole('menuitem', { name: 'Set simulation end here' }).click();
    await expect.element(button('Actions before depot')).toHaveAttribute('title', 'Simulation ends before depot');
    expect(rangeChanged).toHaveBeenLastCalledWith(expect.objectContaining({ startIndex: 0, endIndex: 2 }));
    const tile = button('Inspect Validate');
    const station = tile.element().closest('.react-flow__node') as HTMLElement;
    const initialPosition = station.style.transform;
    await userEvent.dragAndDrop(tile, tile, { sourcePosition: { x: 4, y: 8 }, targetPosition: { x: 40, y: 20 } });
    await expect.poll(() => station.style.transform).not.toBe(initialPosition);
    const moved = station.style.transform;
    render(0, parse(source.replace('name: Validate', 'name: Validate\n    schema: schemas/new.xsd')));
    await expect.element(button('Actions before Validate')).toHaveAttribute('title', 'Simulation starts before Validate');
    expect(station.style.transform).toBe(moved);
    render(0, parse(source.replace('name: Validate', 'name: Check request').replace('endpoint: depot', 'endpoint: depot-v2')));
    await expect.element(button('Actions before Check request')).toHaveAttribute('title', 'Simulation starts before Check request');
    await expect.element(button('Actions before depot-v2')).toHaveAttribute('title', 'Simulation ends before depot-v2');
    expect(station.style.transform).toBe(moved);
    render(0);
    await button('Actions before Validate').click();
    await page.getByRole('menuitem', { name: 'Delete Validate' }).click();
    expect(remove.mock.calls[0]?.[0].path).toEqual(['steps', 0]);
    await button('Collapse flow').click();
    render(1);
    await expect.element(button('Inspect Validate')).toBeVisible();
    await button('Zoom out').click();
    const camera = host.querySelector('.react-flow__viewport') as HTMLElement;
    const before = camera.style.transform;
    host.hidden = true;
    await expect.poll(() => host.clientWidth).toBe(0);
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
    expect(camera.isConnected).toBe(true);
    host.hidden = false;
    await expect.element(button('Inspect Validate')).toBeVisible();
    expect(host.querySelector('.react-flow__viewport')).toBe(camera);
    expect(camera.style.transform).toBe(before);
    render(1, parse(source.replace('  - type: xsd', '  - type: jq\n    name: Prepare\n  - type: xsd')));
    await expect.element(button('Actions before depot')).toHaveAttribute('title', 'Actions before depot');
    expect(rangeChanged).toHaveBeenLastCalledWith(null);
    render(1);
    await expect.element(button('Actions before Validate')).toHaveAttribute('title', 'Actions before Validate');
  } finally {
    root.unmount();
    host.remove();
  }
});

it('reveals the exact external receiver found outside a long diagram at its minimum zoom', async () => {
  const host = document.createElement('div');
  Object.assign(host.style, { width: '580px', height: '320px' });
  document.body.append(host);
  const root = createRoot(host);
  const steps = Array.from({ length: 48 }, (_, i) => `  - type: jq\n    name: map-${i}`).join('\n');
  const picture = view(`schemaVersion: 1\nkind: flow\nid: long\nfrom: { endpoint: orders }\nsteps:\n${steps}\n  - type: to\n    endpoint: partner`,
    { endpoints: [], flows: [], steps: [] });
  const draw = (request: number) => root.render(<ReactFlowProvider><FlowCanvas view={picture} onSelect={vi.fn()}
    selected="/steps/48" inspectSequence={request} inspectTarget="receiver:/steps/48" /></ReactFlowProvider>);
  const receiver = page.getByRole('button', { name: 'Inspect partner' });
  const right = () => receiver.element().getBoundingClientRect().right;
  try {
    draw(0);
    await expect.element(page.getByText('12%', { exact: true })).toBeVisible();
    expect(right()).toBeGreaterThan(host.getBoundingClientRect().right);
    draw(1);
    await expect.poll(right).toBeLessThan(host.getBoundingClientRect().right);
    host.style.height = '220px';
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
    const bounds = receiver.element().getBoundingClientRect();
    expect(bounds.right).toBeLessThan(host.getBoundingClientRect().right);
    expect(bounds.bottom).toBeLessThan(host.getBoundingClientRect().bottom);
  } finally {
    root.unmount();
    host.remove();
  }
});

it('selects only the clicked card when a send step and its receiver share the same settings path', async () => {
  const host = document.createElement('div');
  Object.assign(host.style, { width: '800px', height: '320px' });
  document.body.append(host);
  const root = createRoot(host);
  const picture = view('schemaVersion: 1\nkind: flow\nid: ship\nfrom: { endpoint: in }\nsteps:\n  - type: to\n    name: Deliver\n    endpoint: depot',
    { endpoints: [], flows: [], steps: [] });
  const station = page.getByRole('button', { name: 'Inspect Deliver' });
  const receiver = page.getByRole('button', { name: 'Inspect depot' });
  try {
    root.render(<ReactFlowProvider><FlowCanvas view={picture} selected="/steps/0" onSelect={vi.fn()} /></ReactFlowProvider>);
    await station.click();
    await expect.element(station).toHaveAttribute('aria-pressed', 'true');
    await expect.element(receiver).toHaveAttribute('aria-pressed', 'false');
    await receiver.click();
    await expect.element(receiver).toHaveAttribute('aria-pressed', 'true');
    await expect.element(station).toHaveAttribute('aria-pressed', 'false');
  } finally {
    root.unmount();
    host.remove();
  }
});
