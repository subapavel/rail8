import type { EndpointFile, EndpointsApi } from '../../src/endpoints/api';
import type { FlowFile, FlowsApi } from '../../src/endpoints/flows/api';
import { DIRECT_FORMS, HTTP_FORMS, SFTP_FORMS } from '../../src/endpoints/fake/forms';
import type { Options } from '../../src/endpoints/settings';

// The working folder that the pictures of the Endpoints and Flows screens show: the endpoints and flows of the orders
// package, read-only. Nothing is ever saved.

const ENDPOINTS: { id: string; scheme: string; direction: 'in' | 'out'; address: string }[] = [
  { id: 'orders-in', scheme: 'http', direction: 'in', address: '/orders' },
  { id: 'erp-out', scheme: 'http', direction: 'out', address: 'https://erp.example/idoc' },
  { id: 'partner-domestic', scheme: 'http', direction: 'out', address: 'https://partner.example/cz' },
  { id: 'partner-foreign', scheme: 'sftp', direction: 'out', address: '/outbox' },
];
const pathOf = (id: string) => `orders/endpoints/${id}.yaml`;

/** The settings each endpoint file holds, beside its address; the form of an endpoint shows them. */
const CONFIG: Record<string, Options> = {
  'erp-out': { url: 'https://erp.example/idoc', timeoutMs: 10000, maxBodyBytes: 1048576, path: '/orders' },
};

/** An endpoint the form shows but no flow uses: the partner's files the invoices are read from. */
const INVOICE_FILES = {
  id: 'invoice-files',
  scheme: 'sftp',
  direction: 'in' as const,
  config: { host: 'sftp.partner.example', user: 'rail8', privateKey: 'secret://partner.private-key', hostKey: 'secret://partner.host-key', dir: '/outbox/invoices', glob: '*.xml', afterSuccess: 'move', moveTo: '/outbox/done' },
};

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
/** A flow of schemaVersion 2: it polls the billing system's SFTP server and sends to the partner's ERP by connection. */
export const INVOICE_OUT = `schemaVersion: 2
kind: flow
id: invoice-out
version: 1.1.0
description: Take each invoice file from the billing system and send it to the partner's ERP.
from:
  adapter: sftp
  connection: billing/sftp
  settings: { dir: /outbox/invoices, afterSuccess: delete }
delivery: { mode: at-least-once }
limits: { maxBodyBytes: 1048576 }
steps:
  - type: extractKey
    name: extractKey
    key: invoiceId
    xpath: /Invoice/Id
  - type: to
    name: partner
    retryable: true
    adapter: http
    connection: acme/erp/rest
    settings: { path: /invoices }
`;
const FLOWS: Record<string, string> = {
  'orders/flows/orders.yaml': ORDERS,
  'orders/flows/invoices.yaml': INVOICES,
  'orders/billing/invoices/invoice-out.yaml': INVOICE_OUT,
};

const context: FlowFile['context'] = {
  endpoints: ENDPOINTS.map(({ id, scheme, direction, address }) => ({ id, path: pathOf(id), scheme, direction, address })),
  flows: [
    { path: 'orders/flows/orders.yaml', id: 'orders', mode: 'sync', address: '/orders' },
    { path: 'orders/flows/invoices.yaml', id: 'invoices', mode: 'at-least-once' },
    { path: 'orders/billing/invoices/invoice-out.yaml', id: 'invoice-out', mode: 'at-least-once' },
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
    // the flow of schemaVersion 2 as a folder that could be written shows it, so the editor says why it shows it read-only
    read: async (path) => ({ file: { path, text: FLOWS[path] ?? '', editable: FLOWS[path] === INVOICE_OUT, context }, etag: '"1"' }),
    save: refused,
    steps: async () => [],
    ingress: async () => undefined,
  };
}

/** The endpoints of the folder, and a node that serves the http, sftp and direct components as they describe themselves. */
export function pictureEndpoints(): EndpointsApi {
  const file = (path: string): EndpointFile => {
    if (path === pathOf(INVOICE_FILES.id)) return { path, editable: false, ...INVOICE_FILES, description: 'The invoices the partner leaves on its server.', environments: [] };
    const found = ENDPOINTS.find((e) => pathOf(e.id) === path) ?? ENDPOINTS[0]!;
    return { path, editable: false, id: found.id, scheme: found.scheme, direction: found.direction, description: null, config: CONFIG[found.id] ?? { address: found.address }, environments: [] };
  };
  return {
    list: async () => ({
      configured: true,
      editable: false,
      truncated: false,
      items: [...ENDPOINTS, INVOICE_FILES].map(({ id, scheme, direction }) => ({ path: pathOf(id), id, scheme, direction })),
    }),
    read: async (path) => ({ file: file(path), etag: '"1"' }),
    save: refused,
    create: refused,
    components: async () => ({
      environment: 'dev',
      httpOrigin: null,
      items: [
        { scheme: 'http', httpPrefix: '/http', settings: HTTP_FORMS },
        { scheme: 'sftp', httpPrefix: null, settings: SFTP_FORMS },
        { scheme: 'direct', httpPrefix: null, settings: DIRECT_FORMS },
      ],
    }),
  };
}
