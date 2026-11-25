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
- A secret goes to a new address, proxy or trust only with the secret administrator's consent.
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
| `connection` | a connection key | `{adapter, values, mock?}` | mocked; or `values` pass the adapter's connection form for the directions it is used in, and every secret it names is set, of a kind its field takes |
| `system` | a system key | `{instanceName}`, 1 to 128 printable characters | always, once its shape is right |

- `values` are the fields of the adapter's connection form, the format 2 description at `GET /admin/components`. A
  secret field holds `secret://<name>` and nothing else; no other field holds `secret://`. A name under `rail8.` is
  refused.
- `mock` is the platform's built-in mock: `{reply: echo | empty, delay?, fail?: retryable | invalid}`, the delay at most
  60 s. While it is set the connection is mocked, and `values` are kept as they were, unchecked, so switching the mock
  off brings them back. A mocked connection sends no secret.
- A **removal** is a draft too: `{removed: true}`, activated like any other change.
- Each stored body carries its kind's format number, as a file does, and is read through the same chain of upgrades.
- Whether a connection is **stopped** is not part of its body. It is never drafted and never exported. An operator or
  a connection administrator stops and starts it at once.
- A connection may be set before its system is deployed: configuring before the first deploy is the normal order. Its
  adapter must be the one its deployed system file names, once one does.
- Later kinds join the same tables, routes, history and format, each with its own body and checks: a system's
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

-- One row per object: its version in force, its draft, and a connection's state. A row is never deleted while it has
-- a version in force or a draft.
create table config_objects (
    kind     text   not null check (kind in ('connection', 'system')),
    key      text   not null check (key ~ '^(?!rail8(/|$))[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63}){0,2}$'),
    revision bigint not null check (revision >= 0),
    -- the version in force; null before the first activation and after a removal
    version      int    not null default 0,         -- 0: never activated
    format       smallint,                          -- the kind's format of the body
    body         jsonb,
    key_id       bytea  check (length(key_id) = 8), -- the binding (below); null: not bound
    tag          bytea  check (length(tag) = 32),
    activated_at timestamptz, activated_by text,
    changed_generation bigint not null default 0,  -- the generation of its last activation, stop or start
    -- the draft; null when there is none
    draft_format smallint, draft jsonb, draft_at timestamptz, draft_by text,
    -- a connection's state
    stopped_at   timestamptz, stopped_by text,     -- null: running
    primary key (kind, key),
    check ((body is null) = (format is null)),
    check ((key_id is null) = (tag is null)),
    check ((draft is null) = (draft_format is null)),
    check (kind = 'connection' or stopped_at is null)
);
create index config_objects_changed on config_objects (changed_generation);

-- The secret administrator's consent that a secret may go to a destination.
create table config_pairings (
    secret       text  not null check (secret ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    adapter      text  not null,
    destination  jsonb not null,                   -- the destination fields' values; never a secret
    key_id       bytea not null check (length(key_id) = 8),
    tag          bytea not null check (length(tag) = 32),
    consented_at timestamptz not null, consented_by text not null,
    primary key (secret, adapter, destination)
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
    version    int,                                -- an activation: the version it made
    format     smallint, body jsonb,               -- an activation: the body it put in force
    diff       jsonb not null                      -- [{field, from, to}]; a secret field by its reference only
);
create index config_history_object on config_history (kind, key, id);

alter table nodes add column config_generation bigint, add column config_stamp bigint, add column config_epoch bigint;
alter table deployments add column config_generation bigint;   -- null: written before the store
```

- The migration inserts the one row of `config_store`: generation 0 and a random stamp.
- **The versions kept** are the activation rows of the history. Going back copies one of them into a draft.
- **Retention.** The node's retention job deletes history rows older than `RAIL8_AUDIT_MAX_AGE` (P30D to P3660D, P365D by
  default), in its batches. A write that would give an object more than 1,000 history rows deletes its oldest. The
  version in force and the draft are in `config_objects` and are never deleted by retention, so an object can always be
  read and changed. A version older than its history row cannot be brought back.
- A row with no version in force, no draft and no history row left is deleted by the same job.
- `nodes.config_generation`, `config_stamp` and `config_epoch` are what each node holds. Nothing decides by them.

## Roles and actions

One list of roles. Each says who may hold it: a person, a technical account, or both.

| Role | Id | Held by | Holds |
| --- | --- | --- | --- |
| Administrator | `administrator` | a person | users, roles, technical accounts and their tokens, sessions, the audit |
| Developer | `developer` | both | deploys, the deployed configuration, the modules' services |
| Operator | `operator` | both | monitoring, retry, cancel, release, discard; stops and starts a connection |
| Message bodies | `message-bodies` | both | a message's body after each step |
| Secret administrator | `secret-administrator` | both | the secret store; consents that a secret may go to a destination |
| Connection administrator | `connection-administrator` | both | systems in the environment, the values of connections, credentials, instance names, technical identifiers |
| Partner administrator | `partner-administrator` | both | partner profiles, business identifiers, agreements, API products, subscriptions, number sequences |
| Message sender | `message-sender` | both | sends a test message to a flow (the design of who may send to a flow) |

| Action | Routes | Granted by |
| --- | --- | --- |
| `config.read` | every `GET /admin/config…` | developer, operator, connection-administrator, partner-administrator, secret-administrator |
| `config.connections` | save, discard and activate of connections and systems; a test of a draft | connection-administrator |
| `config.run` | stop, start, and a test of the version in force | operator, connection-administrator |
| `config.consent` | consent | secret-administrator |
| `config.import` | the import's door; each file is then checked by its kind's write action | connection-administrator, partner-administrator |

- `secret.list` gains the connection administrator and the partner administrator, so a form picks a secret by name and
  kind. They gain no other action of the secret store.
- The partner administrator holds `config.read`, `config.import` and `secret.list` until its first kind comes. That kind
  brings `config.partners`, its write action, granted by the partner administrator alone.
- A token may do every action here: a pipeline with a technical account imports and activates. A consent by a token is a
  secret administrator's act like any other, and the audit names its account.

## Admin routes

A key holds `/`, so a verb follows the key after the segment `-`, which no key segment can be. Each verb is mounted as a
route of its own with its action, so the action is checked before the body is read. A body is JSON of at most 64 KiB,
an import's at most 4 MiB.

| Route | Action | Body | Does |
| --- | --- | --- | --- |
| `GET /admin/config/connections?prefix&needs&after&limit` | `config.read` | | the list: key, adapter, state, version, whether a draft waits, whether it needs consent, the nodes behind; `needs=consent` or `needs=activation` narrows it |
| `GET /admin/config/connections/{key}` | `config.read` | | one connection, as the screen shows it (below) |
| `POST /admin/config/connections/{key}/-/save` | `config.connections` | `{expect, values, mock?}`, `{expect, removed: true}` or `{expect, fromVersion}` | writes the draft |
| `POST /admin/config/connections/{key}/-/discard` | `config.connections` | `{expect}` | drops the draft |
| `POST /admin/config/connections/{key}/-/consent` | `config.consent` | `{expect}` | consents to the draft's new pairings |
| `POST /admin/config/connections/{key}/-/activate` | `config.connections` | `{expect, confirm?}` | puts the draft in force |
| `POST /admin/config/connections/{key}/-/stop`, `/-/start` | `config.run` | `{expect: "running" \| "stopped"}` | stops or starts it at once |
| `GET /admin/config/connections/{key}/-/history?after&limit` | `config.read` | | its history, newest first, 50 a page, at most 500 |
| `GET /admin/config/systems?prefix&after&limit`, `GET /admin/config/systems/{key}` | `config.read` | | the systems' instance names |
| `POST /admin/config/systems/{key}/-/save`, `/-/discard`, `/-/activate`; `GET …/-/history` | as for connections | `{expect, instanceName}` | the same, without consent, stop and start |
| `GET /admin/config/pairings?secret&after&limit` | `config.read` | | the consented pairings, with who consented and when |
| `GET /admin/config/export?kind&prefix` | `config.read` | | `{files: [{path, text}]}`: every version in force, in the newest format |
| `POST /admin/config/import?check=true` | `config.import` | `{files}` | the dry run |
| `POST /admin/config/import` | `config.import` | `{files, expect}` | writes the drafts, all or none |

A key that no row holds and no deployed system names is 404 `not-found`. A key that a deployed system names and no row
holds answers an object of revision 0 with nothing in force, so the first save states `expect: 0`.

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
  "problems": [{field, code, direction}], "needsConsent": [{secret, destination}]}`. `problems` and `needsConsent` are
  computed at the read, so the form shows what an activation would refuse before anyone presses it.
- `binding` is `ok`, `none` (an object that names no secret, on an installation without the store's key) or `fails`.
- A node `holds` the version in force when its lease holds, its `config_epoch` is its epoch, and its
  `config_generation` is at least the object's `changed_generation`.
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
   answered.

**Save** checks the shape:
- the adapter is known to the node; it is the adapter the deployed system file names, when one does (409
  `adapter-differs`);
- each value is a field of the connection form, of its field's type;
- a secret field holds only `secret://<name>`, and no other field holds `secret://` (422 `invalid`, naming the field,
  never echoing a value); no `rail8.` name (422 `reserved`).

A draft may be incomplete. `fromVersion` copies that version's body from the history into the draft. A save equal to the
version in force drops the draft. A save over a draft replaces it.

**Activate** checks against the state of its own transaction:
- **complete**: the values pass the connection form for each direction `deployment_connection_uses` names the key in; a
  connection that no deployed flow uses passes for at least one direction its adapter connects; every secret it names is
  set in the store, of a kind its field takes; a system keeps an instance name while a connection of it is used. Else
  422 `incomplete`, one line per field and direction, never a value;
- **consented**: no pairing of the draft lacks consent (409 `consent-needed`, naming each);
- **a removal** of a connection that `deployment_connection_uses` names, or of a system with such a connection, needs
  `confirm=<key>` (409 `in-use`, naming the flows). Once confirmed, every send through it fails retryably and a polling
  sender is `DEGRADED`, as the design of systems and connections says;
- **the key**: every live node holds the key it binds under (409 `keys-differ`, the secret store's rule).

It then binds the body, writes `version + 1` and `changed_generation`, clears the draft, and replaces the object's rows
of `secret_uses` through the store's one method: its secret names, none for a mocked or removed connection.

A **stop** of a connection with nothing in force is 409 `not-set`. A stop holds for every send that starts after it;
a send already started ends.

## Where a secret goes: consent

The secret administrator decides where each secret may go. A connection administrator decides everything else.

- **Destination fields.** A field of an adapter's connection form counts as a destination unless the adapter marks it
  otherwise:
  - `.tuning()`: a field that never changes where bytes go, such as a time limit or a pool's size; it does not count;
  - `.origin()`: a URL counted by its scheme, host and port, so a new path needs no consent.
  Every other field that is not a secret counts whole: an address, a host, a port, a proxy, a trusted certificate, a
  host key, a switch of TLS verification. The platform's `allowedHosts` counts whole. An adapter that marks nothing asks
  for consent at every change; that is safe, and it is the adapter's to improve.
- **Pairings.** A connection's pairings are each secret it names with its adapter and the values of its destination
  fields. A pairing needs consent unless `config_pairings` holds it with a tag that verifies. A mocked or removed
  connection, and a system, have none.
- **Consent.** `/-/consent {expect}` writes the draft's pairings that lack consent, each bound, and one audit row
  `config.consent` naming them. With none to write it is 409 `nothing-to-consent`. A later save withdraws nothing: the
  consent belongs to the pairing, so going back to an address consented before needs nothing new.
- **The inbox.** `GET /admin/config/connections?needs=consent` is the secret administrator's list of drafts that wait.
- **A test** sends secrets as an activation does, and needs the same consent (the step that builds the test).
- The rule holds from the first step: a secret never goes to a new address, proxy or trust, nor with verification off,
  without consent.
- A user who holds both roles consents with one call and activates with another, so the act is always visible as a
  consent in the audit.
- A pairing is kept by the secret's name. A secret deleted and created again under the same name keeps its pairings;
  `GET /admin/config/pairings?secret=<name>` lists them for the secret administrator who creates it.

## The binding

- Each body in force that names a secret is bound: an HMAC-SHA256 tag through the secret store's binding (a subkey of
  the store's key, HKDF info `rail8 settings v1 bind`, named by the key's id) over the bytes `rail8 config v1`, the
  kind, the key, the version, the format and the body as RFC 8785 JSON. On an installation with the store's key, every
  body in force is bound, whether it names a secret or not.
- Each pairing is bound over `rail8 pairing v1`, the secret's name, the adapter and the destination as RFC 8785 JSON.
- A node refuses a body in force whose tag does not verify, and one that names a secret without a tag: it is absent
  from the node's snapshot, so sends through it fail retryably, `/health` is `DEGRADED`, the read shows `binding:
  fails`, and a WARNING names the key and the key's id once. A connection administrator saves it again and activates.
- A change of the store's key binds the rows again, as the secret store asks at its tick: up to 100 rows a batch,
  `for update skip locked`, so a running activation is never waited on. Binding again changes the key id and the tag
  only: no revision, no generation, no history row.
- Drafts are not bound. What a draft would send is checked at its consent, its test and its activation against the
  bound pairings, so a draft changed in SQL sends nothing unconsented.
- A connection's stop is not bound. A database writer can start a stopped connection, as they can delete a secret's
  row; neither sends a secret anywhere new.

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
    Changes since(long generation, long stamp);     // what a node lacks
    void holds(String node, long epoch, long generation, long stamp);
    Page<HistoryRow> history(Ref ref, OptionalLong after, int limit);
}
record Ref(Kind kind, String key) { enum Kind { CONNECTION, SYSTEM } }
```

- **Wake.** Each node listens on `rail8_config` on the connection it already holds for `LISTEN`. It reads
  `config_store` on each notice, after every `LISTEN` acknowledged (a reconnect may have lost notices), and at each 5 s
  tick of the injected timer. The payload is only a wake-up.
- **Read.** When the generation or the stamp differs from what it holds, it reads in one repeatable-read transaction
  the rows whose `changed_generation` is above its own. A generation behind its own, or the same generation with another
  stamp, means the database went back to an earlier state: the node reads every row, and logs a WARNING.
- **Verify.** It verifies each row's tag and upgrades an older format in memory. A row that fails is left out, as above.
- **Swap.** It builds an immutable snapshot (per connection key `Values` or `Mocked`, each with `stopped`; per system
  key the instance name; the generation), swaps it in, and calls the listeners. A snapshot may cover several
  activations; a listener compares it with the one before. Then it writes `config_generation`, `config_stamp` and
  `config_epoch` into its row of `nodes`, only while that row carries its own epoch. A failed write is a WARNING, and the
  next tick writes again.
- **At start**, a node reads the whole store before it restores its packages.
- **A message in flight.** Each send reads `current()` once, when it starts, and uses the producer of that snapshot's
  generation until it ends. A send on the wire ends with the old values. Every later send, a retry waiting in the queue
  included, reads the newest snapshot, so a message that waited because of a wrong address goes through once the
  address is corrected. A message already parked waits for an operator's retry, as any parked message does.
- No read of a send goes to Postgres. A secret stays a reference in the snapshot, read through its handle at each use.

## The deploy

- The check of the design of systems and connections reads `current()`: every connection a flow can reach is set and
  complete for its adapter and directions, or mocked; every system with a used connection has an instance name; a
  connection whose binding fails counts as not set.
- In its record's transaction, the deploy takes `config_store` `for share` before the names' locks, reads again from
  Postgres the objects it reaches, checks them again, and writes `deployments.config_generation`, the generation it
  read. An activation takes `config_store` `for update`, so a removal waits for the record and sees its uses, and a
  deploy waits for an activation and checks it.
- **What names the state.** The record names the generation. That generation, with the history, says which version of
  each object the deploy was checked against.
- **What the deploy expects.** A deploy still expects only its package's commit (`expect` of the deploy from git). It
  expects nothing of the configuration: activations are per object, and an activation never waits for a deploy's
  author.
- A restore at a node's start and a follow check as the design of systems and connections says.

## The environment file

The design of systems and connections reads the environment from `RAIL8_ENVIRONMENT_FILE` until this store exists. With
the store:
- When the store holds no object, a node started with the variable seeds it: in one transaction it activates each
  connection and each instance name of the file, and consents to their pairings. The actor is `environment-file`, the
  way `local`, and each activation is an audit row. Whoever sets a node's environment holds the store's key already.
- When the store holds an object, the file is not read, and the node logs a WARNING naming the variable.
- So a fresh quickstart or development node comes up configured, and a change of the file after the first start takes
  `docker compose down -v`, or a change through the UI or `rail8 config`.

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
# connection/globex/erp/rest.yaml: switched to the mock
schemaVersion: 1
kind: environment.connection
key: globex/erp/rest
adapter: http
mock: { reply: echo, delay: 20ms }
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
  of who changed it, no draft, no consent. In place of a secret it holds the reference; under `secrets` the kind and,
  for a key pair, its public facts (algorithm, the leaf's subject and end, the fingerprint of its public key).
- **Versions.** A node reads every format of a kind up to its own and upgrades an older one in memory, one pure, tested
  step per format; the same chain reads stored bodies. A newer format is 422 `format`, naming the newest it reads. The
  export writes the newest. A format is raised only for a change an older reader would misread; a new kind is a new
  schema of its own.
- **The dry run** answers `{expect, objects: [{kind, key, change, fields, overDraft, inForce}], missing, notes,
  problems}`:
  - `change` is `created`, `changed` or `unchanged`, against the draft, else the version in force; `overDraft` says a
    draft waits that the import would replace; `inForce` says whether the file equals the version in force;
  - `missing` is every secret the files name that the target does not hold, with the kinds its field takes;
  - `notes` are keys that no deployed system defines yet;
  - `problems` are the schema's and the save's refusals, and they refuse the write;
  - `expect` is the SHA-256 over each named object's kind, key and revision in the target.
- **The write** sends `expect` back. When an object moved since the dry run it is 412 `changed`, answering a new dry
  run. It writes a draft for each created or changed object in one transaction, the rows locked in the order of their
  keys, and never deletes, discards or activates. Each object gets a history row `import`; the import gets one audit
  row `config.import`.
- An export is not a backup: it holds what is in force only, no history, no draft, no state, no consent, no value.

## The command `rail8 config`

It sends `RAIL8_TOKEN` to `RAIL8_ADMIN_URL`, as `rail8 deploy` does. Exit 0 when done, 1 for a refusal or problems, 2
for a usage error. It never fills in an expected state for its caller.

| Command | Calls |
| --- | --- |
| `rail8 config get connection\|system <key>` | the read, printed as JSON |
| `rail8 config export <dir> [--kind k] [--prefix p]` | the export, one file per object |
| `rail8 config import <dir> --check` | the dry run; prints it and its `expect` |
| `rail8 config import <dir> --expect <sha256>` | the write of the drafts |
| `rail8 config consent connection <key> --expect <revision>` | one consent |
| `rail8 config activate connection\|system <key> --expect <revision>` | one activation |
| `rail8 config stop\|start <key>` | stops or starts a connection, expecting the other state |

A pipeline that keeps an environment's configuration in a git repository of its own, with approvals on its host:

```sh
plan=$(rail8 config import config/test --check)
rail8 config import config/test --expect "$(jq -r .expect <<<"$plan")"
jq -r '.objects[] | select(.change != "unchanged") | "\(.kind) \(.key)"' <<<"$plan" | while read -r kind key; do
  revision=$(rail8 config get "$kind" "$key" | jq -r .revision)
  rail8 config activate "$kind" "$key" --expect "$revision"
done
```

Each activation stands alone: one refused (`consent-needed`, `incomplete`) leaves the ones before it in force and fails
the job. This is a loop of activations, not a change list.

## Audit and history

| Change | History | Audit row |
| --- | --- | --- |
| a save, a discard | `save`, `discard`, with the old and new values | none |
| an import | `import` on each object | `config.import`: the objects, their changes, the missing secrets |
| an activation, a removal | `activate`, with the body put in force | `config.activate`: the version, the changed fields, the confirmation |
| a consent | none | `config.consent`: each pairing |
| a stop, a start | `stop`, `start` | `config.stop`, `config.start` |
| a test | none | `config.test`: the version or draft tested, the outcome (the step that builds the test) |

- An audit row is written in the transaction of its change: a change whose row cannot be written does not happen (503
  `audit-unavailable`). Its subject is `config/<kind>/<key>`.
- The audit names fields, never their values: a production address stays in the history, which the roles that read the
  configuration read, and out of the audit.
- The history merges in the secret store's audit rows of the names a version held (`secret.overwrite`,
  `secret.delete`), so it shows that a secret changed, never its value.

## A lost or corrupt store

- The backup of Postgres covers every object, draft, pairing, history row, the generation, the uses and the audit. It
  does not hold the store's key. Restored with the key, everything verifies. Restored without it, every body that names
  a secret, and every pairing, fails to verify: each is absent on every node until a connection administrator saves it
  again and a secret administrator consents; its secrets are unreadable in any case.
- A database set back to an earlier state is read whole by every node, with a WARNING.
- An export is not a backup: it holds no state, no history and no secret.

## A new environment and its secrets

- Values never travel. An export names each secret and its kind; the dry run of the import lists the secrets the target
  lacks; the secret administrator there enters each through `POST /admin/secrets`.
- Pairings are never exported: the secret administrator of each installation consents there.
- Environments hold different secrets anyway: production's password is not test's.

## The rules of the secret store

1. **No secret where the secret administrator did not agree.** A pairing with a new destination needs a consent; TLS
   verification and trust are destination fields. It ships with the first step.
2. **No test past rule 1.** A test needs the same consent as an activation.
3. **Nothing a database writer could change unseen.** Every body in force that names a secret, and every pairing, is
   bound; a node refuses what does not verify.
4. **No secret read back, no kind from elsewhere.** Answers and files hold names; the kind is read from the store at each
   check; `secrets` in a file is never read; no route changes a secret's kind; no key is a secret's name.
5. **No value in a draft, a history or a diff.** A secret field holds only a reference; going back copies a body into a
   draft and runs every check again against today's secrets.
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
| Objects | 20,000 |
| A body; all bodies in force together | 64 KiB as canonical JSON, 128 fields; 64 MiB |
| Pairings | 10,000 |
| History | 1,000 rows an object, and `RAIL8_AUDIT_MAX_AGE` |
| A request's body; an import | 64 KiB; 1,000 files, 64 KiB a file, 4 MiB a call |
| A page of a list; of a history | 500; 500 |
| The time a change takes to reach a node | the notice; at most one tick, 5 s, when a notice is lost |

## Tests

- One contract suite, `ConfigStoreContract`, runs for the Postgres store (Testcontainers) and an in-memory one. Two
  stores over one database, or over one in-memory backing with a notice bus a test can make drop notices, stand for two
  nodes, each with its own fake clock and timer. A tamper hook on each (SQL, or a method) plays the database writer.
  Across them:
  - every refusal code, and `expect` on every verb: a save over another's save is 412; a stop beside a pending draft
    refuses neither;
  - a change in force seen on the other node after the notice, and after one tick with the notice dropped; `holds`;
  - a database set back, and set back then written once, read whole with the WARNING;
  - a tampered body, an unbound body that names a secret, a forged pairing: each refused, never used;
  - consent: a new host, port, proxy, trust or TLS switch needs it; a tuning field and a new path do not; an address
    consented before needs nothing; a draft changed in SQL after its consent is refused at its activation;
  - activation: complete per direction `deployment_connection_uses` names; a secret of a kind its field does not take;
    a removal in use refused without its confirmation;
  - a removal racing a deploy and a deploy racing an activation, both ways, end in a consistent record or a refusal;
  - retention by age and by count; going back from a kept version; a version beyond retention refused;
  - the import: the dry run's lists, 412 after a change, all or none, a format 1 file read once format 2 exists;
  - the environment file seeds an empty store once, and is ignored with a WARNING after.
- The send path: a retry waiting in the queue goes out with the corrected values; a send on the wire ends with the old
  ones; a stop fails the next send retryably and a start lets it go.
- The routes: each called as every role, through a session and a token; each refusal of the codes gives its code.
- No answer, log line, history row, audit row or export of any test holds a secret's value, checked by searching every
  captured text for each value written to the secret store.
- A fake clock and an injected timer move every tick. No test sleeps.

## Refusals

| Status | Code | When |
| --- | --- | --- |
| 400 | `bad-request` | a body not of the route's form, or without `expect` |
| 404 | `not-found` | a key no row holds and no deployed system names |
| 409 | `no-draft`, `nothing-to-consent` | a discard, consent or activation without a draft; a consent with no new pairing |
| 409 | `not-set` | a stop or start of a connection with nothing in force |
| 409 | `consent-needed` | an activation or a test while a pairing lacks consent; names each |
| 409 | `in-use` | a removal that `deployment_connection_uses` names, without `confirm=<key>` |
| 409 | `adapter-differs` | a connection's adapter other than its deployed system file names |
| 409 | `keys-differ` | an activation or a consent while a live node lacks the key it binds under |
| 409 | `config-full` | beyond a limit |
| 412 | `changed` | `expect` differs; answers the object, or a new dry run, as it is now |
| 422 | `invalid`, `reserved` | a value against its field, a value in a secret field; a `rail8.` name |
| 422 | `incomplete` | an activation's problems, each with field, code and direction, never a value |
| 422 | `format` | an import file of an unknown kind or a newer format |
| 503 | `no-key` | an activation of a body that names a secret, or a consent, on a node without the store's key |
| 503 | `audit-unavailable` | an audit row not written |

## What depends on the design of systems and connections

That design is agreed separately and may still move. This one relies on:
- the keys, `Environment` and its snapshot, with `onActivation` called once per snapshot, not once per activation;
- the connection form's format 2, and two marks on its fields, `.tuning()` and `.origin()`, which this design adds;
- `deployment_connection_uses` with its directions, and its rule that a connection it names cannot be removed without a
  confirmation;
- the mock's fields, and `GET /admin/systems` reading each connection's state from here;
- the environment file, which becomes a seed of an empty store.

## Steps

Each step is one issue, built and merged on its own. Step 1 comes after the secret store's first step and the first
two steps of systems and connections.

1. **The store.** `class:C`.
   - The migration; the objects `connection` and `system`; drafts, activation, removal, going back; the three numbers.
   - Consent and pairings; the marks `.tuning()` and `.origin()` on the connection form; the binding and binding again.
   - The read path, the nodes' report, the deploy's check and record.
   - Stop and start; the routes with their actions; the roles connection administrator and partner administrator, and
     who may hold each role; `secret.list` for them; the audit and the history with its retention.
   - The environment file as the seed of an empty store; `GET /admin/systems` reading from it.
   - Changes the database; the quickstart's text says how a change of its environment file takes effect.
2. **Export, import and `rail8 config`.** `class:B`, after 1. The format with its schemas and its chain of upgrades, the
   dry run and the write, and the command. No change to the database.
3. **Test connection.** `class:C`, after 1: a new contract of the adapter. A `probe` on `Component` with a default that
   answers it cannot test; the probes of `http` and `sftp` and their mocks, checked by the component contract suite;
   `/-/test` of the version in force and `/-/test-draft` of a draft, with the consent of rule 2 and the audit row.
4. **Editing on the Systems screen under Configure.** `class:B`, after 1, the Secrets screen and the Systems screen of
   systems and connections. The one form of a connection with its secrets; the draft beside the version in force with
   its changes, problems and needed consent; activate, discard, go back; the history; which nodes hold it; instance
   names; the secret administrator's list of drafts that wait for consent. Test connection once step 3 is in.
5. **Connections in Monitor.** `class:B`, after 1 and 3. Each connection's state, its last success and last error,
   stop and start, test. Each node keeps, per connection, the time of its last success and its last error (the fault's
   kind and its scrubbed text) and writes them to a table of its own at most every 5 s; the view reads the live nodes'
   rows. Changes the database.
6. **Partner profiles and identifiers.** `class:C`, with the design of partners first. It adds its kinds to this store.
