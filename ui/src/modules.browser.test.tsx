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

  test('the endpoint and the mapping read a change as "changed"', async () => {
    for (const kind of ['endpoint', 'mapping']) {
      const { Change } = kinds.get(kind)!;
      const host = document.createElement('div');
      document.body.append(host);
      const root = createRoot(host);
      root.render(<Change path="orders/a.yaml" before={null} after={null} />);
      await expect.element(page.getByText('changed', { exact: true })).toBeVisible();
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
