import { createRoot } from 'react-dom/client';
import { describe, expect, test, vi } from 'vitest';
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

  test('each lists its files of the working folder, leaving out one that cannot be read; the flow and the mapping write new ones', async () => {
    const answers: Record<string, unknown> = {
      '/files/flows': { configured: true, editable: true, truncated: false, items: [{ path: 'orders/flows/orders.yaml', id: 'orders' }, { path: 'orders/flows/bad.yaml', problem: 'not YAML' }] },
      '/files/endpoints': { configured: true, editable: true, truncated: false, items: [{ path: 'package/endpoints/in.yaml', id: 'in', scheme: 'http', direction: 'in' }] },
      '/api/mappings': [{ id: 'mappings/order-id-copy' }],
    };
    vi.stubGlobal('fetch', async (url: string) => new Response(JSON.stringify(answers[url]), { headers: { 'content-type': 'application/json' } }));
    try {
      expect(await kinds.get('flow')!.list!()).toEqual([{ path: 'orders/flows/orders.yaml', kind: 'flow', id: 'orders' }]);
      expect(await kinds.get('endpoint')!.list!()).toEqual([{ path: 'package/endpoints/in.yaml', kind: 'endpoint', id: 'in' }]);
      expect(await kinds.get('mapping')!.list!()).toEqual([{ path: 'mappings/order-id-copy.mapping.json', kind: 'mapping', id: 'order-id-copy' }]);
    } finally {
      vi.unstubAllGlobals();
    }
    expect([...kinds.values()].filter((kind) => kind.New !== undefined).map((kind) => kind.kind).sort()).toEqual(['flow', 'mapping']);
  });
});
