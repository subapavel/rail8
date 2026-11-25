# The configuration of an environment

This document is the agreed design of what an environment holds outside git and how it is changed:
- the objects it holds, each with a draft, a version in force and a history;
- the tables in Postgres, the versions kept and how long the history is kept;
- how every node learns of an activation within seconds, without a restart, and what a message in flight sees;
- what a deploy checks against it, and how the deploy's record names the state it ran against;
- where a secret may go: the secret administrator's consent, and the binding under the secret store's key;
- the admin routes with their actions, the two new roles, the audit;
- the format of export and import, its versions, and the command `rail8 config`;
- what the backup of Postgres covers, and how secrets reach a new environment.

It is built in the steps listed at the end, and no step reworks what an earlier one built.

Today everything a flow needs is in files of its package. A difference between environments is an override in an
endpoint file, and the UI changes a running system only through a deploy of a commit, the secret store being the one
planned exception. The design of systems and connections takes the values of a connection out of git: a package names
a system and its connections, and each environment holds the values. Until this store is built, a node reads those
values from a file at its start.

**What this replaces:**
- Differences between environments as overrides in files, for connections: a connection's values are entered in each
  environment, or imported there from files that belong to no package.
- "The UI never changes a running system": the UI changes one in two places, the secret store and this configuration.
  Everywhere else a change reaches a node as a deploy of a commit.
- "Five fixed roles": with the connection administrator, the partner administrator and the message sender of the
  design of who may send to a flow there are eight, in one list that says who may hold each.

## Rules

- Git holds what is developed and what a flow names. The configuration holds what differs between environments and
  what is looked up only at run time.
- Every object has at most one draft and at most one version in force. A saved change is a draft; the environment runs
  on the version in force. Activating checks that the draft is complete and puts it in force. Activation is per object:
  there are no change lists over several objects.
- Every object has its history: who, when, the old and the new value. A secret is named in it, never its value. Going
  back to an earlier version is a new draft, checked again when it is activated.
- Every write states what its author saw. A write over someone else's change is refused and changes nothing.
- A change in force holds for every later attempt to send, a retry waiting in the queue included.
- Every node has a change in force within seconds, without a restart, and the screen shows which nodes have it.
- Two stores: the secret store keeps the values of secrets, sealed; the configuration keeps references to them by name.
  The screen of a connection is one form with its secrets: a secret field picks a secret by name and kind, and a user
  who holds both roles creates one from the form, through the secret store's own route.
- A secret goes through a new field, to a new adapter or to a new destination only with the secret administrator's
  consent, given to exactly what the draft sends.
- Everything the UI does goes through the admin API, and the UI uses nothing else.
- Export and import use one documented, versioned format, one file per object. A file holds no secret's value and no
  state. An import is first a dry run, then writes drafts. It adds and changes; it never deletes and never activates.
- Only `rail8-runtime` reads or writes these tables. Clock, timers, ids and random numbers are injected.
- Every refusal carries a stable code and an English text. The UI translates by the code.

## Objects

An object is a **kind** and a **key**. The keys are those of the design of systems and connections: segments
`^[a-z][a-z0-9-]{0,63}$`, never `rail8` first; a system key is `eshop` or `acme/erp`, a connection key `eshop/rest` or
`acme/erp/rest`.

| Kind | Key | Body | Complete when |
| --- | --- | --- | --- |
| `connection` | a connection key, two or three segments | `{adapter, values, mock?}` | mocked; or `values` pass the adapter's connection form for the directions it is used in, and every secret it names is set, of a kind its field takes |
| `system` | a system key, one or two segments | `{instanceName}`, 1 to 128 printable characters | always, once its shape is right |

- `values` are the fields of the adapter's connection form, the format 2 description at `GET /admin/components`. A
  secret field holds `secret://<name>` and nothing else; no other field holds `secret://`. A name under `rail8.` is
  refused. A URL holds no user information.
- `mock` is the platform's built-in mock: `{reply: echo | empty, delay?, fail?: retryable | invalid}`, the delay at most
  60 s. While it is set the connection is mocked, and `values` are kept as they were, unchecked, so switching the mock
  off brings them back. A mocked connection sends no secret.
- A **removal** is a draft too: `{removed: true}`, activated like any other change.
- Each stored body carries its kind's format number, as a file does, and is read through the same chain of upgrades.
- Whether a connection is **stopped** is not part of its body. It is never drafted and never exported. An operator or
  a connection administrator stops and starts it at once. A removal clears it.
- A connection may be set before its system is deployed: configuring before the first deploy is the normal order. The
  save names the adapter; once a deployed system file names the connection, its adapter must be that one.
- Later kinds join the same tables, routes, history and format, each with its own body, checks and limit: a system's
  credentials, identifiers, partner profiles, API products, subscriptions, agreements and number sequences. A kind whose
  object refers to another holds the reference by key, never a copy of its values, so that it moves between
  environments unchanged. A number sequence's current number is state: it lives in a table of its own, written in the
  message's transaction, and is never a version, a draft or a line of an export.

## Three numbers

| Number | Of | Raised by | Stated by |
| --- | --- | --- | --- |
| `revision` | one object | a save, a discard, an activation | every save, discard, consent and activation, as `expect` |
| `version` | one object | an activation: 1, 2, 3 … | going back, as `fromVersion` |
| `generation` | the environment | an activation, a stop, a start | the deploy's record; each node's report |

- A stop or a start states the state it saw (`expect: "running"` or `"stopped"`), not the revision. So an operator who
  stops a connection never refuses an administrator's pending activation, nor the other way round.
- A consent and a test raise nothing. A draft never raises the generation and never wakes a node.

## Storage

```sql
-- The environment's counter: raised, with a new random stamp, by every activation, stop and start.
create table config_store (
    one        boolean primary key default true check (one),
    generation bigint  not null check (generation >= 0),
    stamp      bigint  not null
);

-- One row per object: its version in force, its draft with the consent to it, and a connection's state.
create table config_objects (
    kind     text   not null check (kind in ('connection', 'system')),
    key      text   not null,
    revision bigint not null check (revision >= 0),
    -- the version in force
    version      int not null default 0 check (version >= 0),   -- 0 before the first activation
    format       smallint,
    body         jsonb,                                         -- null before the first activation and after a removal
    key_id       bytea check (length(key_id) = 8),              -- the binding (below); null: not bound
    tag          bytea check (length(tag) = 32),
    activated_at timestamptz, activated_by text,
    changed_generation bigint not null default 0,               -- the generation of its last activation, stop or start
    -- the draft; null when there is none
    draft_format smallint, draft jsonb, draft_at timestamptz, draft_by text,
    -- the secret administrator's consent to what the draft sends
    consent        bytea check (length(consent) = 32),          -- the digest of the draft's sends (below)
    consent_key_id bytea check (length(consent_key_id) = 8),
    consent_tag    bytea check (length(consent_tag) = 32),
    consent_at     timestamptz, consent_by text,
    -- a connection's state
    stopped_at   timestamptz, stopped_by text,                  -- null: running
    primary key (kind, key),
    check (kind = 'connection' and key ~ '^(?!rail8/)[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63}){1,2}$'
        or kind = 'system' and key ~ '^(?!rail8(/|$))[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63})?$'),
    check ((body is null) = (format is null)),
    check ((key_id is null) = (tag is null)),
    check ((draft is null) = (draft_format is null)),
    check (consent is null or draft is not null),
    check ((consent_key_id is null) = (consent_tag is null)),
    check (kind = 'connection' or stopped_at is null)
);

-- Each object's history. The row of an activation holds the body it put in force: these rows are the versions kept.
create table config_history (
    id         bigint generated always as identity primary key,
    kind       text not null,
    key        text not null,
    at         timestamptz not null,
    actor      text not null, user_id bigint, via text not null, credential text,   -- as the audit names them
    change     text not null check (change in ('save', 'import', 'discard', 'activate', 'stop', 'start')),
    revision   bigint not null,                    -- the object's revision after the change
    generation bigint,                             -- an activation, a stop, a start: the generation it made
    version    int,                                -- an activation: the version it made
    format     smallint, body jsonb,               -- an activation: the body it put in force
    diff       jsonb not null                      -- [{field, from, to}]; a secret field by its reference only
);
create index config_history_object on config_history (kind, key, id);

alter table nodes add column config_generation bigint, add column config_stamp bigint, add column config_epoch bigint,
    add column config_refused text[];              -- the objects it left out, at most 100
alter table deployments add column config_generation bigint;   -- null: written before the store
```

- The migration inserts the one row of `config_store`: generation 0 and a random stamp.
- **The versions kept** are the activation rows of the history. Going back copies one of them into a draft.
- **Retention.** The node's retention job deletes history rows older than `RAIL8_AUDIT_MAX_AGE` (P30D to P3660D, P365D by
  default), in its batches. A write that would give an object more than 1,000 history rows deletes its oldest rows that
  are not among its 100 newest activations, so saves, stops and starts never push out the versions kept. The version in
  force and the draft are in `config_objects` and are never deleted by retention, so an object can always be read and
  changed. A version whose history row is gone cannot be brought back.
- A row with no version in force, no draft and no history row left is deleted by the same job.
- `nodes.config_generation`, `config_stamp`, `config_epoch` and `config_refused` are what each node holds. Nothing
  decides by them.

## Roles and actions

One list of roles. Each says who may hold it: a person, a technical account, or both.

| Role | Id | Held by | Holds |
| --- | --- | --- | --- |
| Administrator | `administrator` | a person | users, roles, technical accounts and their tokens, sessions, the audit |
| Developer | `developer` | both | deploys, the deployed configuration, the modules' services |
| Operator | `operator` | both | monitoring, retry, cancel, release, discard; stops and starts a connection |
| Message bodies | `message-bodies` | both | a message's body after each step |
| Secret administrator | `secret-administrator` | both | the secret store; consents that a secret may go where a draft sends it |
| Connection administrator | `connection-administrator` | both | systems in the environment, the values of connections, credentials, instance names, technical identifiers |
| Partner administrator | `partner-administrator` | both | partner profiles, business identifiers, agreements, API products, subscriptions, number sequences |
| Message sender | `message-sender` | both | sends a test message to a flow (the design of who may send to a flow) |

| Action | Routes | Granted by |
| --- | --- | --- |
| `config.read` | every `GET /admin/config…` | developer, operator, connection-administrator, partner-administrator, secret-administrator |
| `config.connections` | save, discard and activate of connections and systems; a test of a draft | connection-administrator |
| `config.run` | stop, start, and a test of the version in force | operator, connection-administrator |
| `config.consent` | a consent and its withdrawal | secret-administrator |
| `config.import` | the import's door; each file is then checked by its kind's write action | connection-administrator, partner-administrator |

- `secret.list` gains the connection administrator and the partner administrator, so a form picks a secret by name and
  kind. They gain no other action of the secret store.
- The partner administrator holds `config.read`, `config.import` and `secret.list` until its first kind comes. That kind
  brings `config.partners`, its write action, granted by the partner administrator alone.
- A token may do every action here: a pipeline with a technical account imports and activates. A consent by a token is a
  secret administrator's act like any other: it states the digest of what it consents to, and the audit names its
  account.

## Admin routes

A key holds `/`, so a verb follows the key after the segment `-`, which no key segment can be. Each verb is mounted as a
route of its own with its action, so the action is checked before the body is read. A body is JSON of at most 64 KiB,
an import's at most 4 MiB.

| Route | Action | Body | Does |
| --- | --- | --- | --- |
| `GET /admin/config/connections?prefix&needs&after&limit` | `config.read` | | the list: key, adapter, state, version, whether a draft waits, whether it needs consent, the nodes behind; `needs=consent` or `needs=activation` narrows it |
| `GET /admin/config/connections/{key}` | `config.read` | | one connection, as the screen shows it (below) |
| `POST /admin/config/connections/{key}/-/save` | `config.connections` | `{expect, adapter, values, mock?}`, `{expect, removed: true}` or `{expect, fromVersion}` | writes the draft; creates the row when there is none |
| `POST /admin/config/connections/{key}/-/discard` | `config.connections` | `{expect}` | drops the draft and its consent |
| `POST /admin/config/connections/{key}/-/consent` | `config.consent` | `{expect, digest}`; `digest: null` withdraws | consents to exactly what the draft sends |
| `POST /admin/config/connections/{key}/-/activate` | `config.connections` | `{expect, confirm?}` | puts the draft in force |
| `POST /admin/config/connections/{key}/-/stop`, `/-/start` | `config.run` | `{expect: "running" \| "stopped"}` | stops or starts it at once |
| `GET /admin/config/connections/{key}/-/history?after&limit` | `config.read` | | its history, newest first, 50 a page, at most 500 |
| `GET /admin/config/systems?prefix&after&limit`, `GET /admin/config/systems/{key}` | `config.read` | | the systems' instance names |
| `POST /admin/config/systems/{key}/-/save`, `/-/discard`, `/-/activate`; `GET …/-/history` | as for connections | `{expect, instanceName}` | the same, without consent, stop and start |
| `GET /admin/config/export?kind&prefix` | `config.read` | | `{files: [{path, text}]}`: every version in force |
| `POST /admin/config/import?check=true` | `config.import` | `{files}` | the dry run |
| `POST /admin/config/import` | `config.import` | `{files, expect}` | writes the drafts, all or none |

A read of a key that no row holds and no deployed system names is 404 `not-found`. A key that a deployed system names
and no row holds reads as an object of revision 0 with nothing in force. A save of a key no row holds states
`expect: 0` and creates the row.

`GET /admin/systems` of the design of systems and connections reads each connection's state from here: set, mocked,
stopped, missing, or a draft waiting.

### One connection, as the screen shows it

```json
{"kind": "connection", "key": "acme/erp/rest", "system": "acme/erp", "adapter": "http", "revision": 12,
 "inForce": {"version": 4, "values": {"url": "https://erp.acme.example:8443/api", "timeoutMs": 5000,
             "password": "secret://erp-pw"},
             "activatedAt": "…", "activatedBy": "jana", "generation": 381, "binding": "ok"},
 "draft": null,
 "stopped": null,
 "secrets": [{"field": "password", "name": "erp-pw", "kinds": ["value", "credentials"], "kind": "value",
              "set": true, "changedAt": "…", "changedBy": "petr", "unreadable": false}],
 "nodes": [{"node": "node-a", "holds": true}, {"node": "node-b", "holds": false, "generation": 380}],
 "usedBy": [{"flow": "invoices/invoice-async", "step": "partner", "direction": "out"}],
 "form": ["the adapter's connection form, format 2"]}
```

- With a draft: `"draft": {"values", "mock", "removed", "savedAt", "savedBy", "changes": [{field, from, to}],
  "problems": [{field, code, direction}], "consent": null | {"digest", "sends": [{field, secret}], "destination",
  "given": null | {at, by}}}`. `problems` and `consent` are computed at the read, so the form shows what an activation
  would refuse before anyone presses it. `consent` is null when the draft needs none.
- `binding` is `ok`, `none` (an installation without the store's key, below) or `fails`.
- A node `holds` the version in force when its lease holds, its `config_epoch` is its epoch, its `config_generation`
  is at least the object's `changed_generation`, and its `config_refused` does not name the object.
- `secrets` comes from the secret store's list: it shows that a secret changed, never its value.
- `usedBy` comes from `deployment_connection_uses`.

### Every write

1. The action at the door (403), then the body (400, 413).
2. One transaction. Locks in this order, which every writer keeps:
   1. the secret store's row, by an activation and a consent, which bind under the store's key as every seal does;
   2. `config_store` `for update`, by an activation, a stop and a start;
   3. the object's row `for update`, inserted at revision 0 when absent;
   4. the names' advisory locks, through the secret store's one method of uses.
3. `expect` is compared: 412 `changed`, answering the object as it is now. Then the verb's own checks (409, 422).
4. The row, its history row, the audit row where the audit lists one, the uses, the generation and stamp, and
   `pg_notify('rail8_config', '<generation>')`. Then the commit.
5. The node that wrote reads the store again before it answers, so a change in force holds on that node when it is
   answered. Every other node has it within one notice, or one tick when the notice is lost; the read's `nodes` shows
   which do.

**Save** checks the shape:
- the adapter is known to the node; it is the adapter the deployed system file names, when one does (409
  `adapter-differs`);
- each value is a field of the connection form, of its field's type; a URL holds no user information;
- a secret field holds only `secret://<name>`, and no other field holds `secret://` (422 `invalid`, naming the field,
  never echoing a value); no `rail8.` name (422 `reserved`).

A draft may be incomplete. `fromVersion` copies that version's body from the history into the draft. A save equal to the
version in force drops the draft. A save over a draft replaces it, and drops its consent when what it sends changes.

**Activate** checks against the state of its own transaction:
- **complete**: the values pass the connection form for each direction `deployment_connection_uses` names the key in; a
  connection that no deployed flow uses passes for at least one direction its adapter connects; every secret it names is
  set, as the secret store counts it, of a kind its field takes; a system keeps an instance name while a connection of
  it is used. Else 422 `incomplete`, one line per field and direction, never a value;
- **consented**: when the draft needs consent, its consent equals the digest of what the draft sends now and its tag
  verifies (409 `consent-needed`, with the sends and the destination fields);
- **a removal** of a connection that `deployment_connection_uses` names, or of a system with such a connection, needs
  `confirm` to list exactly the flows that use it now (409 `in-use`, naming them). Once confirmed, every send through it
  fails retryably and a polling sender is `DEGRADED`, as the design of systems and connections says;
- **the key**: every live node holds the key it binds under (409 `keys-differ`, the secret store's rule).

It then binds the body, writes `version + 1` and `changed_generation`, clears the draft and its consent, clears the stop
on a removal, and replaces the object's rows of `secret_uses` through the store's one method: its secret names, none for
a mocked or removed connection.

A **stop** of a connection with nothing in force is 409 `not-set`. A stop holds for every send that starts after it;
a send already started ends.

## Where a secret goes: consent

The secret administrator decides where each secret may go. A connection administrator decides everything else. The
consent is held on the object and given to the exact draft it covers.

- **Destination fields.** A field of an adapter's connection form counts as a destination unless the adapter marks it
  otherwise:
  - `.tuning()`: a field that never changes where bytes go, such as a time limit or a pool's size; it does not count;
  - `.origin()`: a URL counted by its scheme, host and port, so a new path needs no consent.
  Every other field that is not a secret counts whole: an address, a host, a port, a proxy, a trusted certificate, a
  host key, a switch of TLS verification. The platform's `allowedHosts` counts whole. A field counts only when it is
  set: a field left at its default is not part of the destination. An adapter that marks nothing asks for consent at
  every change; that is safe, and it is the adapter's to improve.
- **What a body sends.** A connection's sends are each secret field it sets, as the pair of field and secret name,
  together with its adapter and its destination. A mocked or removed connection, and a system, send nothing.
- **When a draft needs consent.** When it sends a secret through a field, an adapter or to a destination the version in
  force does not: a pair of field and secret that the version in force lacks, or another adapter or destination while
  it sends any secret. Taking a secret away, changing a tuning field, or changing a URL's path needs none.
- **The digest** is the SHA-256 of the RFC 8785 JSON `{adapter, destination, sends}`, the sends sorted. The read shows
  it with the sends and the destination fields.
- **Consent.** `/-/consent {expect, digest}` writes the digest on the row, bound, and one audit row `config.consent`
  naming the sends and the destination fields. When the digest is not that of the draft as it is now, it is 412
  `changed`, answering the object; so a draft changed after the secret administrator read it, through the API or in
  SQL, is never consented unseen. With no draft, or one that needs none, it is 409 `nothing-to-consent`.
  `digest: null` withdraws a consent given by mistake.
- **The consent belongs to its draft.** An activation uses it up; a discard, or a save that changes what the draft
  sends, drops it. A version in force carries its own consent in its bound body: what it sends is what was consented.
- **Going back** to an earlier destination is a new consent, unless it is the destination in force. In an emergency
  that is one more step for the secret administrator; it keeps a consent from outliving the draft it was given to.
- **The inbox.** `GET /admin/config/connections?needs=consent` is the secret administrator's list of drafts that wait.
- **A test** sends secrets as an activation does, and needs the same consent (the step that builds the test).
- The rule holds from the first step that stores a connection: a secret never goes through a new field, to a new
  address, proxy or trust, nor with verification off, without consent.
- A user who holds both roles consents with one call and activates with another, so the act is always visible as a
  consent in the audit. `rail8 config consent` takes the digest that the read shows, never computes it.
- A secret deleted and created again under the same name goes where the versions in force that name it send it. The
  secret store's record of uses lists them for the secret administrator who creates it.

## The binding

- Each body in force is bound: an HMAC-SHA256 tag through the secret store's binding (a subkey of the store's key, HKDF
  info `rail8 settings v1 bind`, named by the key's id) over the RFC 8785 JSON array `["rail8 config v1", kind, key,
  version, format, body]`. A consent is bound over `["rail8 consent v1", kind, key, digest]`.
- With the store's key, a node refuses a body in force that is unbound or whose tag does not verify, whether it names a
  secret or not: it is absent from the node's snapshot, so sends through it fail retryably, `/health` is `DEGRADED`, the
  read shows `binding: fails`, the node's `config_refused` names it, and a WARNING names the key and the key's id once. A
  connection administrator saves it again and activates.
- **Without the store's key.** The secret store then runs on variables only. In `dev` and `ci` nothing is bound, a
  consent is written unbound, and the read shows `binding: none`. In a deployed environment, an activation or a consent
  of a body that sends a secret is 503 `no-key`; a body that sends none is activated unbound. When a key is set for the
  first time, the job below binds every unbound row as it stands, and a WARNING says how many.
- **A change of the store's key** binds the rows again, as the secret store asks at its tick: up to 100 rows a batch,
  `for update skip locked`, so a running activation is never waited on. Each tag is verified under the key it names
  before it is bound under the new one. A row that does not verify loses its tag and stays refused, with a WARNING.
  Binding again changes the key id and the tag only: no revision, no generation, no history row. So the previous key is
  dropped once no row needs it.
- Drafts are not bound. What a draft sends is checked against its bound consent at its test and its activation.
- A connection's stop is not bound. A database writer can start a stopped connection, as they can delete a secret's
  row; neither sends a secret anywhere new.
- A database writer can put back an older bound row, which verifies, as an old secret row does. That is a restore of
  one object, and it is accepted.

## How every node learns of a change

The node reads the configuration through the `Environment` of the design of systems and connections, implemented over
this store inside `rail8-runtime`:

```java
// dev.rail8.runtime.config
final class StoredEnvironment implements Environment {
    private volatile Snapshot held;                 // immutable; current() is this one read and never blocks
    public Snapshot current() { return held; }
    public void onActivation(Consumer<Snapshot> listener);   // once per snapshot taken, in increasing generation
}
interface ConfigStore {                             // Postgres, or one in memory; one contract suite for both
    ObjectView read(Ref ref);                       // the object as the screen shows it
    Page<ObjectView> list(Query query);
    ObjectView write(Actor actor, Ref ref, Change change);   // every write, in the order above
    State state(Held held);                         // what a node lacks against what it holds
    void holds(String node, long epoch, long generation, long stamp, List<Ref> refused);
    Page<HistoryRow> history(Ref ref, OptionalLong after, int limit);
}
record Ref(Kind kind, String key) { enum Kind { CONNECTION, SYSTEM } }
```

- **Wake.** Each node listens on `rail8_config` on the connection it already holds for `LISTEN`. It reads
  `config_store` on each notice, after every `LISTEN` acknowledged (a reconnect may have lost notices), and at each 5 s
  tick of the injected timer. The payload is only a wake-up.
- **Read.** When the generation or the stamp differs from what it holds, it reads in one repeatable-read transaction
  every row's kind, key, version, key id, tag and stop, and the bodies of the rows whose version, key id or tag differ
  from what it holds. Every read compares every row, so a database set back, set back and written again, or with a row
  deleted, is read right. A generation behind its own, or the same generation with another stamp, is a WARNING.
- A row changed in SQL without a change of `config_store` is read at the node's next start, or when its version or tag
  changes; its tag fails then. Until then the node keeps the body it verified.
- **Verify.** It verifies each body's tag and upgrades an older format in memory. A body that fails is left out, as
  above.
- **Swap.** It builds an immutable snapshot (per connection key `Values` or `Mocked` with the connection's version and
  `stopped`; per system key the instance name; the generation), swaps it in, and calls the listeners. A snapshot may
  cover several activations; a listener compares it with the one before. Then it writes `config_generation`,
  `config_stamp`, `config_epoch` and `config_refused` into its row of `nodes`, only while that row carries its own
  epoch. A failed write is a WARNING, and the next tick writes again.
- **At start**, a node reads the whole store before it restores its packages.
- **Postgres away.** A node that cannot read keeps its snapshot. When it has not read `config_store` for 30 s,
  `/health` is `DEGRADED`, naming the time of its last read. A change, a stop included, reaches it at its next read.
- **A message in flight.** Each send reads `current()` once, when it starts. It uses the producer built for that
  connection's version, and the producer is rebuilt only when that connection's version, or a plain secret value it
  holds, changes; a stop or a start rebuilds nothing. A send on the wire ends with the old values. Every later send, a
  retry waiting in the queue included, reads the newest snapshot, so a message that waited because of a wrong address
  goes through once the address is corrected. A message already parked waits for an operator's retry, as any parked
  message does.
- No read of a send goes to Postgres. A secret stays a reference in the snapshot; the producer resolves it as the design
  of systems and connections says.

## The deploy

- The deploy reads the store first, as a write does, so a change activated through another node a moment earlier is
  seen.
- The check of the design of systems and connections then reads `current()`: every connection a flow can reach is set
  and complete for its adapter and directions, or mocked; every system with a used connection has an instance name; a
  connection the node left out counts as not set.
- In its record's transaction, the deploy takes `config_store` `for share` before the names' locks, reads again from
  Postgres the objects it reaches, checks them again, and writes `deployments.config_generation`, the generation it
  read. An activation takes `config_store` `for update`, so a removal waits for the record and sees its uses, and a
  deploy waits for an activation and checks it.
- **What names the state.** The record names the generation. The history's rows of activations, stops and starts carry
  the generation they made, so the history says which version of each object was in force at that generation, for as
  long as it keeps those rows.
- **What the deploy expects.** A deploy still expects only its package's commit (`expect` of the deploy from git). It
  expects nothing of the configuration: activations are per object, and an activation never waits for a deploy's
  author.
- A restore at a node's start and a follow check as the design of systems and connections says.

## The environment file

The design of systems and connections reads the environment from `RAIL8_ENVIRONMENT_FILE` until this store exists. With
the store:
- When the store holds no row, a node started with the variable seeds it. In one transaction under `config_store`
  `for update`, it checks again that no row exists, then activates each connection and each instance name of the file,
  each with its consent. The actor is `environment-file`, the way `local`, and each activation is an audit row. Whoever
  sets a node's environment holds its secrets and the store's key already. Two nodes that start together seed once.
- In a deployed environment without the store's key, a file that sends a secret refuses the node's start, naming the
  first connection that does; in `dev` and `ci` the seed is unbound.
- When the store holds a row, the file is not read, and the node logs once, at INFO, that it is ignored.
- So a fresh quickstart or development node comes up configured, its secrets from variables as today, and a change of
  the file after the first start takes `docker compose down -v`, or a change through the UI or `rail8 config`.

## Export and import

One file per object, YAML, in a folder that is not a package. The convention is `<kind>/<key>.yaml`; the file's `kind`
and `key` decide, and a path that says otherwise is a problem.

```yaml
# connection/acme/erp/rest.yaml
schemaVersion: 1
kind: environment.connection
key: acme/erp/rest
adapter: http
values:
  url: https://erp-test.acme.example/api
  timeoutMs: 5000
  password: secret://erp-pw
secrets:                           # written by the export for the reader; the import decides nothing from it
  erp-pw: { kind: value }
```

```yaml
# connection/globex/erp/rest.yaml: switched to the mock, its values kept
schemaVersion: 1
kind: environment.connection
key: globex/erp/rest
adapter: http
mock: { reply: echo, delay: 20ms }
values:
  url: https://erp.globex.example/api
```

```yaml
# system/acme/erp.yaml
schemaVersion: 1
kind: environment.system
key: acme/erp
instanceName: ACME-ERP-TEST
```

- The kinds are `environment.connection` and `environment.system`, so a file of a configuration is never taken for a
  package's system file. Each kind has its JSON Schema, `urn:rail8:schema:environment.<kind>:<n>`, in `rail8-schemas`;
  unknown keys are refused. A connection's `values` are checked by the adapter's form, not by the schema.
- A file holds no secret's value, no state (the stop, a sequence's current number), no revision, version, date or name
  of who changed it, no draft and no consent. In place of a secret it holds the reference, and under `secrets` the
  secret's kind.
- **Versions of a kind.** A node reads every format of a kind up to its own and upgrades an older one in memory, one
  pure, tested step per format; the same chain reads stored bodies. A newer format is 422 `format`, naming the newest
  it reads. A format is raised only for a change an older reader would misread, and a release reads a new format one
  release before any release exports it, so an export from an environment one release ahead imports into one behind.
  A new kind is a new schema of its own.
- **Versions of a connection's values.** An adapter's connection form is a contract of its own: a field is added only
  with a default that means "as before", and never renamed, removed or narrowed. A change that would is a new adapter.
  So every stored body and every file stays readable, and a destination stays the same when a field is added.
- **The dry run** answers `{expect, objects: [{kind, key, change, fields, overDraft, inForce}], missing, notes,
  unnamed, problems}`:
  - `change` is `created`, `changed` or `unchanged`, against the draft, else the version in force; `overDraft` says a
    draft waits that differs from the file, and the import would replace it; `inForce` says whether the file equals the
    version in force;
  - `missing` is every secret the files name that the target does not hold, with the kinds its field takes;
  - `notes` are keys that no deployed system defines yet;
  - `unnamed` are the target's objects of the kinds and keys the files cover that no file names. The import leaves
    them alone;
  - `problems` are the schema's and the save's refusals, and they refuse the write;
  - `expect` is the SHA-256 over the files as sent and each named object's kind, key and revision in the target.
- **The write** sends the files and `expect` back. When a file or an object changed since the dry run it is 412
  `changed`, answering a new dry run. It writes a draft for each created or changed object in one transaction, the rows
  locked in the order of their keys, and never deletes, discards or activates. It answers each object with its revision
  and `inForce`. Each object gets a history row `import`; the import gets one audit row `config.import`.
- A call holds at most 1,000 files. `rail8 config import` refuses a folder beyond that and says to import it by
  `--kind` or `--prefix`, each call all or none.
- An export is not a backup: it holds what is in force only, no history, no draft, no state, no consent, no value.

## The command `rail8 config`

It sends `RAIL8_TOKEN` to `RAIL8_ADMIN_URL`, as `rail8 deploy` does. Exit 0 when done, 1 for a refusal or problems, 2
for a usage error. It never fills in an expected state or a digest for its caller.

| Command | Calls |
| --- | --- |
| `rail8 config get connection\|system <key>` | the read, printed as JSON |
| `rail8 config export <dir> [--kind k] [--prefix p]` | the export, one file per object |
| `rail8 config import <dir> [--kind k] [--prefix p] --check` | the dry run; prints it and its `expect` |
| `rail8 config import <dir> [--kind k] [--prefix p] --expect <sha256>` | the write of the drafts; prints each object's revision |
| `rail8 config consent connection <key> --expect <revision> --digest <sha256>` | one consent |
| `rail8 config activate connection\|system <key> --expect <revision>` | one activation |
| `rail8 config stop\|start <key>` | stops or starts a connection, expecting the other state |

A pipeline that keeps an environment's configuration in a git repository of its own, with approvals on its host:

```sh
set -euo pipefail
plan=$(rail8 config import config/test --check)
if jq -e 'any(.objects[]; .overDraft)' <<<"$plan" >/dev/null; then
  echo "a draft waits in the target, and the import would replace it" >&2; exit 1
fi
written=$(rail8 config import config/test --expect "$(jq -r .expect <<<"$plan")")
jq -r '.objects[] | select(.inForce | not) | "\(.kind) \(.key) \(.revision)"' <<<"$written" |
while read -r kind key revision; do
  rail8 config activate "$kind" "$key" --expect "$revision" || exit 1
done
```

Each activation stands alone: one refused (`consent-needed`, `incomplete`) leaves the ones before it in force and fails
the job. The next run, once the secret administrator has consented, activates what is still not in force. This is a
loop of activations, not a change list.

## Audit and history

| Change | History | Audit row |
| --- | --- | --- |
| a save, a discard | `save`, `discard`, with the old and new values | none |
| an import | `import` on each object | `config.import`: the objects, their changes, the missing secrets |
| an activation, a removal | `activate`, with the body put in force | `config.activate`: the version, the changed fields, the confirmation |
| a consent, a withdrawal | none | `config.consent`: the sends and the destination fields, or the withdrawal |
| a stop, a start | `stop`, `start` | `config.stop`, `config.start` |
| a test | none | `config.test`: the version or draft tested, the outcome (the step that builds the test) |

- An audit row is written in the transaction of its change: a change whose row cannot be written does not happen (503
  `audit-unavailable`). Its subject is `config/<kind>/<key>`.
- The audit names fields, never their values: a production address stays in the history, which the roles that read the
  configuration read, and out of the audit.
- That a secret changed is shown by the read's `secrets`, from the secret store's list, never by its value.

## A lost or corrupt store

- The backup of Postgres covers every object, draft, consent, history row, the generation, the uses and the audit. It
  does not hold the store's key. Restored with the key, everything verifies. Restored without it, every bound body and
  consent fails to verify: each is absent on every node until a connection administrator saves it again and a secret
  administrator consents; its secrets are unreadable in any case.
- A database set back to an earlier state is read whole by every node, with a WARNING.
- An export is not a backup: it holds no state, no history and no secret.

## A new environment and its secrets

- Values never travel. An export names each secret and its kind; the dry run of the import lists the secrets the target
  lacks; the secret administrator there enters each through `POST /admin/secrets`.
- Consents are never exported: the secret administrator of each installation consents there, to its own destinations.
- Environments hold different secrets anyway: production's password is not test's.

## The rules of the secret store

1. **No secret where the secret administrator did not agree.** A draft that sends a secret through a new field, to a new
   adapter or a new destination needs a consent to its exact digest; TLS verification and trust are destination fields.
   It ships with the first step that stores a connection.
2. **No test past rule 1.** A test needs the same consent as an activation.
3. **Nothing a database writer could change unseen.** With the store's key every body in force and every consent is
   bound, and a node refuses what is unbound or does not verify; a consent states the digest its giver saw.
4. **No secret read back, no kind from elsewhere.** Answers and files hold names; the kind is read from the store at each
   check; `secrets` in a file is never read; no route changes a secret's kind; no key is a secret's name.
5. **No value in a draft, a history or a diff.** A secret field holds only a reference, and a URL no user information;
   going back copies a body into a draft and runs every check again against today's secrets.
6. **An inbound caller's password only as a reference.** A system's credential, when its kind comes, holds its secret in
   a secret field.
7. **No git base, store's key or `rail8.` name as a setting.** No kind holds them; keys refuse a `rail8` segment and
   values a `rail8.` name.
8. **`secret_uses` only through the store's method, never for a draft.** Only an activation writes them.

## Open places

- **A second person's approval of an activation.** Its place is the activation, after the checks and before the
  binding: a route `/-/approve {expect}` that records an approver of a draft's revision, and a check that one is there.
  Not built.
- **A scope of the two roles by partner or by package.** The decision point already takes a scope with each action:
  each route asks it with its object's key. A partner scope matches the keys of that partner's systems, a package scope
  the systems the package defines. `role_assignments.scope` gains values; no table of this design changes.
- **A mock refused per environment.** The built-in mock is allowed in every environment, and `/health` lists every
  mocked connection with the flows that use it. Refusing it in one environment is a node setting, added when asked.

## Moving from SAP

- **SAP PO:** a communication channel's parameters are set in each system's Integration Directory and moved with a
  transport, then corrected by hand in the target. Here the channel is a connection whose values are entered in each
  environment, or exported, reviewed in the dry run and imported as drafts; nothing is corrected after the fact, and a
  value the target lacks is listed before anything is written.
- **SAP Integration Suite:** an integration flow's externalized parameters are configured per tenant, and its security
  material is a tenant's keystore and credentials. Here the parameters of the flow's adapters are a connection's values,
  and the security material is the secret store's; the flow file is the same in every environment.
- **Trading Partner Management:** agreements are exported and imported in bulk with placeholders for identifiers and
  channels. Here an agreement will hold references only, so no placeholders are needed.

## Limits

| What | Limit |
| --- | --- |
| Connections; systems | 20,000; 4,096, as an installation holds. A later kind sets its own |
| A body; all bodies in force together | 64 KiB as canonical JSON, 128 fields; 64 MiB |
| History | 1,000 rows an object, its 100 newest activations always kept, and `RAIL8_AUDIT_MAX_AGE` |
| A request's body; an import | 64 KiB; 1,000 files, 64 KiB a file, 4 MiB a call |
| A page of a list; of a history | 500; 500 |
| Objects a node reports as left out | 100 |
| The time a change takes to reach a node | the notice; at most one tick, 5 s, when a notice is lost |
| A node without a read of the store | `DEGRADED` after 30 s |

## Tests

- One contract suite, `ConfigStoreContract`, runs for the Postgres store (Testcontainers) and an in-memory one. Two
  stores over one database, or over one in-memory backing with a notice bus a test can make drop notices, stand for two
  nodes, each with its own fake clock and timer. A tamper hook on each (SQL, or a method) plays the database writer.
  Across them:
  - every refusal code, and `expect` on every verb: a save over another's save is 412; a stop beside a pending draft
    refuses neither;
  - a change in force seen on the other node after the notice, and after one tick with the notice dropped; `holds`;
  - a database set back; set back and then written past the generation the node holds; a row deleted: each read right,
    with the WARNING where the generation went back;
  - a tampered body, an unbound body with the key, a consent forged or moved to another draft: each refused, never used;
  - binding again after a change of key: a tampered row loses its tag and is not blessed; the tag's encoding tells
    version 1 with format 12 from version 11 with format 2;
  - consent: a new host, port, proxy, trust, TLS switch, field or adapter needs it; a tuning field, a new path, a field
    added at its default and a secret taken away do not; two secret fields swapped need it; a draft changed in SQL after
    the read is refused at the consent, and after the consent at the activation; a withdrawal;
  - activation: complete per direction `deployment_connection_uses` names; a secret of a kind its field does not take;
    a removal refused without `confirm`, and refused again when a deploy added a flow after the refusal;
  - a removal racing a deploy and a deploy racing an activation, both ways, end in a consistent record or a refusal; a
    deploy through another node right after an activation sees it;
  - retention by age and by count, the 100 newest activations kept through 1,000 stops and starts; going back from a
    kept version; a version beyond retention refused;
  - the import: the dry run's lists, 412 after a change of a file or an object, all or none, a format 1 file read once
    format 2 exists; the pipeline above run twice around a consent activates what the first run left;
  - the environment file seeds an empty store once from two nodes started together, unbound without the key in `dev`,
    and is ignored after.
- The send path: a retry waiting in the queue goes out with the corrected values; a send on the wire ends with the old
  ones; a stop fails the next send retryably and a start lets it go; a change of one connection rebuilds no other
  producer.
- A node cut off from Postgres keeps its snapshot and is `DEGRADED` after 30 s of the fake clock.
- The routes: each called as every role, through a session and a token; each refusal of the codes gives its code.
- No answer, log line, history row, audit row or export of any test holds a secret's value, checked by searching every
  captured text for each value written to the secret store.
- A fake clock and an injected timer move every tick. No test sleeps.

## Refusals

| Status | Code | When |
| --- | --- | --- |
| 400 | `bad-request` | a body not of the route's form, or without `expect` |
| 404 | `not-found` | a read of a key no row holds and no deployed system names |
| 409 | `no-draft`, `nothing-to-consent` | a discard or activation without a draft; a consent with no draft, or to one that needs none |
| 409 | `not-set` | a stop or start of a connection with nothing in force |
| 409 | `consent-needed` | an activation or a test of a draft whose consent is missing or does not match; names the sends |
| 409 | `in-use` | a removal whose `confirm` does not list exactly the flows that use it |
| 409 | `adapter-differs` | a connection's adapter other than its deployed system file names |
| 409 | `keys-differ` | an activation or a consent while a live node lacks the key it binds under |
| 409 | `config-full` | beyond a limit |
| 412 | `changed` | `expect` or a digest differs; answers the object, or a new dry run, as it is now |
| 422 | `invalid`, `reserved` | a value against its field, a value in a secret field, a URL with user information; a `rail8.` name |
| 422 | `incomplete` | an activation's problems, each with field, code and direction, never a value |
| 422 | `format` | an import file of an unknown kind or a newer format |
| 503 | `no-key` | in a deployed environment without the store's key, an activation or consent of a body that sends a secret |
| 503 | `audit-unavailable` | an audit row not written |

## What depends on the design of systems and connections

That design is agreed separately and may still move. This one relies on, and asks of it:
- the keys, and `Environment` with its snapshot; `onActivation` called once per snapshot, not once per activation;
- each connection's state carrying the connection's version, and a producer kept per connection and version, rebuilt
  only when that connection changes, not at every new generation;
- the connection form's format 2, two marks on its fields, `.tuning()` and `.origin()`, and the rule that a form only
  adds fields with a default that means "as before", checked by the component contract suite against the form recorded
  in the adapter's tests;
- `deployment_connection_uses` with its directions, and its rule that a connection it names cannot be removed without a
  confirmation;
- the mock's fields, and `GET /admin/systems` reading each connection's state from here;
- `secret://` references resolved by the producer, a plain value rebuilding the producer when it changes;
- the environment file, which becomes a seed of an empty store.

## Steps

Each step is one issue, built and merged on its own.

1. **The marks on the connection form.** `class:C`, after the adapter's contract of systems and connections. `.tuning()`
   and `.origin()` on a form's fields in `rail8-api`; the destination of a connection's values and the digest of what it
   sends; the rule that a form only adds fields with a default, checked by the component contract suite against a
   recorded form; `http` and `sftp` marked; a URL with user information refused. No change to the database.
2. **The store.** `class:C`, after step 1, the steps of systems and connections up to systems and flows by name, and
   the secret store's store and its credentials in adapters.
   - The migration; the objects `connection` and `system`; drafts, activation, removal, going back; the three numbers.
   - The consent on the object; the binding and binding again; the rule without the store's key.
   - The read path, the nodes' report, `DEGRADED` without a read, the deploy's check and record.
   - Stop and start; the routes with their actions; the roles connection administrator and partner administrator, and
     who may hold each role; `secret.list` for them; the audit and the history with its retention.
   - The environment file as the seed of an empty store; `GET /admin/systems` reading from it.
   - A mocked body is accepted once the built-in mock of systems and connections is in, and `allowedHosts` counts as a
     destination once a whole address from the message is.
   - Changes the database; the quickstart's text says how a change of its environment file takes effect.
3. **Export, import and `rail8 config`.** `class:C`, after 2: a file format that is a contract, with its JSON Schemas.
   The format with its chain of upgrades, the dry run and the write, and the command. No change to the database.
4. **Test connection.** `class:C`, after 2: a new contract of the adapter. A `probe` on `Component` with a default that
   answers it cannot test; the probes of `http` and `sftp` and their mocks, checked by the component contract suite;
   `/-/test` of the version in force and `/-/test-draft` of a draft, with the consent of rule 2 and the audit row.
5. **Editing on the Systems screen under Configure.** `class:B`, after 2, the Secrets screen and the Systems screen of
   systems and connections. The one form of a connection with its secrets; the draft beside the version in force with
   its changes, problems and the consent it needs; activate, discard, go back; the history; which nodes hold it;
   instance names; the secret administrator's list of drafts that wait for consent, with consent and withdrawal. Test
   connection once step 4 is in.
6. **Connections in Monitor.** `class:B`, after 2 and 4. Each connection's state, its last success and last error,
   stop and start, test. Each node keeps, per connection, the time of its last success and its last error (the fault's
   kind and its scrubbed text) and writes them to a table of its own at most every 5 s; the view reads the live nodes'
   rows. Changes the database.
7. **Partner profiles and identifiers.** `class:C`, with the design of partners first. It adds its kinds to this store.
