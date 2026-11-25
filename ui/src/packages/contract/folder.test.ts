import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type FolderBody } from '../api';
import { FOLDER_REFUSALS } from '../rules';
import { httpWorld } from './httpWorld';
import { fakeWorld, type Build, type Seed, type World } from './seed';

const BILLING = '# kept as the author wrote it\nschemaVersion: 1\nkind: folder\nnamespace: urn:acme:billing\nowner: finance\n';

// The write of a folder's folder.yaml, the same cases on both implementations of PackagesApi: the real git half over real git, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('the %s git half writes a folder', (_name, build) => {
  let world: World | undefined;
  afterEach(async () => {
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed = {}) => (world = await build({ clones: [{ folder: 'orders', files: { 'billing/folder.yaml': BILLING, 'flows/orders.yaml': 'kind: flow\nid: orders\n', notes: 'a file, no folder\n' } }], ...seed }));
  const refusalOf = (call: Promise<unknown>) =>
    call.then(
      () => expect.unreachable('the write was not refused'),
      (error: unknown) => {
        expect(error).toBeInstanceOf(PackagesError);
        return error as PackagesError;
      },
    );
  const textOf = async (path: string) => {
    const file = await world!.api.file('orders', 'work', path).catch(() => null);
    return file !== null && 'text' in file ? file.text : null;
  };
  const body = (path: string, more: Partial<FolderBody> = {}): FolderBody => ({ path, tag: null, namespace: null, description: null, ...more });

  test('a new folder, nested, gets its folder.yaml with the namespace and the description, listed with its folder as added', async () => {
    const { api } = await open();

    const answer = await api.folder('orders', body('orders/invoices/partner', { namespace: 'urn:acme:invoices:partner', description: 'What the partner is sent.' }));

    expect(await textOf('orders/invoices/partner/folder.yaml')).toBe('schemaVersion: 1\nkind: folder\nnamespace: urn:acme:invoices:partner\ndescription: What the partner is sent.\n');
    expect(answer.package?.artifacts.find((a) => a.kind === 'folder' && a.path.startsWith('orders/invoices'))).toEqual({
      path: 'orders/invoices/partner/folder.yaml',
      kind: 'folder',
      namespace: 'urn:acme:invoices:partner',
      description: 'What the partner is sent.',
      change: 'added',
    });
    expect(answer.package?.changes.map((c) => c.path)).toEqual(['orders/invoices/partner/folder.yaml']);
  });

  test('a folder with neither namespace nor description gets the least folder.yaml', async () => {
    const { api } = await open();
    await api.folder('orders', body('orders/returns'));
    expect(await textOf('orders/returns/folder.yaml')).toBe('schemaVersion: 1\nkind: folder\n');
  });

  test('a change of a folder.yaml, with the tag the author read, sets the two fields and keeps its comments and other keys', async () => {
    const { api } = await open();
    const { etag } = await api.file('orders', 'work', 'orders/billing/folder.yaml');

    const answer = await api.folder('orders', body('orders/billing', { tag: etag, namespace: 'urn:acme:billing:2', description: 'Invoices and credit notes.' }));

    expect(await textOf('orders/billing/folder.yaml')).toBe('# kept as the author wrote it\nschemaVersion: 1\nkind: folder\nnamespace: urn:acme:billing:2\nowner: finance\ndescription: Invoices and credit notes.\n');
    expect(answer.package?.artifacts.find((a) => a.path === 'orders/billing/folder.yaml')).toMatchObject({ namespace: 'urn:acme:billing:2', description: 'Invoices and credit notes.', change: 'modified' });
    const { etag: next } = await api.file('orders', 'work', 'orders/billing/folder.yaml');
    await api.folder('orders', body('orders/billing', { tag: next }));
    expect(await textOf('orders/billing/folder.yaml')).toBe('# kept as the author wrote it\nschemaVersion: 1\nkind: folder\nowner: finance\n');
  });

  test('a folder.yaml that is no longer the one the author read, or one that exists where none was seen, is refused with 412, and stays', async () => {
    const { api } = await open();
    expect(await refusalOf(api.folder('orders', body('orders/billing', { tag: '"not-it"', namespace: 'urn:other' })))).toMatchObject({ status: 412, code: FOLDER_REFUSALS.stale.code });
    expect(await refusalOf(api.folder('orders', body('orders/billing', { namespace: 'urn:other' })))).toMatchObject({ status: 412, code: FOLDER_REFUSALS.exists.code });
    expect(await refusalOf(api.folder('orders', body('orders/flows', { tag: '"any"' })))).toMatchObject({ status: 412, code: FOLDER_REFUSALS.stale.code });
    expect(await textOf('orders/billing/folder.yaml')).toBe(BILLING);
  });

  test('a namespace another folder has is refused with 422, naming that folder; the folder keeps its own', async () => {
    const { api } = await open();
    const refused = await refusalOf(api.folder('orders', body('orders/flows', { namespace: 'urn:acme:billing' })));
    expect(refused).toMatchObject({ status: 422, code: FOLDER_REFUSALS.namespaceTaken.code, values: { namespace: 'urn:acme:billing', folder: 'billing' } });
    expect(await textOf('orders/flows/folder.yaml')).toBeNull();

    const { etag } = await api.file('orders', 'work', 'orders/billing/folder.yaml');
    await api.folder('orders', body('orders/billing', { tag: etag, namespace: 'urn:acme:billing', description: 'Still ours.' }));
    expect(await textOf('orders/billing/folder.yaml')).toContain('description: Still ours.');
  });

  test.each([
    ["the package's own folder", 'orders', FOLDER_REFUSALS.root.code],
    ['a name with a capital letter', 'orders/Billing', FOLDER_REFUSALS.name.code],
    ['a name over 64 characters', `orders/${'a'.repeat(65)}`, FOLDER_REFUSALS.name.code],
    ['a folder five deep', 'orders/a/b/c/d/e', FOLDER_REFUSALS.deep.code],
  ])('%s is refused with 422, and nothing is written', async (_, path, code) => {
    const { api } = await open();
    expect(await refusalOf(api.folder('orders', body(path)))).toMatchObject({ status: 422, code });
    expect((await api.get('orders')).changes).toEqual([]);
  });

  test('an empty namespace and one over 1024 characters are refused with 422', async () => {
    const { api } = await open();
    for (const namespace of ['', 'u'.repeat(1025)]) {
      expect(await refusalOf(api.folder('orders', body('orders/returns', { namespace })))).toMatchObject({ status: 422, code: FOLDER_REFUSALS.namespaceLength.code });
    }
  });

  test('a path outside the package is refused with 404, a malformed one with 400', async () => {
    const { api } = await open();
    expect(await refusalOf(api.folder('orders', body('stock/returns')))).toMatchObject({ status: 404 });
    expect(await refusalOf(api.folder('orders', body('orders/../stock')))).toMatchObject({ status: 400 });
  });

  test('a file in the way of the folder is refused with 409, and nothing is written', async () => {
    const { api } = await open();
    expect(await refusalOf(api.folder('orders', body('orders/notes/inner')))).toMatchObject({ status: 409, code: FOLDER_REFUSALS.inTheWay.code, values: { path: 'orders/notes' } });
    expect((await api.get('orders')).changes).toEqual([]);
  });

  test('on a read-only server it is refused with 403', async () => {
    const { api } = await open({ readOnly: true });
    expect(await refusalOf(api.folder('orders', body('orders/returns')))).toMatchObject({ status: 403, code: FOLDER_REFUSALS.readOnly.code });
  });
});
