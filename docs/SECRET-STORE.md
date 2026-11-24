# The secret store

This document is the agreed design of where Rail8 keeps the secrets that flows use:
- the three kinds of secret and their types in `rail8-api`, and how an adapter and custom code take each;
- the table in Postgres, the encryption and the key, and how the key is changed;
- how every node of a pool learns of a change, without a restart;
- which source wins when the store and the node's environment both hold a name;
- what a deploy, a restore and a follow check;
- the pool's git credentials as a secret of the store;
- the admin routes, the audit and the limits.

It is built in the steps listed at the end, and no step reworks what an earlier one built.

Today a secret is an environment variable: `secret://<name>` reads `RAIL8_SECRET_<NAME>` (or its `_FILE` form) once at the
node's start, a new value takes a restart of every node, and a value is plain text only. The pool's git credentials are
`RAIL8_GIT_USER` and `RAIL8_GIT_TOKEN`.

## What the store protects, and what it does not

These limits are accepted. They are stated first, and the documentation of the operator says them the same way.

- **A lost key loses every secret.** The store's key is never in Postgres. Back it up apart from the database and its
  backups; a backup of the database without the key holds nothing that can be read. The only way back from a lost key is
  to enter every secret again.
- **It protects a leaked database or a leaked backup, not access to the server.** Whoever can read a node's memory, its
  environment or the file of its key reads every secret. The store is not a vault, and the documentation never calls it
  one.
- **Whoever deploys can reach every secret.** A flow names any secret by its name, and its code can send the value
  anywhere. Binding a secret to a package comes later.

## Rules

- A secret has a name, a kind, a scope and a value. The name is the one `secret://<name>` uses: `[a-z0-9][a-z0-9._-]*`,
  1 to 128 characters. References do not change.
- The scope is `installation` for every secret now. It is part of the key of a row and of what the encryption binds, so a
  narrower scope can come later with no migration of values.
- There are three kinds: a plain value, credentials (a user and a password), and a key pair with its certificates.
- The role `secret-administrator` creates, overwrites and deletes. Nobody reads a value back: no route, no answer, no log
  line and no audit row holds one.
- A developer sees each secret's name, kind, who changed it last and when, and which deployed flows use it.
- Values are sealed in Postgres under the store's key, which comes from the node's environment or a file.
- A change reaches every node of the pool at once, without a restart.
- The node's environment stays a second source for plain values: `RAIL8_SECRET_<NAME>`. The store wins.
- The platform's own credentials (Postgres, S3, Valkey, ClickHouse, the admin token, the journal's key and the store's key)
  stay in the node's environment and never enter the store.
- Names that start with `rail8.` belong to the platform. A flow cannot reference one.

## Kinds

| Kind | Id | Holds | From the environment |
| --- | --- | --- | --- |
| Plain value | `value` | a text of 1 character to 32 KiB, UTF-8 | yes, `RAIL8_SECRET_<NAME>` |
| Credentials | `credentials` | a user of 1 to 256 characters and a password of 1 to 4,096 | no |
| Key pair | `key-pair` | a private key, its public key, and 0 to 10 certificates, the leaf first | no |

A key pair is written as text: the private key in PEM, PKCS#8 (`BEGIN PRIVATE KEY`) or unencrypted OpenSSH
(`BEGIN OPENSSH PRIVATE KEY`), and the certificates in PEM. The node checks it at the write and keeps it in one form:
- The private key is RSA of 2,048 to 8,192 bits, EC on P-256, P-384 or P-521, or Ed25519. An encrypted key, another
  PEM type or another algorithm is refused, and the answer names the `openssl` command that converts a PKCS#1 or SEC1 key.
- The public key is derived from the private key (bcprov's `OpenSSHPrivateKeyUtil` and `PrivateKeyInfoFactory`, with
  their exact signatures checked with `javap` on the resolved jar when the step builds it).
- The leaf certificate's public key equals the pair's, and each further certificate signed the one before it. Neither
  trust nor dates are checked: an expired certificate is stored, and the list shows its end.
- The stored form is the PKCS#8 encoding of the private key, the X.509 encoding of the public key, and the DER of each
  certificate.

## Types in `rail8-api`

`Secret` stays the one handle, and gains its kind and two value types (version 0.4 of the API):

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
    public X509KeyManager keyManager();    // KEY_PAIR with a certificate only
}
```

- Each read returns the value now. The handle keeps nothing, so a value overwritten in the store is seen at the next read.
  A read of another kind than the handle's is an `IllegalStateException` naming the reference and both kinds.
- `keyManager()` presents the pair for an HTTPS client certificate. The alias it chooses for a handshake names the value
  it read then, and the private key and chain it returns for that alias are that value's, so a handshake never mixes a
  key of one value with a chain of another. It remembers the last 8 values it handed out.
- `toString` of the handle is its reference, as today.

### How an adapter takes one

- `Field.secret(name, help)` takes a plain value, as today. `Field.secret(name, help, Kind... kinds)` takes the kinds it
  names. The settings description serves them as `"kinds": ["value", "key-pair"]`, and the UI's form shows them.
- `EndpointConfig` gains a component `Map<String, Secret> secrets` and a method `Optional<Secret> secret(String option)`.
  Its three-component constructor stays and gives no handles.
- For an option of a secret field whose value is `secret://<name>`:
  - `secrets` holds its handle, whatever its kind;
  - `options` holds the value itself when the secret is a plain value, as today, and the reference when it is
    credentials or a key pair.
- A reference in any other option is a plain value in `options`, as today. Credentials and key pairs are taken only by a
  secret field that names their kind.
- An adapter reads a handle at each use: each connection, login or handshake. It never reads one while it builds a
  consumer or a producer. The component contract suite checks that: it builds a consumer and a producer with handles
  that count their reads, and fails on a read before the first use.
- The SFTP component's `privateKey` takes a plain value in OpenSSH form, as today, or a key pair, which it presents
  through MINA SSHD's `KeyIdentityProvider` over `keyPair().pair()`, read at each session. Its `password` takes a plain
  value or credentials; with credentials, the user comes from the secret and the `user` option is refused.
- Every component's mock takes the same kinds as the real one and passes the same suite.

### How custom code takes one

- `Services.secret(step, key)` takes a plain value, as today. `Services.secret(step, key, Kind... kinds)` takes a secret of
  the kinds it names, and the deploy refuses one of another kind.
- The handle is read at each use, as today. `FakeServices` in the testkit gives a step under test its secrets of every
  kind, and lets the test change one between two messages.
- `rail8 validate` and `rail8 test` read a plain value as `https://secret.invalid/`, credentials as user `rail8` and
  password `invalid`, and a key pair as a fixed EC P-256 pair with a self-signed certificate, generated in the testkit's
  resources and named invalid.

## Sources

- The store wins. For a name the store does not hold, the node reads `RAIL8_SECRET_<NAME>` as today, a plain value only.
- After a delete, the variable takes over on each node that sets it. The answer to the delete says so (`fallsBackTo`).
- The list marks a stored secret that the answering node's environment also sets (`shadows`), so a stale variable is seen
  before a delete reveals it.
- A row that the node cannot open never falls back to the variable: the name counts as set but unreadable.

## Storage

```sql
create table secret_store (
    one         boolean primary key default true check (one),
    generation  bigint  not null,       -- raised by every write; the rows carry it
    writing_key bytea   check (length(writing_key) = 8)
);

create table secrets (
    scope      text        not null check (scope = 'installation'),
    name       text        not null check (name ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    kind       text        not null check (kind in ('value', 'credentials', 'key-pair')),
    generation bigint      not null,
    key_id     bytea       not null check (length(key_id) = 8),
    nonce      bytea       not null check (length(nonce) = 12),
    sealed     bytea       not null,    -- the ciphertext and its 16-byte tag
    created_at timestamptz not null,
    changed_at timestamptz not null,
    changed_by text        not null,    -- the user's name, as the audit row names it
    primary key (scope, name)
);

alter table nodes add column secret_key bytea, add column secret_key_previous bytea;
alter table deployments add column secret_uses jsonb;      -- null: not known
```

- The value is a JSON object in UTF-8: `{"value"}`, `{"user", "password"}`, or `{"privateKey", "publicKey",
  "certificates"}` in base64. It is padded with spaces to the next multiple of 256 bytes, so a row tells no value's
  length within 256 bytes. No digest of a value is stored anywhere.
- It is sealed with AES-256-GCM under a key derived from the store's key by HKDF-SHA256 (info `rail8 secrets v1 seal`),
  with a random 96-bit nonce for every seal. The associated data is the format version, the scope, the name, the kind,
  the generation and the key's id, so a sealed value moved to another row, another kind or another generation does not
  open.
- `key_id` is the first 8 bytes of HKDF-SHA256 of the key with info `rail8 secrets v1 id`: it tells keys apart and
  tells nothing of the key.
- Every write raises `secret_store.generation` by one, under its row lock, and writes the new generation into the row it
  writes. A delete removes the row. A node refuses a row whose generation is lower than the one it holds for that name:
  it logs a WARNING naming the name and both generations, and keeps what it holds.
- `nodes.secret_key` and `nodes.secret_key_previous` are the ids of the keys a node holds, written when it joins.
- `deployments.secret_uses` is written with the record: `[{"name", "kinds", "flows"}]`, the names the package's flows use,
  the kinds each use takes, and the flows. A record written before the store has none: null, not known.

## The key

- `RAIL8_SECRETS_KEY`, or `RAIL8_SECRETS_KEY_FILE` (preferred): the base64 of 256 random bits, as
  `openssl rand -base64 32` writes it, read once at start, as the node's other secrets are. One key per installation,
  shared by all its nodes.
- It is not the journal's key. A node whose two keys are equal refuses its start.
- `RAIL8_SECRETS_KEY_PREVIOUS` (or `_FILE`) is set only while the key is changed.
- With no key and an empty store, the node starts and reads secrets from its environment only; a write is 503 `no-key`.
- With no key and a store that holds a row, the node refuses its start, naming the number of rows and the ids of their
  keys.
- A row sealed under a key the node does not hold refuses its start the same way. A row that does not open under a key
  the node holds is unreadable: the flows that use it are `FAILED`, `/health` is `DEGRADED`, the list shows it
  `unreadable`, and a WARNING names the name and the key's id once.

### Changing the key

Two rolls of the pool's nodes, and no call:

1. Each node is restarted with the new key as `RAIL8_SECRETS_KEY` and the old one as `RAIL8_SECRETS_KEY_PREVIOUS`.
   Writes meanwhile go on under the old key, which every node holds.
2. Once every live node (a row of `nodes` whose lease holds) names the same current key and it is not the writing key, a
   node's next tick sets `writing_key` to it and logs it at INFO. From then on writes go under the new key, which every
   live node holds. Each tick then seals again up to 100 rows still under another key, in one transaction under the
   store's row lock; a row keeps its generation.
3. Once `GET /admin/secrets` shows no row under the old key, each node is restarted without
   `RAIL8_SECRETS_KEY_PREVIOUS`.

- A write is refused with 409 `keys-differ` unless every live node holds the writing key, current or previous. So a node
  that does not hold it, or a node of a version before the store, holds writes back until it leaves or is rolled.
- A node that starts without the old key while rows remain under it refuses its start (above). A pool rolled back to the
  old key switches back the same way.
- The switch and the sealing again are logged at INFO with the key ids and the counts, and leave no audit row: no user
  acted and no value changed.

## How every node learns of a change

- A write commits its row, the store's generation, its audit row and `pg_notify('rail8_secrets', '<generation>')` in one
  transaction.
- Each node listens on `rail8_secrets` on the connection it already holds for `LISTEN`. On a notice, after every `LISTEN`
  acknowledged (a reconnect may have lost notices), and at each 5 s tick on the injected timer, it reads
  `secret_store.generation`. When that is ahead of what it holds, it reads every row's name, kind, generation and key id,
  opens the rows whose generation changed, and drops the names that are gone. The payload is only a wake-up.
- The node that wrote reads again before it answers, so a write is in force on that node when it is answered.
- The values are held in the node's memory. A read of a secret never goes to Postgres.
- A flow that holds a plain value in an endpoint's `options` is built again with the new value. A flow's effective hash
  takes the name and generation of each value it holds in `options`, and the name, kind and presence of each handle it
  holds, never a value. The node redeploys the record it runs, locally, as a follow does: only the flows whose hash
  changed are built again. A handle needs no rebuild: its next read has the new value.
- A flow whose secret is deleted or becomes unreadable is built again too, and its build fails: it is listed `FAILED`
  with "secret '<name>' is not set" and tried again at each tick, so it runs again once the name is set.

## Git credentials

- The pool's git credentials are the credentials secret `rail8.git`. The variables `RAIL8_GIT_USER` and
  `RAIL8_GIT_TOKEN` stay the second source, so a node starts with an empty store.
- The token rules of the deploy from git keep holding. The token is read-only, for the `https` base, one for the base; it
  is sent only as the `https` credentials of the base's host; it is never in git, in a URI, in a log or in an answer. In
  Postgres it is only sealed in the store. The base stays in the node's environment, so a secret administrator cannot
  point the node at another host.
- The git cache reads the credentials at each fetch and each check, behind the check of the same origin that it makes
  today.
- A change of `rail8.git` starts the check of the credentials at once on every node, out of its turn. `GET /admin/git`
  adds `credentialsFrom`: `store`, `environment` or `none`.
- A flow, `rail8 validate` and a deploy refuse a reference to any `rail8.` name. Later platform secrets, such as the
  client secret of OIDC, take names under `rail8.` too.

## Admin routes

Each route is mounted with its action, and the action is checked before the body is read. A body is JSON of at most
48 KiB.

| Route | Action | Does |
| --- | --- | --- |
| `GET /admin/secrets` | `secret.list` | every secret: name, kind, scope, `source` on the answering node, `changedAt`, `changedBy`, `usedBy`, `shadows`, `unreadable`, and for a key pair its algorithm, size, the leaf's subject, issuer and end, and the SHA-256 fingerprint of its public key; the names the records use that are set nowhere on this node; and the key's state: the writing key, each live node's keys, and the rows under another key |
| `GET /admin/secrets/{name}` | `secret.list` | one of them, 404 when no source holds it and no record uses it |
| `POST /admin/secrets` | `secret.write` | creates `{name, kind, value}`, `{name, kind, user, password}` or `{name, kind, privateKey, certificates}`; 409 `exists` |
| `POST /admin/secrets/{name}` | `secret.write` | overwrites it with a value of the same kind; 404 when the store does not hold it, 409 `kind-differs` |
| `DELETE /admin/secrets/{name}` | `secret.delete` | deletes it; 409 `in-use` with `usedBy` unless `?confirm=<name>` names it; the answer has `fallsBackTo` |

- A create and a delete take the pool's deploy lock without waiting, as a deploy does, and are 409 `deploy-running`
  while one runs. So a deploy never checks a name that a delete removes before its record is written. An overwrite does
  not take it: it changes neither a name nor a kind.
- A create is refused with 409 `kind-in-use` when a record uses the name with kinds that do not include the new one.
- A delete counts a package as a user of the name when its record lists the name, and also when its record's uses are not
  known; those are answered as `mayUse`.
- A value of the wrong form or out of its limits is 422, naming the field and never echoing the value.
- A write is 503 `no-key` without a key, 409 `keys-differ` as above, and 503 when its audit row cannot be written.
- `secret.list` holds no value; the developer and the secret administrator have it, as the roles' table says.

## Audit

- `secret.create`, `secret.overwrite` and `secret.delete`, each with the subject `secret/<name>`, written in the
  transaction of the change: a change whose row cannot be written does not happen.
- The detail is the kind; for a key pair, the fingerprint of its public key and the leaf's end; for a delete, whether it
  was confirmed and the packages it named. Never a value, a user name of credentials, a password or a private key.
- The actor, the way and the credential come from the check, as for every route.

## Checks

At a deploy, `rail8 validate` and a restore, each reference:
- is well formed, at most 128 characters, and not under `rail8.`;
- is set on the node in the store or in the environment (`rail8 validate` skips this);
- has a kind that its place takes: a secret field that names the kind, `Services.secret` with the kind, or a plain value
  anywhere else.

A refusal names the flow, the endpoint or step, the option, the name and its source, never a value. A row that does not
open is a problem naming its key's id.

- At a deploy, a problem refuses the deploy, as a missing variable does today.
- At a restore and a follow, a problem refuses the package whole: it is listed `FAILED` with its problems and tried again
  at each tick, as today. A secret created later lets the next tick install it.
- A deploy writes the record's `secret_uses` from what its flows took while they built.

## Scrubbing

- `FlowSecrets` keeps, for each handle it handed out, the current value and up to 7 values read before it, at most 64 KiB
  per handle and 256 KiB per flow, the oldest dropped first. A failure that holds a value from before a change is still
  scrubbed.
- It scrubs a plain value; the password of credentials and the base64 of `user:password`; and a key pair's private key
  in base64, whole and in each 64-character line of its PEM.
- The plain values that an endpoint holds in `options`, and the handles of its secret fields, join the flow's set.
- Longer values are replaced first, as today.

## Limits

| What | Limit |
| --- | --- |
| Secrets in the store | 1,000 |
| All sealed values together | 16 MiB |
| A plain value | 32 KiB |
| A user; a password | 256 characters; 4,096 characters |
| A key pair | RSA 2,048 to 8,192 bits; EC P-256, P-384, P-521; Ed25519; 0 to 10 certificates of at most 8 KiB each |
| A name | 1 to 128 characters |
| A request's body | 48 KiB |
| Rows sealed again per tick | 100 |
| Values a handle remembers for scrubbing | 8 and 64 KiB; 256 KiB per flow |
| Values `keyManager()` remembers | 8 |
| The time a change takes to reach a node | the notice; at most one tick, 5 s, when a notice is lost |

## Against an attacker

| What an attacker tries | What stands against it |
| --- | --- |
| reading a dump or a backup of the database | every value sealed under a key that is never in Postgres; lengths hidden within 256 bytes; no digest |
| moving a sealed value to another name or kind | the name, scope, kind, generation and key id are bound into the seal |
| putting an older row back | a node refuses a lower generation than it holds; a fresh node cannot tell (accepted) |
| deleting rows or the key id in the database | flows fail closed and never fall back to a variable; a node refuses its start rather than run on part of the store (accepted: whoever writes the database can stop the pool) |
| reading a value back through the API | no route answers one; the audit and the logs never hold one; a refusal never echoes one |
| a developer learning values | the list holds names, kinds, dates, uses and public facts of certificates only |
| a write that a node cannot read | 409 `keys-differ` unless every live node holds the writing key |
| a key typed wrong on one node | that node refuses its start, naming the key ids, before it runs a flow |
| deleting a secret a flow uses, by mistake | 409 `in-use` until the name is written out; a package whose uses are not known counts |
| a deploy racing a delete | both take the pool's deploy lock |
| a stale variable taking over after a delete | `shadows` in the list, `fallsBackTo` in the answer |
| a flow reading the git token | `rail8.` names cannot be referenced |
| a secret administrator pointing git elsewhere | the base stays in the environment; the token goes only to the base's host |
| a value in a failure, a log line or an answer | the scrubber, with the values from before a change |
| a value too short to scrub safely | accepted: a value of a few characters can be read off what the scrubber replaced, and is weak anyway |
| reading a node's memory, environment or key file | accepted: the store does not protect access to the server |
| a deployed flow sending a secret away | accepted: whoever deploys reaches every secret |
| a secret administrator changing a value that a flow uses as its address | accepted: the role changes what flows send; the audit names who |

## Tests

- One contract suite, `SecretStoreContract`, runs for the Postgres store (Testcontainers) and an in-memory one. Two Postgres
  stores over one database stand for two nodes, each with its own fake clock and timer. Across them:
  - a write on one is read on the other after the notice, and after one tick with the notice dropped;
  - a delete falls back to the variable, and a confirmed delete fails the flows that used it;
  - a row under a key a node does not hold refuses its start; a tampered row is unreadable and never falls back;
  - a lower generation is refused;
  - the change of the key: writes during each roll, the switch once every node holds the new key, the sealing again in
    batches, the start refused without the old key while rows remain, and 409 `keys-differ` with a node of the old set.
- The seal: each bound field changed one at a time fails to open; two seals of one value differ; values of 1 and 255
  bytes give rows of one length.
- The routes: each as every role, through a session and a token; a create and a delete during a deploy are 409; a delete
  with records of unknown uses needs the confirmation; no answer, log line or audit row of any test holds a value, checked
  by searching every captured text for each value written.
- `FlowSecrets`: a failure thrown with a value from before an overwrite is scrubbed; the limits drop the oldest value.
- The adapter contract: a build that reads a handle fails the suite; `keyManager()` gives one value's key and chain for a
  handshake that straddles an overwrite.
- The SFTP component against its mock and the SFTP server of the compose stack, by password, by credentials, by a key in
  OpenSSH form and by a key pair, with the key pair overwritten between two sessions.
- Git: a fetch after `rail8.git` is overwritten sends the new token, and the check runs at once; a redirect to another host
  still sends nothing.
- A fake clock and an injected timer move every tick. No test sleeps.

## Values that are not secret, later

A per-environment value that is not secret, such as an address or a user name entered in each installation, fits this
storage format with no change to it. It would be a fourth kind. Adding it widens the check on `kind`, and the seal
already binds the kind. Its rows, the key, the notices, the tick and the node's in-memory read path stay as they are, and
so do the rebuild of the flows that hold it and `secret_uses`. What would have to be added:
- a reference form of its own in flow files, and the form noted in each entry of `secret_uses`;
- a route that answers its value back, with actions of its own, since the secret administrator's role is about secrets;
- its history: an audit detail that holds the old and the new value for that kind only, or a table of its own.

## Out

- OAuth clients and partners' trusted certificates.
- Reading a value back, and a history of values.
- An external vault as another source.
- The client secret of OIDC in the store: it moves there with the OIDC step, as a `rail8.` name.
- Binding a secret to a package, and scopes narrower than the installation.
- A key from a KMS, and a key per scope.
- Credentials or key pairs from the environment.
- A command of the command line for secrets.

## Steps

Each step is one issue, built and merged on its own. Step 1 can run beside the login step of the users' design.

- **1a. The store.** `class:C`. The migration; the seal, the key and its change; the notices and the tick; the routes with
  their actions, locks and audit; the checks of a deploy, a restore and a follow, and `secret_uses`; the rebuild of flows
  whose value changed; `FlowSecrets` with the values from before a change; the git credentials as `rail8.git`, read at
  each fetch, and `credentialsFrom`. The API's value types `Secret.Kind`, `Secret.Credentials` and `Secret.KeyPair` come
  with it, since the store holds them, and the key pair's checks at the write. A flow takes plain values only.
- **1b. Credentials and key pairs in flows.** `class:C`, after 1a. `Field.secret` with kinds, `EndpointConfig.secrets`,
  `Services.secret` with kinds, `keyManager()`, the component contract's check that a build reads no handle, the testkit's
  fakes and the placeholders of `rail8 validate`, and the SFTP component by credentials and by key pair, with its mock.
  It is apart from 1a because it changes public types every adapter builds on, and is reviewed as a contract of its own.
- **2. The Secrets screen.** `class:B`, after 1a. A developer's list with the uses, and a secret administrator's create,
  overwrite and delete with its confirmation, the key's state and the `shadows` mark.
- **3. The end of a certificate.** `class:B`, labelled `later`, after 1b. A key pair whose leaf certificate ends within 30
  days is a WARNING once a day and a warning on Home and on the Secrets screen. Partners' trusted certificates, when they
  come, join the same watch.
