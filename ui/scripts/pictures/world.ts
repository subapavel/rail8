import type { PackagesApi } from '../../src/packages/api';
import { fakeStateOf, type Seed } from '../../src/packages/contract/seed';
import { fakePackagesApi } from '../../src/packages/fake';

/** The time the pictures are taken at: every age on the screens is told against it. */
export const NOW = new Date('2026-11-23T10:00:00Z');

const flow = (id: string, endpoint: string, steps: string) =>
  `schemaVersion: 1\nkind: flow\nid: ${id}\nversion: 1.0.0\nfrom:\n  endpoint: ${endpoint}\ndelivery:\n  mode: sync\nlimits:\n  maxBodyBytes: 1048576\nsteps:\n${steps}`;
const endpoint = (id: string, scheme: string, direction: string, address: string) =>
  `schemaVersion: 1\nkind: endpoint\nid: ${id}\nscheme: ${scheme}\ndirection: ${direction}\nconfig:\n  address: ${address}\n`;
const test = (forFlow: string, send: string, reply: string) =>
  `schemaVersion: 1\nkind: test\nflow: ${forFlow}\nmocks:\n  erp-out: { reply: ${reply} }\ncases:\n  - name: one order\n    send: ${send}\n    expect: { steps: 3 }\n`;
const mapping = (target: string, fields: number) =>
  `${JSON.stringify({ formatVersion: 1, target: { root: { element: target } }, targetFieldMappings: Object.fromEntries(Array.from({ length: fields }, (_, n) => [`/${target}/Field${n}`, { boxes: {} }])) }, null, 2)}\n`;
const lock = (version: string, more = '') =>
  `schemaVersion: 1\nkind: lock\ndependencies:\n  - name: "common"\n    version: "${version}"\n    content: "sha256:${'4'.repeat(64)}"\n    repository: "common"\n    commit: "${'c'.repeat(40)}"\n${more}`;
/** The commits of the package customers, oldest first: the node runs the second, and the last is its `HEAD`. */
const CUSTOMER_COMMITS = ['Start the customers package', 'Match customers by their VAT id', "Keep each customer's language"];
/** The pin of the package customers at its `HEAD` as the pictures show it. */
const CUSTOMERS_PIN = `  - name: "customers"\n    version: "1.0.0"\n    content: "sha256:${'5'.repeat(64)}"\n    repository: "customers"\n    commit: "${idFor(`customers/${CUSTOMER_COMMITS[2]}`)}"\n`;
/** A package the node runs from a repository outside the working folder, pinning the commit `pinned` of customers. */
const consumerOf = (name: string, commit: string, pinned: string) => ({
  repository: name,
  commit: commit.repeat(40),
  dependencies: [{ name: 'customers', repository: 'customers', commit: pinned, content: `sha256:${'5'.repeat(64)}` }],
});

const ORDERS = flow(
  'orders',
  'orders-in',
  '  - type: xsd\n    name: validate\n    schema: schemas/order.xsd\n  - type: xslt\n    name: toIdoc\n    stylesheet: flows/orders.xsl\n  - type: to\n    name: erp\n    endpoint: erp-out\n',
);
const INVOICES = flow('invoices', 'erp-in', '  - type: xslt\n    name: toPartner\n    stylesheet: flows/invoices.xsl\n  - type: to\n    name: partner\n    endpoint: partner-out\n');
const XSL = '<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">\n  <xsl:template match="/Order">\n    <ORDERS05/>\n  </xsl:template>\n</xsl:stylesheet>\n';

/** The commits of `main`, oldest first: each one's subject, the files it writes (null deletes one), its author and its date. */
const COMMITS: { subject: string; files: Record<string, string | null>; author: string; at: string }[] = [
  { subject: 'Start the orders package', files: {}, author: 'Jana Novak', at: '2026-11-09T08:41:00+01:00' },
  { subject: 'Add the ERP endpoints', files: { 'endpoints/erp-out.yaml': endpoint('erp-out', 'http', 'out', 'https://erp.example/idoc'), 'endpoints/erp-in.yaml': endpoint('erp-in', 'http', 'in', '/erp') }, author: 'Jana Novak', at: '2026-11-10T14:05:00+01:00' },
  { subject: 'Map orders to the IDoc ORDERS05', files: { 'mappings/order-to-idoc.mapping.json': mapping('ORDERS05', 12) }, author: 'Petr Svoboda', at: '2026-11-12T10:30:00+01:00' },
  { subject: 'Add the invoices flow', files: { 'flows/invoices.yaml': INVOICES, 'flows/invoices.xsl': XSL, 'endpoints/partner-out.yaml': endpoint('partner-out', 'sftp', 'out', '/outbox'), 'mappings/invoice-to-partner.mapping.json': mapping('Invoice', 8) }, author: 'Eva Horak', at: '2026-11-16T16:12:00+01:00' },
  { subject: 'Test the happy path of an order', files: { 'tests/orders.test.yaml': test('orders', 'fixtures/order-4711.xml', 'fixtures/erp-ack.xml'), 'fixtures/erp-ack.xml': '<ack/>\n' }, author: 'Petr Svoboda', at: '2026-11-18T09:20:00+01:00' },
  { subject: 'Retry the ERP call three times before the message is dead', files: { 'flows/orders.yaml': ORDERS.replace('    endpoint: erp-out\n', '    endpoint: erp-out\n    retryable: true\n') }, author: 'Jana Novak', at: '2026-11-20T15:47:00+01:00' },
  { subject: 'Pin common 1.2.0', files: { 'package.lock': lock('1.2.0') }, author: 'Eva Horak', at: '2026-11-21T11:03:00+01:00' },
];

/**
 * The working folder the pictures show, in the terms of the contract suite's seed: the package `orders` with seven
 * commits on `main`, two of them not yet on its upstream, which has one of its own; the node runs the fifth commit;
 * and eight files differ from the last commit, of every kind but `package.yaml` and `folder.yaml`. Its folder `billing`
 * nests two folders, each with a flow, and it and its folder `invoices` have a namespace, as `flows` has. Of its three
 * dependencies, `customers` is pinned, `common` has moved since its pin, and `ledger` has no folder. The package
 * `customers` runs on the node one commit behind its `HEAD`, and two packages the node runs from other repositories pin
 * it: `billing` at the commit that runs, `shipping` one commit behind it. `common` runs nowhere, and two folders that are
 * no package are listed below them: `mappings`, which holds the fake mapping service's mappings, and `notes`.
 * The node's pool has three nodes: `node-b` still runs the commit before the pool's record of `orders`, as it could not
 * fetch the record's commit.
 */
const SEED: Seed = {
  clones: [
    {
      folder: 'orders',
      manifest: 'schemaVersion: 1\nkind: package\nname: orders\nversion: 2.4.0\ndescription: Orders from the web shop to the ERP, and its invoices back.\ndependencies:\n  - name: common\n    version: 1.2.1\n  - name: customers\n    version: 1.0.0\n  - name: ledger\n    version: 2.0.0\n',
      files: {
        'flows/folder.yaml': 'schemaVersion: 1\nkind: folder\nnamespace: urn:acme:orders\ndescription: The flows of the orders package.\n',
        'billing/folder.yaml': 'schemaVersion: 1\nkind: folder\nnamespace: urn:acme:orders:billing\ndescription: What the orders bill.\n',
        'billing/invoices/folder.yaml': 'schemaVersion: 1\nkind: folder\nnamespace: urn:acme:orders:billing:invoices\n',
        'billing/invoices/invoice-out.yaml': flow('invoice-out', 'erp-in', '  - type: to\n    name: partner\n    endpoint: partner-out\n'),
        'billing/credit-notes/credit-note-out.yaml': flow('credit-note-out', 'erp-in', '  - type: to\n    name: partner\n    endpoint: partner-out\n'),
        'flows/orders.yaml': ORDERS,
        'flows/orders.xsl': XSL,
        'schemas/order.xsd': '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>\n',
        'endpoints/orders-in.yaml': endpoint('orders-in', 'http', 'in', '/orders'),
        'endpoints/invoice-files.yaml': endpoint('invoice-files', 'sftp', 'in', '/inbox'),
        'tests/legacy.test.yaml': test('orders', 'fixtures/legacy-order.xml', 'fixtures/legacy-ack.xml'),
        'package.lock': lock('1.1.0'),
      },
      commits: COMMITS.map((c) => c.subject),
      // each labelled commit would also add `<subject>.txt`: it writes only its own files
      commitFiles: Object.fromEntries(COMMITS.map((c) => [c.subject, { [`${c.subject}.txt`]: null, ...c.files }])),
      upstream: { at: 'Test the happy path of an order', more: ['Round VAT to two decimals'] },
      moves: { 'flows/orders.xsl': 'flows/order-to-idoc.xsl' },
      staged: {
        'flows/invoices.yaml': INVOICES.replace('mode: sync', 'mode: async'),
        'package.lock': lock('1.2.1', CUSTOMERS_PIN),
      },
      edits: {
        'flows/orders.yaml': ORDERS.replace('flows/orders.xsl', 'flows/order-to-idoc.xsl').replace('    endpoint: erp-out\n', '    endpoint: erp-out\n    retryable: true\n').replace('maxBodyBytes: 1048576', 'maxBodyBytes: 4194304'),
        'endpoints/webshop-in.yaml': endpoint('webshop-in', 'http', 'in', '/webshop/orders'),
        'mappings/order-to-idoc.mapping.json': mapping('ORDERS05', 14),
        'tests/legacy.test.yaml': null,
        'tests/orders.test.yaml': test('orders', 'fixtures/order-4711.xml', 'fixtures/erp-ack.xml').replace('steps: 3', 'steps: 4'),
        'fixtures/order-4711.xml': '<Order><Id>4711</Id></Order>\n',
      },
    },
    { folder: 'common', manifest: 'schemaVersion: 1\nkind: package\nname: common\nversion: 1.2.1\n', commits: ['Share the VAT rounding'], commitFiles: { 'Share the VAT rounding': { 'Share the VAT rounding.txt': null, 'flows/vat.xsl': XSL } } },
    {
      folder: 'customers',
      commits: CUSTOMER_COMMITS,
      commitFiles: {
        [CUSTOMER_COMMITS[0]!]: { [`${CUSTOMER_COMMITS[0]}.txt`]: null, 'flows/customers.yaml': flow('customers', 'crm-in', '  - type: to\n    name: erp\n    endpoint: erp-out\n') },
        [CUSTOMER_COMMITS[1]!]: { [`${CUSTOMER_COMMITS[1]}.txt`]: null, 'flows/customers.xsl': XSL },
        [CUSTOMER_COMMITS[2]!]: { [`${CUSTOMER_COMMITS[2]}.txt`]: null, 'schemas/customer.xsd': '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>\n' },
      },
    },
  ],
  plain: ['mappings', 'notes'],
  sources: [
    { path: 'acme/invoices.git', commits: ['Start the invoices package', 'Send each invoice to the ledger'] },
    { path: 'acme/payroll.git', fails: 'credentials' },
  ],
  node: [
    { repository: 'orders', commit: 'Test the happy path of an order', dependencies: [{ name: 'common', repository: 'common', commit: 'c'.repeat(40), content: `sha256:${'4'.repeat(64)}` }] },
    { repository: 'customers', commit: CUSTOMER_COMMITS[1]! },
    consumerOf('billing', 'b', CUSTOMER_COMMITS[1]!),
    consumerOf('shipping', 'e', CUSTOMER_COMMITS[0]!),
  ],
  pool: {
    records: [
      { repository: 'customers', commit: CUSTOMER_COMMITS[1]! },
      { repository: 'orders', commit: 'Test the happy path of an order' },
    ],
    nodes: ['node-a', 'node-b', 'node-c'].map((node) => ({
      node,
      packages: [
        { repository: 'customers', commit: CUSTOMER_COMMITS[1]! },
        node === 'node-b'
          ? { repository: 'orders', commit: 'Add the invoices flow', behind: ['the commit could not be fetched from the git host: connection timed out'] }
          : { repository: 'orders', commit: 'Test the happy path of an order' },
      ],
    })),
  },
};

/** A commit id as git gives one, 40 hex digits, drawn from `text` so that it is the same in every picture. */
function idFor(text: string): string {
  let hash = 2166136261;
  let id = '';
  for (let round = 0; id.length < 40; round++) {
    for (const char of `${round}:${text}`) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619) >>> 0;
    id += hash.toString(16).padStart(8, '0');
  }
  return id.slice(0, 40);
}

/** The git half over the fake world of the seed above, with ids, authors and dates of real work on its commits. */
export function pictureWorld(): PackagesApi {
  const { state } = fakeStateOf(SEED);
  const ids = new Map(state.folders.flatMap((f) => f.commits.map((c) => [c.id, idFor(`${f.name}/${c.subject}`)] as const)));
  for (const source of state.sources ?? []) for (const c of source.commits) ids.set(c.id, idFor(`${source.address}/${c.subject}`));
  const idOf = (id: string) => ids.get(id) ?? id;
  for (const source of state.sources ?? []) {
    for (const commit of source.commits) {
      commit.id = idOf(commit.id);
      commit.parents = commit.parents.map(idOf);
      commit.author = 'Petr Svoboda';
      commit.at = '2026-11-20T15:40:00+01:00';
    }
    if (source.head !== null) source.head = idOf(source.head);
  }
  for (const folder of state.folders) {
    for (const commit of folder.commits) {
      commit.id = idOf(commit.id);
      commit.parents = commit.parents.map(idOf);
    }
    if (folder.head.commit !== null) folder.head.commit = idOf(folder.head.commit);
    if (folder.upstream !== null) folder.upstream.commit = idOf(folder.upstream.commit);
  }
  if (state.node.answers) {
    for (const running of state.node.running) {
      running.commit = idOf(running.commit);
      for (const pin of running.dependencies ?? []) pin.commit = idOf(pin.commit);
    }
  }
  const pool = state.node.answers && state.node.pool !== undefined && !('error' in state.node.pool) ? state.node.pool : null;
  for (const record of pool?.records ?? []) record.commit = idOf(record.commit);
  for (const runs of pool?.nodes.flatMap((n) => n.packages) ?? []) {
    runs.commit = idOf(runs.commit);
    runs.deployedAt = runs.repository === 'orders' ? '2026-11-18T10:02:00Z' : '2026-11-05T08:00:00Z';
  }
  const orders = state.folders.find((f) => f.name === 'orders')!;
  for (const commit of orders.commits) {
    const known = COMMITS.find((c) => c.subject === commit.subject);
    commit.author = known?.author ?? 'Eva Horak';
    commit.at = known?.at ?? '2026-11-22T17:30:00+01:00';
  }
  orders.upstream!.fetchedAt = '2026-11-23T09:12:00+01:00';
  const others = state.folders.filter((f) => f.name === 'customers' || f.name === 'common');
  for (const commit of others.flatMap((f) => f.commits)) {
    commit.author = 'Petr Svoboda';
    commit.at = '2026-11-04T13:00:00+01:00';
  }
  if (state.node.answers) for (const running of state.node.running) running.deployedAt = running.repository === 'orders' ? '2026-11-18T10:02:00Z' : '2026-11-05T08:00:00Z';
  return fakePackagesApi(state);
}
