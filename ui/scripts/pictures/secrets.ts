import { httpSecretsApi, type SecretEntry } from '../../src/secrets/api';
import { fakeSecretsNode, type FakeSecrets } from '../../src/secrets/fake';

const OLD = '5c0ffee1d2e3f405';
const NEW = '9a8b7c6d5e4f3021';

const entry = (name: string, kind: SecretEntry['kind'], changedAt: string, changedBy: string, rest: Partial<SecretEntry> = {}): SecretEntry => ({
  name,
  kind,
  scope: 'installation',
  changedAt,
  changedBy,
  usedBy: [],
  users: 0,
  unreadable: false,
  keyId: NEW,
  ...rest,
});

/**
 * The secret store of the pictures, written by sam at `now`, in the middle of a key's roll: both nodes hold the new key and
 * still the old one, and two rows are not yet sealed again. A plain value two flows use, the credentials of a connection and
 * of the platform's git, a key pair with its certificate, an unused value under the old key, and a name a flow uses that the
 * store does not hold.
 */
export function pictureSecrets(now: () => Date) {
  const state: FakeSecrets = {
    secrets: [
      { entry: entry('erp.token', 'value', '2026-11-21T09:12:00Z', 'sam'), value: { kind: 'value', value: 'picture' } },
      { entry: entry('erp.login', 'credentials', '2026-11-22T14:30:00Z', 'sam'), value: { kind: 'credentials', user: 'picture', password: 'picture' } },
      {
        entry: entry('partner.sftp', 'key-pair', '2026-11-20T10:05:00Z', 'sam', {
          keyId: OLD,
          keyPair: {
            algorithm: 'Ed25519',
            size: 255,
            fingerprint: '3f1c9a0d7be24e6f8a51c0d2e9b7a4f6031d5c8e9a2b7f4e6d1c0a9b8e7f6d5c',
            subject: 'CN=rail8.acme.example,O=Acme',
            issuer: 'CN=Acme Issuing CA,O=Acme',
            notAfter: '2027-03-01T00:00:00Z',
          },
        }),
        value: { kind: 'key-pair', privateKey: 'picture', certificates: [] },
      },
      { entry: entry('rail8.git', 'credentials', '2026-11-19T08:00:00Z', 'import'), value: { kind: 'credentials', user: 'picture', password: 'picture' } },
      { entry: entry('legacy.token', 'value', '2026-10-02T16:45:00Z', 'ops', { keyId: OLD }), value: { kind: 'value', value: 'picture' } },
    ],
    uses: {
      'erp.token': [
        { kind: 'flow', id: 'orders/order-in', kinds: ['value'] },
        { kind: 'flow', id: 'orders/order-status', kinds: ['value'] },
      ],
      'erp.login': [{ kind: 'connection', id: 'erp', kinds: ['credentials'] }],
      'partner.sftp': [{ kind: 'connection', id: 'partner-sftp', kinds: ['key-pair'] }],
      'rail8.git': [{ kind: 'platform', id: 'git', kinds: ['credentials'] }],
      'billing.api': [{ kind: 'flow', id: 'billing/invoice-out', kinds: ['value'] }],
    },
    unknown: [],
    key: {
      nodes: [
        { node: 'node-a', joined: true, key: NEW, previous: OLD },
        { node: 'node-b', joined: true, key: NEW, previous: OLD },
      ],
      common: NEW,
      rowsUnderAnotherKey: 2,
      previousSet: true,
    },
    node: 'node-a',
  };
  return httpSecretsApi(fakeSecretsNode(state, 'sam', now));
}
