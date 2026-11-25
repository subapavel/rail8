# The secret store

This document is the agreed design of where Rail8 keeps the secrets that flows and connections use:
- the three kinds of secret and their types in `rail8-api`, and how an adapter and custom code take each;
- the table in Postgres, the encryption and the key, and how the key is changed;
- how every node of a pool learns of a change, without a restart;
- which source wins when the store and the node's environment both hold a name;
- what a deploy and a restore check, and the record of who uses a secret;
- the pool's git credentials as a secret of the store;
- the admin routes, the audit and the limits;
- what the configuration of an environment may rely on, and what it must not do.

It is built in the steps listed at the end, and no step reworks what an earlier one built.

Today a secret is an environment variable: `secret://<name>` reads `RAIL8_SECRET_<NAME>` (or its `_FILE` form) once at the
node's start, a new value takes a restart of every node, and a value is plain text only. The pool's git credentials are
`RAIL8_GIT_USER` and `RAIL8_GIT_TOKEN`.

The store has four kinds of user:
- custom code, through `secret://<name>` in a step's options;
- connections and credentials, through the configuration of an environment (another design, which builds on this one);
- endpoint files, until connections replace them;
- the platform itself, for the git credentials.

## What the store protects, and what it does not

These limits are accepted. They are stated first, and the operator's documentation says them the same way.

- **A lost key loses every secret.** The store's key is never in Postgres. Back it up apart from the database and its
  backups. A backup of the database without the key holds nothing that can be read. The only way back from a lost key is
  to enter every secret again.
- **It protects a leaked database or a leaked backup, not access to the server.** Whoever can read a node's memory, its
  environment or the file of its key reads every secret. The store is not a vault, and the documentation never calls it
  one.
- **Whoever deploys can reach every secret.** A flow names any secret by its name, and its code can send the value
  anywhere. Custom code runs in the node's JVM and can read the store's key from the node's environment too. Binding a
  secret to a package comes later.
- **Until the configuration's own check ships, whoever edits a connection can reach the secrets it uses.** A connection
  administrator who changes the address of a connection that holds a secret makes the node send the secret there. The
  first step of connections brings the check that stops it (rule 1 below).
- **Whoever writes the database can take a secret away or bring an old value back.** They cannot read or forge a value.
  A node cannot tell a row deleted in SQL from a delete through the API, nor an old row put back from a write.

## Rules

- A secret has a name, a kind, a scope and a value. The name is the one `secret://<name>` uses: `[a-z0-9][a-z0-9._-]*`,
  1 to 128 characters. References in flows do not change.
- The scope is `installation` for every secret now. It is part of a row's key and of what the encryption binds, so a
  narrower scope can come later with no migration of values.
- There are three kinds: a plain value, credentials (a user and a password), and a key pair with its certificates.
- The role `secret-administrator` creates, overwrites and deletes. Nobody reads a value back: no route, no answer, no log
  line and no audit row holds one.
- A developer sees each secret's name, kind, who changed it last and when, and who uses it: the deployed flows, the
  connections and credentials, and the platform.
- Values are sealed in Postgres under the store's key, which comes from the node's environment or a file.
- A change reaches every node of the pool at once, without a restart.
- The node's environment stays a second source for plain values: `RAIL8_SECRET_<NAME>`. The store wins.
- The platform's own credentials (Postgres, S3, Valkey, ClickHouse, the admin token, the journal's key and the store's key)
  stay in the node's environment and never enter the store.
- Names that start with `rail8.` belong to the platform. No flow, endpoint or setting can reference one.
- Every refusal carries a stable code and an English text. The UI translates by the code.

## Kinds

| Kind | Id | Holds | From the environment |
| --- | --- | --- | --- |
| Plain value | `value` | a text of 1 character to 32 KiB, UTF-8 | yes, `RAIL8_SECRET_<NAME>` |
| Credentials | `credentials` | a user of 1 to 256 characters and a password of 1 to 4,096 | no |
| Key pair | `key-pair` | a private key, its public key, and 0 to 10 certificates, the leaf first | no |

A key pair is written as text: the private key in PEM, PKCS#8 (`BEGIN PRIVATE KEY`) or unencrypted OpenSSH
(`BEGIN OPENSSH PRIVATE KEY`), and the certificates in PEM. The node checks it at the write and keeps it in one form:
- The private key is RSA of 2,048 to 8,192 bits, EC on P-256, P-384 or P-521, or Ed25519. An encrypted key, another
  PEM type or another algorithm is refused. The answer names the `openssl` command that converts a PKCS#1 or SEC1 key.
- bcprov only unpacks the OpenSSH container. Every key object comes from the JDK's `KeyFactory`, and every certificate
  from the JDK's `CertificateFactory`. The step that builds it checks bcprov's exact signatures with `javap` on the
  resolved jar.
- The public key is derived from the private key. A signature made with the private key must verify with the public
  key, which also proves that an RSA key's parts agree.
- A certificate's key follows the same list of algorithms and sizes. The leaf's public key equals the pair's, and each
  further certificate signed the one before it. Neither trust nor dates are checked: an expired certificate is stored,
  and the list shows its end.
- The certificates take at most 32 KiB together.
- The stored form is the PKCS#8 encoding of the private key, the X.509 encoding of the public key, and the DER of each
  certificate.

## Types in `rail8-api`

`Secret` stays the one handle. It gains its kind and two value types, in version 0.4 of the API:

```java
public final class Secret {
    public enum Kind { VALUE, CREDENTIALS, KEY_PAIR; public String id(); }            // "value", "credentials", "key-pair"

    /** toString names the user only. */
    public record Credentials(String user, String password) { }
    /** toString names the algorithm and the leaf's subject only. */
    public record KeyPair(PrivateKey privateKey, PublicKey publicKey, List<X509Certificate> certificates) {
        public java.security.KeyPair pair();
    }

    public static Secret of(String reference, Supplier<String> value);                 // a plain value, as today
    public static Secret credentials(String reference, Supplier<Credentials> value);
    public static Secret keyPair(String reference, Supplier<KeyPair> value);

    public String reference();
    public Kind kind();
    public String value();                 // VALUE only
    public Credentials credentials();      // CREDENTIALS only
    public KeyPair keyPair();              // KEY_PAIR only
}
```

- Each read returns the value now. The handle keeps nothing, so a value overwritten in the store is seen at the next read.
  A read of another kind than the handle's is an `IllegalStateException` naming the reference and both kinds.
- An adapter reads a handle once per connection, login, session or handshake, and uses that one value throughout it.
  A TLS handshake that asked twice could otherwise pair one value's key with another's chain.
- `toString` of the handle is its reference, as today.

### How an adapter takes one

- `Field.secret(name, help)` takes a plain value, as today. `Field.secret(name, help, Kind... kinds)` takes the kinds it
  names. The settings description serves them as `"kinds": ["value", "key-pair"]`, and the UI's form shows them.
- The config an adapter is built with, `EndpointConfig` today, gains `Optional<Secret> secret(String option)`: the handle
  of a secret field, whatever its kind. It is backed by a fourth component, `Map<String, Secret> secrets`. The
  three-component constructor stays and gives no handles. The config that connections bring carries the same method.
- A secret field holds a reference to a secret, never a value. Every other field holds its value itself. An adapter whose
  secret would sit inside another value, such as a token in a URL, declares a secret field for it.
- An adapter never reads a handle while it builds a consumer or a producer. The component contract suite checks that: it
  builds a consumer and a producer with handles that count their reads, and fails on a read before the first use.
- The SFTP component's `privateKey` takes a plain value in OpenSSH form, as today, or a key pair. It presents a key pair
  through MINA SSHD's `KeyIdentityProvider` over `keyPair().pair()`, read at each session. Its `password` takes a plain
  value or credentials. With credentials, the user comes from the secret and the `user` option is refused.
- Every component's mock takes the same kinds as the real one and passes the same suite.

### How a connection's or a credential's setting refers to one

- A setting of a connection or of a system's credential refers to a secret only from a secret field: by the secret's name,
  with the kinds the field takes. The setting holds no value.
- The node builds the adapter's config from the active setting, with a handle for each secret field. A change of the
  secret reaches the adapter at its next read, so no flow is rebuilt for a secret.
- A user who holds both the role that edits the setting and `secret-administrator` may create the secret from the
  setting's form. That is two calls: the secret through the store's route, then the setting with its name.

### How custom code takes one

- `Services.secret(step, key)` takes a plain value, as today. `Services.secret(step, key, Kind... kinds)` takes a secret of
  the kinds it names, and the deploy refuses one of another kind.
- The handle is read at each use, as today. `FakeServices` in the testkit gives a step under test its secrets of every
  kind, and lets the test change one between two messages.
- `rail8 validate` and `rail8 test` read a plain value as `https://secret.invalid/`, credentials as user `rail8` and
  password `invalid`, and a key pair as a fixed EC P-256 pair with a self-signed certificate, generated in the testkit's
  resources and named invalid.

### Endpoint files, until connections replace them

- A `secret://<name>` value of an endpoint option is resolved from the store or the variable when the flow builds, as
  today from the variable. A plain value goes into the endpoint's options, a typed one only through its handle.
- When the value or the source of a name that an endpoint's options resolved changes in a node's memory, the node builds
  again the flows that hold it. It does so through its local follow, which builds only those flows, with the record it
  runs. A handle needs no rebuild.
- One rule of that follow changes for this rebuild. Today a follow that cannot install keeps the old version running,
  listed behind. A flow rebuilt for a secret whose build fails is stopped and listed `FAILED` instead, so a deleted or
  changed secret is never used on with its old value. Each tick tries it again.
- This rebuild goes away with endpoint files.

## Sources

- The store wins. For a name the store does not hold, the node reads `RAIL8_SECRET_<NAME>` as today, a plain value only.
- After a delete, the variable takes over on each node that sets it.
- Each node writes the names of its `RAIL8_SECRET_*` variables, never their values, into its row of `nodes` when it
  joins. So the list shows, for each stored secret, the nodes whose variable it shadows, which a delete would bring back.
- A row that the node cannot open never falls back to the variable: the name counts as set but unreadable.

## Storage

```sql
create table secret_store (
    one        boolean primary key default true check (one),
    generation bigint  not null,     -- raised by every change
    stamp      bigint  not null      -- a random number written by every change
);

create table secrets (
    scope      text        not null check (scope = 'installation'),
    name       text        not null check (name ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    kind       text        not null check (kind in ('value', 'credentials', 'key-pair')),
    format     smallint    not null check (format = 1),
    key_id     bytea       not null check (length(key_id) = 8),
    nonce      bytea       not null check (length(nonce) = 12),
    sealed     bytea       not null,    -- the ciphertext and its 16-byte tag
    created_at timestamptz not null,
    changed_at timestamptz not null,
    changed_by text        not null,    -- the user's name, as the audit row names it
    primary key (scope, name)
);

create table secret_uses (
    scope     text   not null,
    name      text   not null,
    user_kind text   not null check (user_kind in ('flow', 'connection', 'credential', 'platform')),
    user_id   text   not null,         -- '<package>/<flow>', the connection's or credential's name, or 'git'
    kinds     text[] not null,         -- the kinds this use takes
    primary key (scope, name, user_kind, user_id)
);

alter table nodes add column secret_key bytea, add column secret_key_previous bytea,
    add column secret_variables text[], add column secret_epoch bigint;
alter table deployments add column secret_uses_known boolean not null default false;
```

- The value is a JSON object in UTF-8: `{"value"}`, `{"user", "password"}`, or `{"privateKey", "publicKey",
  "certificates"}` in base64. It is padded with spaces to the next multiple of 256 bytes, so a row tells no value's
  length within 256 bytes. No digest of a value is stored anywhere.
- Format 1 seals with AES-256-GCM under a key derived from the store's key by HKDF-SHA256 (info `rail8 secrets v1 seal`),
  with a random 96-bit nonce for every seal. The associated data is the format, the scope, the name, the kind and the key's
  id. So a sealed value moved to another row or another kind does not open. A later format, such as a key from a KMS,
  takes the next number.
- `key_id` is the first 8 bytes of HKDF-SHA256 of the key with info `rail8 secrets v1 id`. It tells keys apart and tells
  nothing of the key.
- The nonce is a row's identity: a node holds each name with the nonce it opened, and opens a row whose nonce it does not
  hold. A delete removes the row.
- The generation and the stamp together say only that something changed. The stamp tells two states apart that have the
  same generation, such as a database set back one change and then written once.
- `nodes.secret_key` and `nodes.secret_key_previous` are the ids of the keys a node holds, and `nodes.secret_variables` the
  names of its variables. A node writes them when it joins, with the epoch of that join in `nodes.secret_epoch`. A write
  counts a live node whose `secret_epoch` is not its `epoch` as holding no key.
- `secret_uses` holds every use (below). `deployments.secret_uses_known` says whether a record's flow rows are there. It is
  false for every record written before the store.
- The migration inserts the one row of `secret_store`, generation 0 with a random stamp, and the platform's row of
  `secret_uses`: `rail8.git`, used by `platform`/`git` as credentials.

## The key

- `RAIL8_SECRETS_KEY`, or `RAIL8_SECRETS_KEY_FILE` (preferred): the base64 of 256 random bits, as
  `openssl rand -base64 32` writes it, read once at start, as the node's other secrets are. One key per installation,
  shared by all its nodes.
- It is not the journal's key. A node whose two keys are equal refuses its start.
- `RAIL8_SECRETS_KEY_PREVIOUS` (or `_FILE`) is set only while the key is changed.
- With no key and an empty store, the node starts and reads secrets from its environment only. A write is 503 `no-key`.
- With no key and a store that holds a row, the node refuses its start, naming the number of rows and the ids of their
  keys.
- The node joins in one transaction under the row lock of `secret_store`, which every write takes too. It writes its key
  ids and its variables' names, and checks every row's key id. A row sealed under a key the node does not hold refuses
  its start, naming the key's id and the number of rows.
- A row that does not open, or that appears later under a key the node does not hold, is unreadable:
  - the flows and connections that use it fail as a missing secret does;
  - `/health` is `DEGRADED`;
  - the list shows it `unreadable`;
  - a WARNING names the name and the key's id once.
- A node stops holding its previous key once every live node names the same current key as it, and no row of any table
  is sealed or bound under the previous one. It checks both, and clears `secret_key_previous` in its row, in one
  transaction under the store's row lock, so no write can seal under that key after the check. While the variable is
  still set, `/health` is `DEGRADED` and a WARNING says to remove it. A node restarted with it still set holds it again
  until the same check passes.

### Changing the key

Two rolls of the pool's nodes:

1. Each node is restarted with the new key as `RAIL8_SECRETS_KEY` and the old one as `RAIL8_SECRETS_KEY_PREVIOUS`.
   - A node seals under its own current key only.
   - A write is refused with 409 `keys-differ` unless every live node holds the key it would seal under, as current or
     previous. A live node is a row of `nodes` whose lease holds.
   - So during this roll a write succeeds on a node not yet rolled, whose old key every node holds. On a rolled node it is
     409 until the roll ends. The UI says to try again after the roll.
2. Once every live node names the same current key, each node's tick seals again up to 100 rows still under another key.
   Each batch is one transaction under the store's row lock. The ticks also ask the configuration to bind its rows again
   under the new key. Sealing again changes a row's key id and nonce only: its `changed_at` and `changed_by` stay those of
   the last person who wrote it. When no row of any table is left under another key, the node that sealed the last one
   writes the audit row `secret.key-changed`, subject `secret-store`. Its actor is the node, its way `local`, and its
   detail the old and the new key's ids and the number of rows.
3. Once `GET /admin/secrets` shows no row under the old key, each node is restarted without
   `RAIL8_SECRETS_KEY_PREVIOUS`.

- A node that starts without the old key while rows remain under it refuses its start, as above. A pool rolled back to
  the old key changes back the same way.
- A node restarted on a version before the store ignores the store and runs on its variables. Its join raises its epoch
  and writes no key ids, so every write is 409 `keys-differ` while it runs. The release note says not to run a version
  before the store beside one that has it.
- A forged row of `nodes` can only hold writes back. It never decides which key seals.
- An unreadable row cannot be sealed again. It is deleted, and its secret entered again.
- **After a leak of the key:** changing the key does not protect the database's backups that were taken under the old key.
  Whoever holds the leaked key reads them. After a leak, every value is entered again, in the secrets' own systems first
  where they can be changed there.

## How every node learns of a change

- A write commits its row, the store's generation and stamp, its audit row and `pg_notify('rail8_secrets', '<generation>')`
  in one transaction.
- Each node listens on `rail8_secrets` on the connection it already holds for `LISTEN`. It reads the store's generation and
  stamp on each notice, after every `LISTEN` acknowledged (a reconnect may have lost notices), and at each 5 s tick on the
  injected timer.
- When the generation or the stamp differs from the one it holds, the node reads in one repeatable-read transaction: the
  generation, the stamp, and every row's name, kind, key id and nonce.
  - It opens the rows whose nonce it does not hold, and drops the names that are gone.
  - A generation behind its own, or the same generation with another stamp, means the database went back to an earlier
    state, since every change raises the generation. It is a WARNING, and the node holds what it read, as for any other
    change.
  - The payload of a notice is only a wake-up.
- The node that wrote reads again before it answers, so a write is in force on that node when it is answered.
- The values are held in the node's memory. A read of a secret never goes to Postgres.
- A handle sees the new value at its next read. In the interim, the flows whose endpoint options resolved a changed name
  are built again, as above.

## The record of uses

- `secret_uses` names every direct user of a secret:
  - a deployed flow, for a reference in its steps' options or, in the interim, its endpoint files;
  - an activated connection or credential;
  - the platform, for `rail8.git`.
- Every writer of uses goes through one method of the store. It takes the transaction advisory lock of each name it
  writes, in the order of the names, and every create and delete of a secret takes the same lock. No writer writes this
  table with its own SQL.
- A deploy replaces its package's flow rows in the transaction that writes its record. In that same transaction, under
  the names' locks, it checks again against the rows in Postgres that each name it uses is set and of a kind its uses
  take. A name changed since its first check makes the deploy refuse, as a problem. An undeploy deletes its rows with its
  record.
- The configuration writes a connection's or credential's rows in the transaction that activates its setting. A draft is
  not a use.
- A node that restores a record whose `secret_uses_known` is false writes its flow rows, taking the pool's lock without
  waiting. While another holds the lock, it leaves the record as it is and tries again at a later tick.
- The list answers the direct users. For a connection, it adds the deployed flows that name the connection, from the
  configuration's own record. A delete counts every row as a use, and every record whose uses are not known as a
  possible one: the 409 `in-use` answer names the first in `usedBy` and the others in `mayUse`.

## Git credentials

- The pool's git credentials are the credentials secret `rail8.git`. The variables `RAIL8_GIT_USER` and
  `RAIL8_GIT_TOKEN` stay the second source, so a node starts with an empty store.
- The token rules of the deploy from git keep holding:
  - the token is read-only, for the `https` base, one for the base;
  - it is sent only as the `https` credentials of the base's host;
  - it is never in git, in a URI, in a log or in an answer;
  - in Postgres it is only sealed in the store.
- The base stays in the node's environment, so a secret administrator cannot point the node at another host.
- The git cache reads the credentials once for each request it sends, behind the check of the same origin that it makes
  today.
- A change of `rail8.git` brings one extra check of the credentials on every node. Changes close together are coalesced
  into one check, and it starts no sooner than 1 minute after the node's last check ended. So overwrites in a loop cannot
  make the nodes hammer the git host. `GET /admin/git` adds `credentialsFrom`: `store`, `environment` or `none`.
- `rail8.git` always counts as in use (its platform row), so its delete needs the confirmation.
- The resolver that flows and connections read through refuses every `rail8.` name itself, besides the checks of a
  deploy and `rail8 validate`. A package that references a `rail8.` name today fails its restore after the upgrade, with
  a problem that names the reference, and the release note says so.

## Admin routes

Each route is mounted with its action, and the action is checked before the body is read. A body is JSON of at most
64 KiB, which the UI server passes.

| Route | Action | Does |
| --- | --- | --- |
| `GET /admin/secrets` | `secret.list` | every secret, the names used but not stored, and the key's state (below) |
| `GET /admin/secrets/{name}` | `secret.list` | one of them; 404 `not-found` when no source holds it and nothing uses it |
| `POST /admin/secrets` | `secret.write` | creates `{name, kind, value}`, `{name, kind, user, password}` or `{name, kind, privateKey, certificates}` |
| `POST /admin/secrets/{name}` | `secret.write` | overwrites it with a value of the same kind; the last writer wins |
| `DELETE /admin/secrets/{name}` | `secret.delete` | deletes it; 409 `in-use` with its users unless `?confirm=<name>` names it |

The list answers, for each secret:
- its name, kind and scope;
- `changedAt` and `changedBy`;
- `usedBy`, typed;
- `shadowedOn`: the nodes whose variable it shadows;
- `unreadable`;
- for a key pair, its algorithm and size, the leaf's subject, issuer and end, and the SHA-256 fingerprint of its public key.

It also answers:
- the names that a use names and no source holds, with their users;
- the names that only variables hold, with the nodes;
- the key's state: each live node's key ids, the rows under another key than the common one, and whether a previous key
  is still set;
- the node that answered.

The refusals and their codes:

| Status | Code | When |
| --- | --- | --- |
| 404 | `not-found` | an overwrite or delete of a name the store does not hold |
| 409 | `exists` | a create of a name the store holds |
| 409 | `kind-differs` | an overwrite with another kind: delete and create instead |
| 409 | `kind-in-use` | a create of a kind that a use of the name does not take |
| 409 | `in-use` | a delete without the confirmation, of a name with a use or a `mayUse` |
| 409 | `keys-differ` | a write while a live node does not hold the key it would seal under |
| 409 | `store-full` | a create beyond 1,000 secrets, or a write that would take the sealed values beyond 16 MiB |
| 409 | `reserved` | a name under `rail8.` other than `rail8.git` |
| 422 | `invalid` | a value of the wrong form or out of its limits; it names the field and never echoes the value |
| 503 | `no-key` | a write on a node without the store's key |
| 503 | `audit-unavailable` | a write whose audit row cannot be written |

- `secret.list` holds no value. The developer, the secret administrator, and the roles that edit an installation's
  configuration (the connection administrator and the partner administrator) hold it, so that a connection's form can
  pick a secret by name and kind. The configuration's design adds the grant with those roles.

## Audit

- `secret.create`, `secret.overwrite` and `secret.delete` have the subject `secret/<name>`. Each row is written in the
  transaction of the change: a change whose row cannot be written does not happen.
- The detail is the kind, and for a key pair also the fingerprint of its public key and the leaf's end. For a delete,
  it also says whether it was confirmed and names the users. It never holds a value, a user name of credentials, a
  password or a private key.
- The actor, the way and the credential come from the check, as for every route.
- `secret.key-changed` records the end of sealing again under a new key, as above.

## Checks

At a deploy, `rail8 validate` and a restore, each reference:
- is well formed, at most 128 characters, and not under `rail8.`;
- is set on the node, in the store or in the environment (`rail8 validate` skips this);
- has a kind that its place takes: a secret field that names the kind, `Services.secret` with the kind, or a plain value
  in any other option of an endpoint file.

A refusal names the flow, the endpoint or step, the option, the name and its source, never a value. A row that does not
open is a problem naming its key's id.

- At a deploy, a problem refuses the deploy, as a missing variable does today. The deploy checks again in its record's
  transaction (above).
- At a restore and a follow, a problem refuses the package whole. It is listed `FAILED` with its problems and tried again
  at each tick, as today, so a secret created later lets the next tick install it.
- A secret that a running flow uses and that is deleted, or becomes unreadable, fails that flow's next read. In the
  interim, the flows whose endpoint options held it are built again, fail their build, and are stopped and listed
  `FAILED`, as above. Each tick tries them again.

## Scrubbing

- `FlowSecrets` always keeps each handle's current value. It also keeps up to 7 earlier values per handle, which it read
  before a change, within 256 KiB of earlier values per flow, the oldest dropped first. A failure that holds a value from
  before a change is still scrubbed.
- It scrubs a plain value in its text, URL-encoded and JSON-escaped forms. For credentials it scrubs the password in the
  same three forms and the base64 of `user:password`. For a key pair it scrubs the private key in base64, whole and in each
  64-character line of its PEM.
- In the interim, the plain values that an endpoint holds in its options join the flow's set, and so do the handles of its
  secret fields.
- Longer values are replaced first, as today.

## The configuration of an environment

Connections, a system's credentials and the rest of what an environment holds outside git are its configuration,
designed apart, which builds on this store. Whether it keeps tables of its own or shares these is that design's choice;
either way the rules below hold.

It may rely on:
- the three kinds, their checks at the write, and a reference to a secret by its name and kinds;
- the node's read path: values in memory, a change on every node at once, a handle read at each use;
- the routes, their codes and their audit rows. A form that creates a secret calls `POST /admin/secrets`;
- the record of uses, written through the store's one method with the names' locks;
- a binding: an HMAC-SHA256 tag under a subkey of the store's key (HKDF info `rail8 settings v1 bind`), named by the
  key's id, over the bytes the configuration gives. A change of the key binds its rows again, and the previous key is
  dropped only when no row is bound under it.

It must not:
1. **Send a secret where the secret administrator did not agree.** A setting that pairs a secret with an address, a
   proxy or a trust it was not paired with before takes effect only with the secret administrator's consent. The
   configuration's design chooses the mechanism, for example an approval at activation, and it ships with the first
   step of connections. A setting that turns off TLS verification, or adds trust, for a connection that carries a
   secret needs the same consent.
2. **Test a draft so that a secret goes where rule 1 forbids.** "Test connection" on a drafted connection sends its
   secrets under the same consent as an activation would need: a draft that pairs a secret with a new address, proxy or
   trust is tested only with the secret administrator's consent.
3. **Keep an address, a proxy or a trust that a database writer could change unseen.** Each setting that says where a
   secret goes is bound under the store's key. A node refuses a setting whose binding does not verify.
4. **Read a secret back, or take its kind from anywhere but the store.** Its own answers hold a secret's name only. It
   never changes a secret's kind in place, and it never names a setting with a secret's name.
5. **Hold a value in a draft, a history or a diff.** It holds the secret's name. Bringing back an old version of a setting
   runs the checks again against today's secrets.
6. **Hold an inbound caller's password or key other than as a reference to a secret.** The secret administrator enters
   it, and the setting picks it by name.
7. **Make the git base, the store's key or a `rail8.` name a setting.**
8. **Write `secret_uses` with its own SQL, or count a draft as a use.**

## A new installation

- Values never leave an installation, except inside a backup of its database taken together with its key. Copying the
  two copies every secret, production's included, and the documentation says so.
- An export of an environment's configuration carries the names and kinds of the secrets its settings use, and a key
  pair's public facts, never a value.
- An import lists the secrets that the new installation lacks, as missing uses, and the secret administrator enters each
  there. Environments hold different secrets in any case.
- An export of sealed values under a key for the transfer is not built: it would be a way to read values back.

## Limits

| What | Limit |
| --- | --- |
| Secrets in the store | 1,000 |
| All sealed values together | 16 MiB |
| A plain value | 32 KiB; a larger one stays in a variable, whose file may hold 64 KiB |
| A user; a password | 256 characters; 4,096 characters |
| A key pair | RSA 2,048 to 8,192 bits; EC P-256, P-384, P-521; Ed25519; 0 to 10 certificates, 32 KiB together |
| A name | 1 to 128 characters |
| A request's body | 64 KiB |
| Rows sealed again per tick | 100 |
| Earlier values kept for scrubbing | 7 per handle, 256 KiB per flow; the current values always |
| The time a change takes to reach a node | the notice; at most one tick, 5 s, when a notice is lost |
| Extra checks of the git credentials | one per change, coalesced, at least 1 minute after the last check |

## Against an attacker

| What an attacker tries | What stands against it |
| --- | --- |
| reading a dump or a backup of the database | every value sealed under a key that is never in Postgres; lengths hidden within 256 bytes; no digest |
| moving a sealed value to another name or kind | the format, name, scope, kind and key id are bound into the seal |
| forging a value with an old key after a change | the previous key is dropped once no row needs it; `DEGRADED` while it is set |
| steering writes to a key they hold through `nodes` | a node seals under its own current key; a forged row only holds writes back |
| deleting a row, or putting an old row back | accepted: a variable may take over, or an old value return; the generation behind is a WARNING |
| restoring the database to an earlier state | every node reads everything when the generation or the stamp differs |
| a node rolled back to a version before the store | its join leaves no key ids for its epoch, so writes are 409 while it runs |
| reading a value back through the API | no route answers one; the audit and the logs never hold one; a refusal never echoes one |
| a developer learning values | the list holds names, kinds, dates, uses and public facts of certificates only |
| a write that a node cannot read | 409 `keys-differ` unless every live node holds the key; the join checks the rows under the same lock |
| a key typed wrong on one node | that node refuses its start, naming the key ids, before it runs a flow |
| deleting a secret in use, by mistake | 409 `in-use` until the name is written out; unknown uses count |
| a deploy or an activation racing a delete | the names' locks, and the check again in the record's transaction |
| a stale variable taking over after a delete | `shadowedOn` in the list, from every node |
| a flow file reading the git token | `rail8.` names refused by the resolver itself |
| a secret administrator pointing git elsewhere | the base stays in the environment; the token goes only to the base's host |
| overwriting `rail8.git` in a loop | one coalesced check per node, at least 1 minute apart |
| a value in a failure, a log line or an answer | the scrubber, with earlier values and three encodings |
| learning whether a guess equals a secret through the scrubber | accepted: whoever puts text into a failure sees whether it was replaced |
| a value too short to scrub safely | accepted: a value of a few characters can be read off what the scrubber replaced |
| a crafted key or certificate | three algorithms with bounded sizes; the JDK's parsers; a signature that must verify |
| a connection administrator redirecting a secret | rule 1 of the configuration; until it ships, accepted beside whoever deploys |
| reading a node's memory, environment or key file | accepted: the store does not protect access to the server |
| a deployed flow sending a secret away, or reading the key | accepted: whoever deploys reaches every secret |

## Tests

- One contract suite, `SecretStoreContract`, runs for the Postgres store (Testcontainers) and an in-memory one. Two Postgres
  stores over one database stand for two nodes, each with its own fake clock and timer. Across them:
  - a write on one is read on the other after the notice, and after one tick with the notice dropped;
  - a database set back to an earlier state is read whole by both, with the WARNING, and so is one set back one change
    and then written once;
  - a delete brings back the variable, and a deleted secret fails the next read of a handle;
  - a row under a key a node does not hold refuses its start; a join that races a write sees the row; a tampered row is
    unreadable and never falls back;
  - the change of the key:
    - writes during the roll succeed on the nodes not yet rolled and are 409 on the others;
    - each node seals again in batches once every node names the new key, and the audit row is written at the end;
    - a node refuses to start without the old key while rows remain under it;
    - the previous key is no longer held once no row needs it;
    - a forged `nodes` row holds writes back and changes no seal.
- The seal: each bound field changed one at a time fails to open; two seals of one value differ; plain values of 1
  and 244 bytes, whose objects take 13 and 256 bytes, give rows of one length.
- The uses:
  - a delete that races a deploy, and a deploy that races a delete, end with a consistent record or a refusal;
  - a restore writes the uses of a record that had none;
  - `rail8.git` needs the confirmation.
- The routes:
  - each route is called as every role, through a session and a token;
  - each refusal of the codes table gives its code;
  - no answer, log line or audit row of any test holds a value, checked by searching every captured text for each value
    written, in each of its encodings.
- The interim: an overwrite of a plain value that an endpoint option resolved rebuilds that flow on both nodes, and no
  other.
- `FlowSecrets`: a failure thrown with a value from before an overwrite is scrubbed; the limits drop the oldest earlier
  value and never a current one; the URL-encoded and JSON-escaped forms are scrubbed.
- The key pair: each refused algorithm and size, for the key and for a certificate's key; a key that does not match its
  public key; a chain out of order.
- The adapter contract: a build that reads a handle fails the suite.
- The SFTP component against its mock and the SFTP server of the compose stack: by password, by credentials, by a key in
  OpenSSH form and by a key pair, with the key pair overwritten between two sessions.
- Git:
  - a fetch after `rail8.git` is overwritten sends the new token;
  - overwrites in a loop give one check a minute at most;
  - a redirect to another host still sends nothing.
- A fake clock and an injected timer move every tick. No test sleeps.

## Out

- OAuth clients and partners' trusted certificates.
- Reading a value back, and a history of values.
- An external vault as another source, and a key from a KMS (a later `format`).
- The client secret of OIDC in the store: it moves there with the OIDC step, as a `rail8.` name.
- Binding a secret to a package, and scopes narrower than the installation.
- Credentials or key pairs from the environment.
- A command of the command line for secrets.
- **Open point:** a kind that holds only a verifier (a salted hash) of an inbound caller's password or key, so that the
  node can check a caller without holding a value it could send on.

## Steps

Each step is one issue, built and merged on its own. 1a comes first. The configuration of an environment builds on
1a and 1b, and the Secrets screen on 1a.

- **1a. The store.** `class:C`. It can run beside the login step of the users' design.
  - The migration; the seal, the key, the join and the change of the key with its audit row.
  - The notices and the refresh.
  - The routes with their actions, codes and audit.
  - `secret_uses` with its one method and the names' locks, the check again in the record's transaction, and the
    backfill at a restore.
  - The checks of a deploy and a restore. The `rail8.` reservation in the resolver.
  - `shadowedOn` from the nodes' variables.
  - The interim rebuild for endpoint options.
  - `FlowSecrets` with the earlier values and the encodings.
  - The binding for the configuration.
  - The API's value types `Secret.Kind`, `Secret.Credentials` and `Secret.KeyPair`, and `Services.secret` with kinds for
    custom code, with the testkit's `FakeServices`, its placeholder key pair, and the placeholders of `rail8 validate` and
    `rail8 test` for every kind.
  - The key pair's checks at the write.
- **1b. Credentials and key pairs in adapters.** `class:C`, after 1a.
  - `Field.secret` with kinds, and `secret(option)` on the config an adapter is built with.
  - The component contract's check that a build reads no handle.
  - The SFTP component by credentials and by key pair, with its mock.
  - It is apart from 1a because it changes public types every adapter builds on, and is reviewed as a contract of its own.
- **1c. The git credentials from the store.** `class:C`, after 1a. `rail8.git` read once per request, behind the same-origin
  check; the coalesced check after a change; `credentialsFrom`; the release note on `rail8.` references. The rule of the
  deploy from git that its credentials live in the node's environment only becomes: in the environment, or sealed in the
  secret store.
- **2. The Secrets screen.** `class:B`, after 1a.
  - A developer's list with its typed users, and `shadowedOn`.
  - A secret administrator's create, overwrite and delete, with the confirmation, and the key's state.
  - The create form is a component that a connection's form opens as it is, so the Systems screen reuses it.
  - Texts are translated by their codes.
- **3. The end of a certificate.** `class:B`, labelled `later`, after 1b. A key pair whose leaf certificate ends within 30
  days is a WARNING once a day and a warning on Home and on the Secrets screen. Partners' trusted certificates, when they
  come, join the same watch.
