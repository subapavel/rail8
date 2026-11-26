import { currentLanguage, textsIn } from '../language';
import { refusalOf, refusalText } from '../refusal';
import { uiFetch } from '../session';

/** The kinds of secret, by their ids, in the order the screen offers them. */
export const KINDS = ['value', 'credentials', 'key-pair'] as const;

export type SecretKind = (typeof KINDS)[number];

/**
 * A direct user of a secret, typed: a deployed flow (`flow`), an undeployed flow that still drains its queued work
 * (`drain`), a connection, a credential, or the platform itself; `id` names it, and `kinds` are the kinds it takes.
 */
export interface SecretUser {
  kind: string;
  id: string;
  kinds: SecretKind[];
}

/** What the store knows of a key pair, never its private key: the leaf's subject, issuer and end, and its mark, only with a certificate. */
export interface KeyPairFacts {
  algorithm: string;
  size: number;
  /** The SHA-256 fingerprint of its public key, in hex. */
  fingerprint: string;
  subject?: string;
  issuer?: string;
  notAfter?: string;
  /** `ending` while the leaf ends within 30 days of the node's clock, `ended` once it has ended; absent otherwise. */
  certificate?: 'ending' | 'ended';
}

/** A secret the store holds, as `GET /admin/secrets` lists it: never its value. */
export interface SecretEntry {
  name: string;
  kind: SecretKind;
  scope: string;
  changedAt: string;
  changedBy: string;
  /** The first users, at most 100. */
  usedBy: SecretUser[];
  /** How many users it has in all. */
  users: number;
  /** True when this node cannot open it: its users fail as with a missing secret. */
  unreadable: boolean;
  /** The id of the key it is sealed under. */
  keyId: string;
  keyPair?: KeyPairFacts;
}

/** A name that a use names and the store does not hold, with its users. */
export interface MissingSecret {
  name: string;
  usedBy: SecretUser[];
  users: number;
}

/** A live node's key ids: its current one and its previous one, null when not set or while it has not joined. */
export interface NodeKeys {
  node: string;
  joined: boolean;
  key: string | null;
  previous: string | null;
}

/** The state of the store's key across the live nodes. */
export interface KeyState {
  nodes: NodeKeys[];
  /** The key every live node names as its current one; null while they differ. */
  common: string | null;
  rowsUnderAnotherKey: number;
  /** True while a node still holds a previous key. */
  previousSet: boolean;
}

/** The answer of `GET /admin/secrets`. */
export interface SecretList {
  secrets: SecretEntry[];
  missing: MissingSecret[];
  key: KeyState;
  /** The node that answered. */
  node: string;
}

/** The value of a write, of its kind. The certificates are PEM texts, the leaf first. */
export type SecretValue =
  | { kind: 'value'; value: string }
  | { kind: 'credentials'; user: string; password: string }
  | { kind: 'key-pair'; privateKey: string; certificates: string[] };

/** A refusal by the node: its status, its code, the text shown for it, and its body, which never holds a value. */
export class SecretsError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    message: string,
    readonly body: unknown,
  ) {
    super(message);
    this.name = 'SecretsError';
  }
}

/** What the Secrets screen asks of the node. Every call rejects with a {@link SecretsError} when the node refuses it. */
export interface SecretsApi {
  /** Every secret, the names used but not stored, the key's state and the node that answered. */
  list(): Promise<SecretList>;
  /** Creates the secret `name`; gives its entry. */
  create(name: string, value: SecretValue): Promise<SecretEntry>;
  /** Overwrites the secret `name` with a value of its kind; gives its entry. */
  overwrite(name: string, value: SecretValue): Promise<SecretEntry>;
  /** Deletes the secret `name`; `confirm`, its name typed out, deletes one that has a use or a possible use. */
  remove(name: string, confirm?: string): Promise<void>;
}

async function refusal(res: Response): Promise<SecretsError> {
  let body: unknown;
  try {
    body = await res.json();
  } catch {
    // no JSON
  }
  const text = refusalText(body) ?? textsIn(currentLanguage()).t('frame.node.answered', { status: res.status });
  return new SecretsError(res.status, refusalOf(body).code, text, body);
}

const secretPath = (name: string) => `/admin/secrets/${encodeURIComponent(name)}`;

/** The secrets of the node's admin API, through `send`, the page's `fetch` by default. */
export function httpSecretsApi(send: typeof fetch = uiFetch): SecretsApi {
  const call = async <T>(url: string, init: RequestInit = {}): Promise<T> => {
    const res = await send(url, { ...init, headers: { accept: 'application/json', ...(init.body === undefined ? {} : { 'content-type': 'application/json' }) } });
    if (!res.ok) throw await refusal(res);
    return (res.status === 204 ? undefined : await res.json()) as T;
  };
  return {
    list: () => call<SecretList>('/admin/secrets'),
    create: (name, value) => call<SecretEntry>('/admin/secrets', { method: 'POST', body: JSON.stringify({ name, ...value }) }),
    overwrite: (name, value) => call<SecretEntry>(secretPath(name), { method: 'POST', body: JSON.stringify(value) }),
    remove: (name, confirm) =>
      call<void>(confirm === undefined ? secretPath(name) : `${secretPath(name)}?${new URLSearchParams({ confirm }).toString()}`, { method: 'DELETE' }),
  };
}
