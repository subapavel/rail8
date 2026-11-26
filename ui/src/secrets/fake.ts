import type { KeyState, SecretEntry, SecretKind, SecretList, SecretUser, SecretValue } from './api';

/**
 * What a fake node's secret store holds: each secret's entry and the value it was given, the uses of each name, the
 * packages whose uses are not known (each a possible use of every secret), the key's state and the node's name. With
 * `keysDiffer`, a live node lacks the key this node seals under, so every write is 409 `keys-differ`.
 */
export interface FakeSecrets {
  secrets: { entry: SecretEntry; value: SecretValue }[];
  uses: Record<string, SecretUser[]>;
  unknown: string[];
  key: KeyState;
  node: string;
  keysDiffer?: { key: string; nodes: string[] };
}

const json = (status: number, body: unknown) => new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

const refused = (status: number, code: string, error: string, values: Record<string, unknown> = {}) => json(status, { error, code, values });

const NAME = /^[a-z0-9][a-z0-9._-]{0,127}$/;

/** The fixed facts the fake gives a key pair: it checks only the PEM type of its private key. */
const facts = (name: string, certificates: readonly string[]) => ({
  algorithm: 'EC',
  size: 256,
  fingerprint: '3f1c9a0d7be24e6f8a51c0d2e9b7a4f6031d5c8e9a2b7f4e6d1c0a9b8e7f6d5c',
  ...(certificates.length === 0 ? {} : { subject: `CN=${name}`, issuer: 'CN=Acme Issuing CA', notAfter: '2027-11-26T00:00:00Z' }),
});

/** Why `value` is not taken, as the node refuses it with 422 `invalid`, naming the field; undefined when it is taken. */
function invalid(value: Partial<Record<string, unknown>>): Response | undefined {
  const text = (field: string) => (typeof value[field] === 'string' && value[field] !== '' ? undefined : refused(422, 'invalid', `${field}: give it as a text of at least one character`, { field }));
  switch (value.kind) {
    case 'value':
      return text('value');
    case 'credentials':
      return text('user') ?? text('password');
    case 'key-pair': {
      const key = value.privateKey;
      if (typeof key !== 'string' || !/-----BEGIN (OPENSSH )?PRIVATE KEY-----/.test(key)) {
        return refused(422, 'invalid', 'privateKey: give the private key in PEM, as PKCS#8 or unencrypted OpenSSH', { field: 'privateKey' });
      }
      return undefined;
    }
    default:
      return refused(422, 'invalid', 'kind: the kind is value, credentials or key-pair', { field: 'kind' });
  }
}

/**
 * A node's admin routes of the secret store as a `fetch`, over `state`, written by `actor` at `now`: the list, a create, an
 * overwrite and a delete, each refused with the node's status, code and values as `docs/SECRET-STORE.md` lists them. No
 * answer holds a value.
 */
export function fakeSecretsNode(state: FakeSecrets, actor: string, now: () => Date): typeof fetch {
  const listed = (): SecretList => ({
    secrets: [...state.secrets]
      .sort((a, b) => a.entry.name.localeCompare(b.entry.name))
      .map(({ entry }) => ({ ...structuredClone(entry), usedBy: structuredClone(state.uses[entry.name] ?? []), users: (state.uses[entry.name] ?? []).length })),
    missing: Object.entries(state.uses)
      .filter(([name]) => !state.secrets.some((s) => s.entry.name === name))
      .map(([name, usedBy]) => ({ name, usedBy: structuredClone(usedBy), users: usedBy.length })),
    key: structuredClone(state.key),
    node: state.node,
  });
  const entryOf = (name: string) => listed().secrets.find((e) => e.name === name)!;
  const keysDiffer = () =>
    state.keysDiffer &&
    refused(409, 'keys-differ', `the nodes [${state.keysDiffer.nodes.join(', ')}] do not hold the key ${state.keysDiffer.key} this node seals under: try again once the roll of the key has ended`, {
      key: state.keysDiffer.key,
      nodes: state.keysDiffer.nodes,
    });
  const written = (name: string, value: SecretValue, createdBy?: string): SecretEntry => {
    const kind: SecretKind = value.kind;
    return {
      name,
      kind,
      scope: 'installation',
      changedAt: now().toISOString(),
      changedBy: createdBy ?? actor,
      usedBy: [],
      users: 0,
      unreadable: false,
      keyId: state.key.common ?? '00000000',
      ...(value.kind === 'key-pair' ? { keyPair: facts(name, value.certificates) } : {}),
    };
  };

  return async (input, init) => {
    const url = new URL(String(input), 'http://ui.test');
    const method = init?.method ?? 'GET';
    const name = url.pathname.startsWith('/admin/secrets/') ? decodeURIComponent(url.pathname.slice('/admin/secrets/'.length)) : undefined;
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as Record<string, unknown>) : {};
    if (url.pathname === '/admin/secrets' && method === 'GET') return json(200, listed());

    if (url.pathname === '/admin/secrets' && method === 'POST') {
      const named = body.name;
      if (typeof named !== 'string' || !NAME.test(named)) return refused(422, 'invalid', 'name: a name is [a-z0-9][a-z0-9._-]*', { field: 'name' });
      if (named.startsWith('rail8.') && named !== 'rail8.git') return refused(409, 'reserved', `names under rail8. belong to the platform`, { name: named });
      const bad = invalid(body);
      if (bad) return bad;
      const differ = keysDiffer();
      if (differ) return differ;
      if (state.secrets.some((s) => s.entry.name === named)) return refused(409, 'exists', `the secret '${named}' exists: overwrite it, or delete it first`, { name: named });
      const user = (state.uses[named] ?? []).find((u) => !u.kinds.includes(body.kind as SecretKind));
      if (user) return refused(409, 'kind-in-use', `${user.kind} '${user.id}' uses '${named}' as ${user.kinds.join(', ')}`, { name: named, kind: body.kind as string, user });
      const value = Object.fromEntries(Object.entries(body).filter(([field]) => field !== 'name')) as SecretValue;
      state.secrets.push({ entry: written(named, value), value });
      return json(201, entryOf(named));
    }

    if (name !== undefined && method === 'POST') {
      const bad = invalid(body);
      if (bad) return bad;
      const differ = keysDiffer();
      if (differ) return differ;
      const held = state.secrets.find((s) => s.entry.name === name);
      if (held === undefined) return refused(404, 'no-secret', `the store holds no secret '${name}'`, { name });
      if (held.entry.kind !== body.kind) {
        return refused(409, 'kind-differs', `'${name}' is a secret of kind ${held.entry.kind}, not ${String(body.kind)}: delete it and create it again`, {
          name,
          kind: held.entry.kind,
          asked: body.kind as string,
        });
      }
      held.entry = written(name, body as SecretValue);
      held.value = body as SecretValue;
      return json(200, entryOf(name));
    }

    if (name !== undefined && method === 'DELETE') {
      const held = state.secrets.find((s) => s.entry.name === name);
      if (held === undefined) return refused(404, 'no-secret', `the store holds no secret '${name}'`, { name });
      const usedBy = state.uses[name] ?? [];
      if ((usedBy.length > 0 || state.unknown.length > 0) && url.searchParams.get('confirm') !== name) {
        return refused(409, 'in-use', `'${name}' is used: delete it with ?confirm=${name}`, { name, usedBy, users: usedBy.length, mayUse: state.unknown, packages: state.unknown.length });
      }
      state.secrets = state.secrets.filter((s) => s !== held);
      return json(204, undefined);
    }
    return refused(404, 'not-found', 'no such route');
  };
}
