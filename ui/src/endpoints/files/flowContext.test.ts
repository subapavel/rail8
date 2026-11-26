import { describe, expect, it } from 'vitest';
import { contextOf, surveyOf } from './flowContext';

const flow = (id: string, from: string) =>
  `schemaVersion: 2\nkind: flow\nid: ${id}\nversion: 0.1.0\nfrom: ${from}\ndelivery: { mode: sync }\nlimits: { maxBodyBytes: 1 }\nsteps:\n  - { type: to, adapter: http, connection: acme/erp/rest }\n`;

describe('the context of a flow of schemaVersion 2', () => {
  it('gives a flow whose sender is direct the address it serves, and none to another sender or to a system file', () => {
    const survey = surveyOf([
      { path: 'demo/package.yaml', text: 'schemaVersion: 1\nkind: package\nname: demo\nversion: 0.1.0\n' },
      { path: 'demo/flows/orders.yaml', text: flow('orders', '{ adapter: direct, settings: { address: /orders } }') },
      { path: 'demo/flows/inbox.yaml', text: flow('inbox', '{ adapter: sftp, connection: billing/sftp, settings: { address: /not-served } }') },
      { path: 'demo/systems/billing.yaml', text: 'schemaVersion: 1\nkind: system\nname: billing\nconnections:\n  sftp: { adapter: sftp }\n' },
    ]);
    expect(contextOf(survey, 'demo/flows/orders.yaml', false)).toEqual({
      endpoints: [],
      flows: [
        { path: 'demo/flows/orders.yaml', id: 'orders', mode: 'sync', address: '/orders' },
        { path: 'demo/flows/inbox.yaml', id: 'inbox', mode: 'sync' },
      ],
      partial: false,
    });
  });
});
