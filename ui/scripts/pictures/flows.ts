import type { EndpointFile, EndpointsApi } from '../../src/endpoints/api';
import type { FlowFile, FlowsApi } from '../../src/endpoints/flows/api';

// The working folder that the pictures of the Endpoints and Flows screens show: the endpoints and flows of the orders
// package, read-only. Nothing is ever saved.

const ENDPOINTS: { id: string; scheme: string; direction: 'in' | 'out'; address: string }[] = [
  { id: 'orders-in', scheme: 'http', direction: 'in', address: '/orders' },
  { id: 'erp-out', scheme: 'http', direction: 'out', address: 'https://erp.example/idoc' },
  { id: 'partner-domestic', scheme: 'http', direction: 'out', address: 'https://partner.example/cz' },
  { id: 'partner-foreign', scheme: 'sftp', direction: 'out', address: '/outbox' },
];
const pathOf = (id: string) => `orders/endpoints/${id}.yaml`;

const ORDERS = `schemaVersion: 1
kind: flow
id: orders
version: 1.0.0
description: Validate an order, turn it into an IDoc and send it to the ERP.
from: { endpoint: orders-in }
delivery: { mode: sync }
limits: { maxBodyBytes: 1048576 }
steps:
  - type: xsd
    name: validate
    schema: schemas/order.xsd
  - type: route
    prop: PARTNER
    routes:
      - when: "/Order/Currency = 'CZK'"
        set: domestic
    otherwise: foreign
  - type: to
    name: partner
    byProp: PARTNER
    endpoints:
      domestic: partner-domestic
      foreign: partner-foreign
  - type: to
    name: erp
    endpoint: erp-out
`;
const INVOICES = `schemaVersion: 1
kind: flow
id: invoices
version: 1.0.0
from: { endpoint: orders-in }
delivery: { mode: at-least-once }
steps:
  - type: to
    endpoint: partner-foreign
`;
const FLOWS: Record<string, string> = { 'orders/flows/orders.yaml': ORDERS, 'orders/flows/invoices.yaml': INVOICES };

const context: FlowFile['context'] = {
  endpoints: ENDPOINTS.map(({ id, scheme, direction, address }) => ({ id, path: pathOf(id), scheme, direction, address })),
  flows: [
    { path: 'orders/flows/orders.yaml', id: 'orders', mode: 'sync', address: '/orders' },
    { path: 'orders/flows/invoices.yaml', id: 'invoices', mode: 'at-least-once' },
  ],
  partial: false,
};

const refused = () => Promise.reject(new Error('the pictures save nothing'));

/** The flows of the folder, each as written above. */
export function pictureFlows(): FlowsApi {
  return {
    list: async () => ({
      configured: true,
      editable: false,
      truncated: false,
      items: Object.keys(FLOWS).map((path) => ({ path, id: path.split('/').at(-1)!.replace('.yaml', '') })),
    }),
    read: async (path) => ({ file: { path, text: FLOWS[path] ?? '', editable: false, context }, etag: '"1"' }),
    save: refused,
    steps: async () => [],
    ingress: async () => undefined,
  };
}

/** The endpoints of the folder, and a node that serves the http and sftp components with one setting each. */
export function pictureEndpoints(): EndpointsApi {
  const file = (path: string): EndpointFile => {
    const found = ENDPOINTS.find((e) => pathOf(e.id) === path) ?? ENDPOINTS[0]!;
    return { path, editable: false, id: found.id, scheme: found.scheme, direction: found.direction, description: null, config: { address: found.address }, environments: [] };
  };
  const described = (scheme: string) => ({
    scheme,
    httpPrefix: null,
    settings: {
      format: 1,
      described: true,
      tabs: [{ id: 'general' as const, label: 'General', common: true }],
      fields: [{ name: 'address', type: 'string' as const, help: 'Where the messages go or come from.', tab: 'general', required: true, directions: ['in' as const, 'out' as const] }],
      rules: [],
    },
  });
  return {
    list: async () => ({
      configured: true,
      editable: false,
      truncated: false,
      items: ENDPOINTS.map(({ id, scheme, direction }) => ({ path: pathOf(id), id, scheme, direction })),
    }),
    read: async (path) => ({ file: file(path), etag: '"1"' }),
    save: refused,
    create: refused,
    components: async () => ({ environment: 'dev', httpOrigin: null, items: [described('http'), described('sftp')] }),
  };
}
