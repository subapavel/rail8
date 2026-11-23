import { createRoot } from 'react-dom/client';
import { describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import { artifactKinds } from './artifacts';

const kinds = artifactKinds(import.meta.glob('./*/module.tsx', { eager: true }));

describe('the artifact kinds the modules register', () => {
  test('are the flow, the endpoint and the mapping', () => {
    expect([...kinds.keys()].sort()).toEqual(['endpoint', 'flow', 'mapping']);
  });

  test('each opens the tab its list opens for the same file', () => {
    expect(kinds.get('flow')!.open('orders/flows/orders.yaml')).toMatchObject({ id: 'flow:orders/flows/orders.yaml', title: 'orders' });
    expect(kinds.get('endpoint')!.open('orders/endpoints/in.yml')).toMatchObject({ id: 'endpoint:orders/endpoints/in.yml', title: 'in' });
    expect(kinds.get('mapping')!.open('orders/mappings/order.mapping.json')).toMatchObject({ id: 'mapping:orders/mappings/order' });
  });

  test('the endpoint and the mapping draw their own change', async () => {
    const side = (rev: string, text: string) => ({ rev, text, artifacts: async () => [], read: async () => null });
    const endpoint = 'schemaVersion: 1\nkind: endpoint\nid: in\nscheme: http\ndirection: in\nconfig:\n  path: /orders\n';
    const mapping = '{"formatVersion": 1}';
    for (const [kind, path, before, after, says] of [
      ['endpoint', 'orders/endpoints/in.yaml', endpoint, `# the orders\n${endpoint}`, 'Only comments or formatting changed.'],
      ['mapping', 'orders/mappings/order.mapping.json', mapping, `${mapping}\n`, 'Only the formatting changed.'],
    ] as const) {
      const { Change } = kinds.get(kind)!;
      const host = document.createElement('div');
      document.body.append(host);
      const root = createRoot(host);
      root.render(<Change path={path} before={side('a'.repeat(40), before)} after={side('work', after)} />);
      await expect.element(page.getByText(says)).toBeVisible();
      root.unmount();
      host.remove();
    }
  });

  test('the flow draws its own change', async () => {
    const { Change } = kinds.get('flow')!;
    const side = (rev: string, text: string) => ({ rev, text, artifacts: async () => [], read: async () => null });
    const text = 'schemaVersion: 1\nkind: flow\nid: orders\nsteps:\n  - type: jq\n';
    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    root.render(<Change path="orders/flows/orders.yaml" before={side('a'.repeat(40), text)} after={side('work', `# the orders\n${text}`)} />);
    await expect.element(page.getByText('Only comments or formatting changed.')).toBeVisible();
    root.unmount();
    host.remove();
  });
});
