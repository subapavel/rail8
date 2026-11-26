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
a system and its connections, and each environment holds the values. No node reads them from a file: this store is
built first, and the systems and flows by name of that design read it.

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
- A secret goes to a server that no verified version of its connection sent it to only with the secret administrator's
  consent, given to exactly those pairs of secret and server. A user who holds both roles consents by the same act.
- Everything the UI does goes through the admin API, and the UI uses nothing else.
- Export and import use one documented, versioned format, one file per object. A file holds no secret's value and no
  state. An import is first a dry run, then writes drafts. It adds and changes; it never deletes, never replaces
  another draft and never activates.
- Only `rail8-runtime` reads or writes these tables. Clock, timers, ids and random numbers are injected.
- Every refusal carries a stable code and an English text. The UI translates by the code.

## Objects

An object is a **kind** and a **key**. The keys are those of the design of systems and connections: segments
`^[a-z][a-z0-9-]{0,63}$`, never `rail8`, `subscribers` or `by-agreement` first; a system key is `eshop` or `acme/erp`, a
connection key `eshop/rest` or `acme/erp/rest`.

| Kind | Key | Body | Complete when |
| --- | --- | --- | --- |
| `connection` | a connection key, two or three segments | `{adapter, values, mock?}` | mocked, in an environment that allows mocks; or `values` pass the adapter's connection form for at least one direction its adapter connects, and every secret it names is set, of a kind its field takes. A direction a use needs that stays incomplete asks the one confirmation below |
| `system` | a system key, one or two segments | `{instanceName}`, 1 to 128 printable characters | always, once its shape is right |

- An instance name of a system in use, one whose connection a deployed flow uses as `deployment_connection_uses` names
  it, can be changed but not removed: its removal is 409 `name-in-use`, naming the flows, with no confirmation.

- `values` are the fields of the adapter's connection form, the format 2 description at `GET /admin/components`. A
  secret field holds `secret://<name>` and nothing else; no other field holds `secret://`. A name under `rail8.` is
  refused. A URL holds no user information, and a credential is never part of a URL: an adapter that sends a token in a
  query takes it from a secret field.
- `mock` is the platform's built-in mock: `{reply: echo | empty, delay?, fail?: retryable | invalid}`, the delay at most
  60 s. While it is set the connection is mocked, and `values` are kept as they were, so switching the mock off brings
  them back. Kept values are checked for their shape at every save, import and going back, as any values are; only
  completeness and whether their secrets are set wait until the mock is switched off. A mocked connection sends no
  secret.
- **Whether the environment allows mocks** is one switch of the environment, `config_store.mocks_allowed`, off unless
  set, since a mocked send completes a message without reaching the partner and Rail8 has no replay. A connection
  administrator changes it with `POST /admin/config/-/mocks`, which writes an audit row `config.mocks` and a new
  generation. It is never exported, so a file from test never makes production mock. Turned off, a node leaves every
  mocked connection out of its snapshot, so their sends fail as through a connection not set, naming the connection and
  that mocks are not allowed, and are retried by their flow's retry: turning it off while a used connection is mocked
  is a change that leaves that connection unusable, and needs the one confirmation (below). Turning it on needs none.
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

## Numbers and tokens

| What | Of | Changes at | Used for |
| --- | --- | --- | --- |
| `revision` | one object | a save, a discard, an activation | the history; people |
| `version` | one object | an activation: 1, 2, 3 … | going back, as `fromVersion`; people |
| `activation` | one object | an activation: a new random 64-bit id, never reused | the connection's version in a node's snapshot, so a producer is never taken for other values; the binding |
| `incarnation` | one row | the row's creation: a new random 64-bit id | the tokens and the binding, so a row deleted and created again never matches an old request |
| `etag` | one object | any change of its row's revision, draft or consent | `expect` of every save, discard, consent, withdrawal and activation |
| `run` | one connection | a stop, a start, a removal | `expect` of a stop and a start |
| `generation` | the environment | an activation, a stop, a start, the switch of mocks, a batch of binding again | the deploy's record; each node's report |

- `etag` is the SHA-256 of the row's incarnation, revision, draft and consent, as opaque text, and for a key no row
  holds the SHA-256 of the same array with no incarnation, revision 0, and no draft or consent; `run` is its
  incarnation and a counter. Neither is secret or bound: they only say what the caller saw. A write whose `expect`
  differs is 412 `changed`, answering the object as it is now.
- A stop or a start states `run`, not `etag`. So an operator who stops a connection never refuses an administrator's
  pending activation, nor the other way round, and a stop delayed past a colleague's stop and start is refused.
- A draft never raises the generation and never wakes a node.

## Storage

```sql
-- The environment: its counter, raised with a new random stamp by every change a node must read.
create table config_store (
    one           boolean primary key default true check (one),
    generation    bigint  not null check (generation >= 0),
    stamp         bigint  not null,
    mocks_allowed boolean not null default false
);

-- One row per object: its version in force, its draft with the consent to it, and a connection's state.
create table config_objects (
    kind        text   not null check (kind in ('connection', 'system')),
    key         text   not null,
    incarnation bigint not null,
    revision    bigint not null check (revision >= 0),
    -- the version in force
    version      int    not null default 0 check (version >= 0),  -- 0 before the first activation
    activation   bigint,                                          -- null before the first activation
    format       smallint,
    body         jsonb,                                           -- null before the first activation and after a removal
    key_id       bytea  check (length(key_id) = 8),  -- the binding: null both, never bound; a key id alone, it failed
    tag          bytea  check (length(tag) = 32),
    activated_at timestamptz, activated_by text,
    -- the draft; null when there is none
    draft_id     bigint,                                          -- a new random id when a draft starts
    draft_format smallint, draft jsonb, draft_at timestamptz, draft_by text,
    -- the secret administrator's consent to what the draft sends
    consent        bytea check (length(consent) = 32),            -- the digest of the draft's sends (below)
    consent_key_id bytea check (length(consent_key_id) = 8),
    consent_tag    bytea check (length(consent_tag) = 32),
    consent_at     timestamptz, consent_by text,
    -- a connection's state
    run          int not null default 0,
    stopped_at   timestamptz, stopped_by text,                    -- null: running
    primary key (kind, key),
    check (kind = 'connection' and key ~ '^(?!(rail8|subscribers|by-agreement)/)[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63}){1,2}$'
        or kind = 'system' and key ~ '^(?!(rail8|subscribers|by-agreement)(/|$))[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63})?$'),
    check ((body is null) = (format is null)),
    check ((version = 0) = (activation is null)),
    check (tag is null or key_id is not null),
    check ((draft is null) = (draft_format is null) and (draft is null) = (draft_id is null)),
    check (consent is null or draft is not null),
    check (consent_tag is null or consent_key_id is not null),
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
    incarnation bigint, activation bigint,         -- an activation: the row's incarnation and the activation's id
    key_id     bytea check (length(key_id) = 8),   -- an activation: its binding, as the version in force has it
    tag        bytea check (length(tag) = 32),
    diff       jsonb not null,                     -- [{field, from, to}]; a secret field by its reference only
    note       text check (length(note) <= 200)    -- an activation or an import: its author's note, such as a commit
);
create index config_history_object on config_history (kind, key, id);

alter table nodes add column config_generation bigint, add column config_stamp bigint, add column config_epoch bigint,
    add column config_read_at timestamptz,         -- its last read of the store that succeeded
    add column config_refused text[],              -- the objects it left out, at most 100
    add column config_refused_count int;
```

- The migration inserts the one row of `config_store`: generation 0, a random stamp, mocks not allowed.
- The same migration creates `deployment_systems` and `deployment_connection_uses` as the design of systems and
  connections defines them, empty, and adds `deployments.config_generation`, null for every record before it. The step
  of that design that brings systems and flows by name fills them at a deploy; until then nothing names a connection,
  and the tests of this store write their rows themselves.
- **The versions kept** are the activation rows of the history, each bound as the version in force was. Going back
  copies one of them into a draft.
- **Retention.** The node's retention job deletes history rows older than `RAIL8_AUDIT_MAX_AGE` (P30D to P3660D, P365D by
  default), in its batches, except each object's 100 newest activations. A write that would give an object more than
  1,000 history rows deletes its oldest rows that are not among those 100, so saves, stops and starts never push out
  the versions kept. The version in force and the draft are in `config_objects` and are never deleted by retention, so
  an object can always be read and changed. A version whose history row is gone cannot be brought back.
- A row with no version in force, no draft and no history row left is deleted by the same job. A removed object whose
  removal is older than `RAIL8_AUDIT_MAX_AGE` loses its history too, its 100 newest activations included, so its row
  is deleted and no longer counts against the limits.
- The columns of `nodes` are what each node holds. Nothing decides by them but the screen.

## Roles and actions

One list of roles. Each says who may hold it: a person, a technical account, or both.

| Role | Id | Held by | Holds |
| --- | --- | --- | --- |
| Administrator | `administrator` | a person | users, roles, technical accounts and their tokens, sessions, the audit |
| Developer | `developer` | both | deploys, the deployed configuration, the modules' services |
| Operator | `operator` | both | monitoring, retry, cancel, release, discard; stops and starts a connection |
| Message bodies | `message-bodies` | both | a message's body after each step |
| Secret administrator | `secret-administrator` | both | the secret store; consents that a secret may go where a draft sends it |
| Connection administrator | `connection-administrator` | both | systems in the environment, the values of connections, credentials, instance names, technical identifiers, the switch of mocks |
| Partner administrator | `partner-administrator` | both | partner profiles, business identifiers, agreements, API products, subscriptions, number sequences |
| Message sender | `message-sender` | both | sends a test message to a flow (the design of who may send to a flow) |

| Action | Routes | Granted by |
| --- | --- | --- |
| `config.read` | every `GET /admin/config…` | developer, operator, connection-administrator, partner-administrator, secret-administrator |
| `config.connections` | save, discard and activate of connections and systems; a test of a draft; the switch of mocks | connection-administrator |
| `config.run` | stop, start, and a test of the version in force | operator, connection-administrator |
| `config.consent` | a consent and its withdrawal | secret-administrator |
| `config.import` | the import's door; each file is then checked by its kind's write action | connection-administrator, partner-administrator |

- `secret.list` gains the connection administrator and the partner administrator, so a form picks a secret by name and
  kind. They gain no other action of the secret store.
- The partner administrator holds `config.read`, `config.import` and `secret.list` until its first kind comes. That kind
  brings `config.partners`, its write action, granted by the partner administrator alone.
- A token may do every action here: a pipeline with a technical account imports and activates. A consent by a token is a
  secret administrator's act like any other: it states the digest of what it consents to, and the audit names its
  account. An account that holds both roles consents by its activation.

## Admin routes

A key holds `/`, so a verb follows the key after the segment `-`, which no key segment can be. Each verb is mounted as a
route of its own with its action, so the action is checked before the body is read. A body is JSON of at most 128 KiB,
so a save carries a body of 64 KiB, and an import's at most 4 MiB.

| Route | Action | Body | Does |
| --- | --- | --- | --- |
| `GET /admin/config` | `config.read` | | the environment: its generation, whether it allows mocks, each live node's generation, last read and what it left out |
| `POST /admin/config/-/mocks` | `config.connections` | `{expect: true \| false, allowed, confirm?}` | allows or refuses mocks in the environment |
| `GET /admin/config/connections?prefix&needs&after&limit` | `config.read` | | the list: key, adapter, state, version, whether a draft waits, whether it needs consent, the nodes behind; `needs=consent` or `needs=activation` narrows it |
| `GET /admin/config/connections/{key}` | `config.read` | | one connection, as the screen shows it (below) |
| `POST /admin/config/connections/{key}/-/save` | `config.connections` | `{expect, adapter, values, mock?}`, `{expect, removed: true}` or `{expect, fromVersion}` | writes the draft; creates the row when there is none |
| `POST /admin/config/connections/{key}/-/discard` | `config.connections` | `{expect}` | drops the draft and its consent |
| `POST /admin/config/connections/{key}/-/consent` | `config.consent` | `{expect, digest}`; `digest: null` withdraws | consents to exactly what the draft sends |
| `POST /admin/config/connections/{key}/-/activate` | `config.connections` | `{expect, confirm?, note?}` | puts the draft in force |
| `POST /admin/config/connections/{key}/-/stop`, `/-/start` | `config.run` | `{expect: <run>}` | stops or starts it at once |
| `GET /admin/config/connections/{key}/-/history?after&limit` | `config.read` | | its history, newest first, 50 a page, at most 500, with the changes of its secrets merged in |
| `GET /admin/config/systems?prefix&after&limit`, `GET /admin/config/systems/{key}` | `config.read` | | the systems' instance names |
| `POST /admin/config/systems/{key}/-/save`, `/-/discard`, `/-/activate`; `GET …/-/history` | as for connections | `{expect, instanceName}` | the same, without consent, stop and start |
| `GET /admin/config/export?kind&prefix` | `config.read` | | `{files: [{path, text}]}`: every version in force |
| `POST /admin/config/import?check=true` | `config.import` | `{scope: {kind?, prefix?}, files}` | the dry run |
| `POST /admin/config/import` | `config.import` | `{scope, files, expect, note?}` | writes the drafts, all or none |

A `note` is at most 200 printable characters, kept in the history and the audit row: a pipeline names the commit that
was approved, so the history says more than the technical account that activated.

A key that no row holds has one fixed `etag`, the same for every such key. A read of a key that no row holds and no
deployed system names is 404 `not-found`, carrying that `etag`; a key that a deployed system names and no row holds
reads as an object with nothing in force and that `etag`. A first save states it and creates the row, so a connection
is configured before its system is deployed.

`GET /admin/systems` of the design of systems and connections reads each connection's state from here: set, mocked,
stopped, missing, or a draft waiting.

### One connection, as the screen shows it

```json
{"kind": "connection", "key": "acme/erp/rest", "system": "acme/erp", "adapter": "http", "revision": 12,
 "etag": "…", "run": "…",
 "inForce": {"version": 4, "values": {"url": "https://erp.acme.example:8443/api", "timeoutMs": 5000,
             "password": "secret://erp-pw"},
             "activatedAt": "…", "activatedBy": "jana", "generation": 381, "binding": "ok"},
 "draft": null,
 "stopped": null,
 "secrets": [{"field": "password", "name": "erp-pw", "kinds": ["value", "credentials"], "kind": "value",
              "set": true, "changedAt": "…", "changedBy": "petr", "unreadable": false}],
 "nodes": [{"node": "node-a", "holds": "yes"}, {"node": "node-b", "holds": "no", "generation": 380, "readAt": "…"}],
 "usedBy": {"digest": "…", "flows": [{"flow": "invoices/invoice-async", "step": "partner", "direction": "out"}]},
 "form": ["the adapter's connection form, format 2"]}
```

- With a draft: `"draft": {"values", "mock", "removed", "savedAt", "savedBy", "changes": [{field, from, to}],
  "problems": [{field, code, direction}], "consent": null | {"digest", "sends": [{secret, server}],
  "given": null | {at, by}}}`. `problems` and `consent` are computed at the read, so the form shows what an activation
  would refuse before anyone presses it. `consent` is null when the draft needs none; its `sends` are the pairs that
  need it.
- `binding` is `ok` or `fails`.
- A node `holds` the version in force (`yes`) when its lease holds, its `config_epoch` is its epoch, its
  `config_generation` and `config_stamp` are the store's, and its `config_refused` does not name the object. It is
  `unknown` when the node left out more objects than it can name, and `no` otherwise. A node one change behind shows
  `no` for a moment even for an object that did not change; the screen never shows `yes` for a node that lacks it.
- `secrets` comes from the secret store's list: it shows that a secret changed, never its value.
- `usedBy` comes from `deployment_connection_uses`, paged, with the digest of the whole sorted set of uses that a
  confirmation states.

### Every write

1. The action at the door (403), then the body (400, 413).
2. One transaction. Locks in this order, which every writer keeps:
   1. the secret store's row, by an activation and a consent, which bind under the store's key as every seal does;
   2. `config_store` `for update`, by an activation, a stop, a start, the switch of mocks, a batch of binding again, and
      a save that creates a row;
   3. the object's row `for update`, inserted when absent with a new incarnation;
   4. the names' advisory locks, through the secret store's one method of uses.
3. `expect` is compared: 412 `changed`, answering the object as it is now. Then the verb's own checks (409, 422).
4. The row, its history row, the audit row where the audit lists one, the uses, the generation and stamp, and
   `pg_notify('rail8_config', '<generation>')`. Then the commit.
5. The node that wrote reads the store again before it answers, so a change in force holds on that node when it is
   answered. Every other node has it within one notice, or one tick when the notice is lost; the read's `nodes` shows
   which do.

**Save** checks the shape, of the values kept under a mock too:
- the adapter is known to the node; it is the adapter the deployed system file names, when one does (409
  `adapter-differs`);
- each value is a field of the connection form, of its field's type; a URL holds no user information;
- a secret field holds only `secret://<name>`, and no other field holds `secret://` (422 `invalid`, naming the field,
  never echoing a value); no `rail8.` name (422 `reserved`).

A save that creates a row counts the rows under `config_store`'s lock, drafts and removed objects included (409
`config-full`). A draft may be incomplete. `fromVersion` copies that version's body from the history into the draft,
checked as any save. A save equal to the version in force drops the draft. A save over a draft replaces it, keeps its
`draft_id`, and drops its consent when what it sends changes.

**Activate** checks against the state of its own transaction:
- **the adapter**: the adapter the deployed system definition names for the key now, in `deployment_systems`, when one
  does (409 `adapter-differs`), so a system deployed after the save is checked;
- **complete**: the values pass the connection form for at least one direction its adapter connects, and every secret it
  names is set, as the secret store counts it, of a kind its field takes. Else 422 `incomplete`, one line per field and
  direction, never a value;
- **consented**: when the draft needs consent, its consent equals the digest of what the draft sends now and its tag
  verifies for this row, draft and version in force, or the caller holds `config.consent` too and consents by this act
  (409 `consent-needed`, with the pairs);
- **mocks**: a mocked body only in an environment that allows mocks (409 `mocks-refused`);
- **one confirmation**: a change after which a use that `deployment_connection_uses` names could not be served needs
  `confirm`, the digest of the uses as the caller saw them (409 `in-use`, with the uses, what each would lack, and their
  digest): a removal; values incomplete for a direction a use needs, with the lines of `incomplete`; an `allowedHosts`
  left empty while a use goes through an address field. Its sends then fail, naming the connection and the reason, and
  are retried by their flow's retry and then parked, as any failed send; a polling sender through it fails its start.
  A switch to the mock leaves every use served and needs none;
- **for a system**: a removal of an instance name in use is 409 `name-in-use`, naming the flows;
- **the key**: every live node holds the key it binds under (409 `keys-differ`, the secret store's rule).

It then binds the body, writes `version + 1` and a new `activation`, writes the history row of the activation with the
same binding, clears the draft and its consent, clears the stop and raises `run` on a removal, and replaces the
object's rows of `secret_uses` through the store's one method: its secret names, none for a mocked or removed
connection.

A **stop** of a connection with nothing in force is 409 `not-set`. A stop holds for every attempt that starts after it:
the attempt fails with an error naming the connection and that it was stopped, by whom and when, and the flow's retry
tries it again, then parks it as any failed send. An attempt already started ends.

## Where a secret goes: consent

The secret administrator decides to which servers each secret may go. A connection administrator decides everything
else. The consent is held on the object and given to the exact draft it covers.

- **The server.** An adapter marks with `.server()` the fields of its connection form that name the server it connects
  to: a URL, a host, a port, each a text or an integer field; the description serves such a field with `"server": true`.
  A connection's server is the list of the effective values of those fields, in the form's order: a field not set
  counts as its default, a URL counts by its host and its port alone, the scheme's default port filled in, and a host is
  compared in lower case. It is written as text, the values joined by `:`, so `http`'s server and `sftp`'s are each
  `host:port`; an IPv6 literal is written in brackets, and a marked text field that is not a URL refuses a `:` outside
  brackets (422 `invalid`), so two servers never write the same text. Each entry of the platform's `allowedHosts` is a
  server of its own, written `host:port` with its host in lower case and a `*.` kept as written. An entry without a port
  takes the scheme's default; on a form whose address fields name more than one scheme it is one server for each of
  their default ports, so the consent names every port it lets a secret reach. No other field counts: a path, a time limit, a
  proxy, a trusted certificate, a pinned certificate or host key, a switch of TLS verification. One function in
  `rail8-runtime` computes the servers and the sends, and the read, the consent, the activation and a test all call it.
- **What a body sends** is a set of pairs: each secret its secret fields name, with each of its servers. A secret that
  holds trust, such as a pinned certificate or a host key, counts as any other. A mocked or removed connection, and a
  system, send nothing.
- **What counts as consented.** A pair that a verified version of this connection sent: the version in force, or an
  earlier version kept in its history, whose binding verifies inside the transaction that decides. A body that is
  unbound or does not verify consents to nothing. A version whose history row retention deleted consents to nothing,
  so going back to it asks again.
- **When a draft needs consent.** When it sends a pair that is not consented: a secret to a new server, or a secret the
  connection did not send before. Going back to a server consented to before, a change of a path, a proxy or a trust,
  taking a secret away, and two secret fields swapped need none. So removing a pin, or switching TLS verification off on
  the same server, is the connection administrator's alone, and shows in the history and the audit.
- **The digest** is the SHA-256 of the RFC 8785 JSON `{sends}`, the pairs that need consent, sorted. The read shows it
  with those pairs.
- **Consent.** `/-/consent {expect, digest}` writes the digest on the row, bound with the row's incarnation, the
  draft's id and the activation in force, and one audit row `config.consent` naming the pairs. When the digest is not
  that of the draft as it is now, it is 412 `changed`, answering the object; so a draft
  changed after the secret administrator read it, through the API or in SQL, is never consented unseen. With no draft,
  or one that needs none, it is 409 `nothing-to-consent`. `digest: null` withdraws a consent given by mistake. A
  consent and a withdrawal change the `etag`, so of two secret administrators the later one sees the earlier one's act.
- **The consent belongs to its draft.** An activation uses it up; a discard, or a save that changes what the draft
  sends, drops it; a save that keeps what it sends keeps it. A consent of an earlier draft never verifies for a later
  one, since each draft has its own id. A version in force carries its own consent in its bound body.
- **By the same act.** An activation by a caller who holds both `config.connections` and `config.consent` consents to
  the pairs the draft needs, as its `expect` shows them: no consent call comes first. Its audit row `config.activate`
  names those pairs as consented by it.
- **An adapter that sends a secret has a server.** `Forms` refuses a connection form that holds a secret field and no
  `.server()` field.
- **The inbox.** `GET /admin/config/connections?needs=consent` is the secret administrator's list of drafts that wait.
- **A test** sends secrets as an activation does, and needs the same consent (the step that builds the test). It runs
  on the copy of the values its check read, never on a draft read again.
- The rule holds from the first step that stores a connection.
- `rail8 config consent` takes the digest that the read shows, never computes it.
- A secret deleted and created again under the same name goes where the versions in force that name it send it. The
  secret store's record of uses lists them for the secret administrator who creates it.

## The binding

- Each body in force is bound: an HMAC-SHA256 tag through the secret store's binding (a subkey of the store's key, HKDF
  info `rail8 settings v1 bind`, named by the key's id) over the RFC 8785 JSON array `["rail8 config v1", kind, key,
  incarnation, version, activation, format, body]`. A consent is bound over `["rail8 consent v1", kind, key,
  incarnation, draft id, activation in force or null, digest]`. The integers of these arrays and of the `etag`'s are
  written as decimal strings, since RFC 8785 writes a number as a double and a random 64-bit id would lose its low
  bits. A value of a body that is a number beyond ±2^53 is refused at its save (422 `invalid`), so a body's canonical
  JSON is exact.
- A node refuses a body in force that is unbound or whose tag does not verify, whether it names a secret or not, at its
  start and at every read: it is absent from the node's snapshot, so sends through it fail as
  through a connection not set, `/health` is `DEGRADED`, the read shows `binding: fails`, the node's `config_refused`
  names it, and a WARNING names the key and the key's id once. A connection administrator saves it again and activates,
  with the secret administrator's consent when it sends a secret.
- **Every environment has the store's key**, so every body is bound at its activation, in `dev` and `ci` too. The kinds
  that come later, a system's credential, an issuer, an API product and a subscription among them, are bound and
  refused by the same rule, with no case of their own.
- **A change of the store's key** binds the rows again, as the secret store asks at its tick: up to 100 rows a batch,
  `for update skip locked`, so a row being written is skipped and bound by a later batch. Each tag, of a body, of a
  history row and of a consent, is verified under the key it names before it is bound under the new one. A tag that does not verify is
  cleared and its key id kept, which marks it failed: it stays refused and is never bound again, with a WARNING. A batch
  raises the generation and the stamp and notifies, so every node reads the new tags and leaves out a failed row without
  anyone activating. It changes no revision, version, activation or history. So the previous key is dropped once no row
  is bound under it.
- Drafts are not bound. What a draft sends is checked against its bound consent at its test and its activation.
- A connection's stop and the switch of mocks are not bound. A database writer can start a stopped connection or allow
  mocks, as they can delete a secret's row or a message's work; none of it sends a secret anywhere new. The UI calls a
  stop an operator's control, not a security one.
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
    public void onActivation(Consumer<Snapshot> listener);   // once per snapshot taken, in the order taken
}
interface ConfigStore {                             // Postgres, or one in memory; one contract suite for both
    ObjectView read(Ref ref);                       // the object as the screen shows it
    Page<ObjectView> list(Query query);
    ObjectView write(Actor actor, Ref ref, Change change);   // every write, in the order above
    void writeMocks(Actor actor, Change.Mocks change);       // the switch of mocks, which has no key
    State state(Held held);                         // what a node lacks against what it holds
    void holds(String node, long epoch, long generation, long stamp, Refused refused);
    Page<HistoryRow> history(Ref ref, OptionalLong after, int limit);
}
record Ref(Kind kind, String key) { enum Kind { CONNECTION, SYSTEM } }
```

- **Wake.** Each node listens on `rail8_config` on the connection it already holds for `LISTEN`. It reads
  `config_store` on each notice, after every `LISTEN` acknowledged (a reconnect may have lost notices), and at each 5 s
  tick of the injected timer. The payload is only a wake-up. Notices are coalesced: one read runs at a time, and a
  notice during it makes one more.
- **Read.** When the generation or the stamp differs from what it holds, it reads in one repeatable-read transaction
  every row's kind, key, incarnation, activation, key id, tag and stop, and the bodies of the rows whose incarnation,
  activation, key id or tag differ from what it holds. Every read compares every row, so a database set back, set back
  and written again, or with a row deleted, is read right, bound or not. A generation behind its own, or the same
  generation with another stamp, is a WARNING.
- A row changed in SQL without a change of `config_store` is read at the node's next start, or when its activation or
  tag changes; its tag fails then. Until then the node keeps the body it verified.
- **Verify.** It verifies each body's tag and upgrades an older format in memory. A body that fails is left out, as
  above.
- **Swap.** It builds an immutable snapshot (per connection key `Values` or `Mocked` with `stopped`, who stopped it and
  when, and, as the connection's version, its `activation`; per system key the instance name; whether mocks are allowed;
  the generation), swaps it in, and calls the listeners. A mocked connection is left out while the environment does not
  allow mocks, with that reason. A snapshot may cover several activations, and after a database set back it may carry a
  lower generation; a listener compares it with the one before. Then it writes `config_generation`, `config_stamp`,
  `config_epoch`, `config_read_at` and what it left out into its row of `nodes`, only while that row carries its own
  epoch. A failed write is a WARNING, and the next tick writes again.
- **At start**, a node reads the whole store before it restores its packages and takes any work.
- **Postgres away.** A node that cannot read the store keeps its last good snapshot and goes on sending by it. After
  30 s without a successful read, `/health` is `DEGRADED`, naming the time of its last read. A change committed
  meanwhile, a stop among them, reaches it at its next read. A node that cannot renew its lease fences itself after half
  a lease, 15 s, as every node does.
- **A message in flight.** Each attempt reads one snapshot and keeps it, and the producer it took, until its send
  settles. The next attempt through a connection whose `activation` differs from its producer's builds a new producer;
  since an activation's id is never reused, a restore followed by a new activation never meets an old producer. A stop,
  a start, binding again, or a new value of a secret it names rebuilds no producer; a stop stops the connection's
  polling sender and a start starts it again. A send on the wire ends with the old values. Every later attempt, a retry
  waiting in the queue included, reads the newest snapshot, so a message whose send failed because of a wrong address
  goes through at its next retry once the address is corrected. A message already parked waits for an operator's retry,
  as any parked message does.
- No read of a send goes to Postgres. A secret stays a reference in the snapshot, read through its handle at each use.

## The deploy

- The deploy reads the store first, as a write does, so a change activated through another node a moment earlier is
  seen.
- The check of the design of systems and connections then reads `current()`: every connection a flow can reach is set
  and complete for its adapter and directions, or mocked where the environment allows mocks; every system with a used
  connection has an instance name; a step that uses an address field has allowed hosts; a connection the node left out
  counts as not set.
- In its record's transaction, the deploy takes `config_store` `for share` before the names' locks, reads again from
  Postgres the objects it reaches, checks them again, and writes `deployments.config_generation`, the generation it
  read. An activation takes `config_store` `for update`, so a removal waits for the record and sees its uses, and a
  deploy waits for an activation and checks it. The uses of a retiring flow count until its drain ends.
- **The window before the record.** The flows a deploy starts before its record is saved read the snapshot at every
  attempt, so each of their sends goes through a connection as it is in force at that attempt, or fails. A deploy whose
  record is refused then stops them; what they sent meanwhile went where the configuration in force said.
- **What names the state.** The record names the generation. The history's rows of activations, stops and starts carry
  the generation they made, so the history says which version of each object was in force at that generation, for as
  long as it keeps those rows.
- **What the deploy expects.** A deploy still expects only its package's commit (`expect` of the deploy from git). It
  expects nothing of the configuration: activations are per object, and an activation never waits for a deploy's
  author.
- A restore at a node's start and a follow check as the design of systems and connections says.

## The quickstart, the demo and CI

No node reads a file of the environment. The quickstart, the demo and CI configure their environment as a production
team does, through the import:
- `deploy/quickstart/config/` holds one file per object of the demo's three systems, `connection/<key>.yaml` and
  `system/<key>.yaml`, in the format below, with `secret://` references to the secrets the store's import took from the
  stack's variables.
- `deploy/quickstart/configure` runs the pipeline below over that folder: the dry run, the write with its `expect`, and
  each activation.
- `rail8 bootstrap` takes `--role <role>`, repeatable, for its technical account beside the developer role. The
  quickstart and CI pass `connection-administrator` and `secret-administrator`, so each activation consents by the same
  act. Outside the quickstart an administrator gives these roles to an account on purpose, and the quickstart's text
  says so.
- The platform's own tests use the store in memory.
- These come with the step of systems and connections that brings systems and flows by name, the first to read the
  store.

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
- A file holds no secret's value, no state (the stop, a sequence's current number), no revision, version, token, date
  or name of who changed it, no draft and no consent. In place of a secret it holds the reference, and under `secrets`
  the secret's kind and, for a key pair, its public facts (algorithm, the leaf's subject and end, the fingerprint of its
  public key), as the secret store's export promises.
- The export leaves out a field at its default, so a file stays readable by a target whose adapter does not know a
  field added since.
- **Versions of a kind.** A node reads every format of a kind up to its own and upgrades an older one in memory, one
  pure, tested step per format; the same chain reads stored bodies. A newer format is 422 `format`, naming the newest
  it reads. A format is raised only for a change an older reader would misread, and a release reads a new format one
  release before any release exports it, so an export from an environment one release ahead imports into one behind.
  A new kind is a new schema of its own.
- **Versions of a connection's values.** An adapter's connection form is a contract of its own: a field is added only
  with a default that means "as before", and never renamed, removed or narrowed, and its default never changes, since a
  field at its default is neither exported nor counted as part of a server. A change that would is a new adapter.
  So every stored body and every file stays readable, and a server stays the same when a field is added. A file
  that sets a field the target's adapter does not know is a problem of the dry run, naming the adapter and the field.
- **The dry run** takes the scope it covers (`kind` and `prefix`, or everything) and answers `{expect, objects: [{kind,
  key, change, fields, inForce}], blockers, notes, unnamed, problems}`:
  - `change` is `created`, `changed` or `unchanged`, against the draft, else the version in force; `inForce` says
    whether the file equals the version in force;
  - `blockers` are what would refuse a later activation without refusing the draft: a secret the target does not hold,
    holds unreadable, or holds of a kind its field does not take;
  - `notes` are keys that no deployed system defines yet, and mocked files in an environment that does not allow mocks;
  - `unnamed` are the target's objects in the scope that no file names. The import leaves them alone;
  - `problems` refuse the write: the schema's and the save's refusals, two files of one kind and key, a file outside
    the scope, a field the target's adapter does not know, and a draft that waits and differs from the file
    (`draft-waits`): an import never replaces another's draft, which its author or a connection administrator first
    discards;
  - `expect` is the SHA-256 over the scope, the files as sent, and each named object's kind, key and `etag`.
- **The write** sends the scope, the files and `expect` back. It takes `config_store` first, as a save that creates a
  row does, and counts the rows it creates (409 `config-full`). It locks the named rows in the order of their kind and
  key, computes the dry run again under those locks, and when anything differs from `expect` it is 412 `changed`,
  answering the new dry run. It writes a draft for each created or changed object in one transaction and never
  deletes, discards or activates. An object whose file equals its draft keeps the draft and its consent. It answers
  each object with its `etag` and `inForce`. Each object gets a history row `import`; the import gets one audit row
  `config.import`.
- A file is at most 128 KiB, so every body a store accepts has a file. A call holds at most 1,000 files and 4 MiB;
  `rail8 config import` refuses a folder beyond either and says to import it by `--kind` or `--prefix`, each call all
  or none. The UI's server passes the import's 4 MiB on that one route.
- An export is not a backup: it holds what is in force only, no history, no draft, no state, no consent, no value.

## The command `rail8 config`

It sends `RAIL8_TOKEN` to `--admin`, else `RAIL8_ADMIN`, as `rail8 deploy` does. Exit 0 when done, 1 for a refusal or problems, 2
for a usage error. It never fills in an expected state, a confirmation or a digest for its caller.

| Command | Calls |
| --- | --- |
| `rail8 config get connection\|system <key>` | the read, printed as JSON with its `etag` and `run` |
| `rail8 config export <dir> [--kind k] [--prefix p]` | the export, one file per object |
| `rail8 config import <dir> [--kind k] [--prefix p] --check` | the dry run; prints it and its `expect` |
| `rail8 config import <dir> [--kind k] [--prefix p] --expect <sha256> [--note text]` | the write of the drafts; prints each object's `etag` |
| `rail8 config consent connection <key> --expect <etag> --digest <sha256>` | one consent |
| `rail8 config activate connection\|system <key> --expect <etag> [--confirm <digest>] [--note text]` | one activation |
| `rail8 config stop\|start <key> --expect <run>` | stops or starts a connection |
| `rail8 config mocks allow\|refuse --expect <true\|false> [--confirm <digest>]` | the switch of mocks |

A pipeline that keeps an environment's configuration in a git repository of its own, with approvals on its host:

```sh
set -euo pipefail
commit=$(git rev-parse HEAD)
plan=$(rail8 config import config/test --check)
written=$(rail8 config import config/test --expect "$(jq -r .expect <<<"$plan")" --note "$commit")
jq -r '.objects[] | select(.inForce | not) | "\(.kind) \(.key) \(.etag)"' <<<"$written" |
while read -r kind key etag; do
  rail8 config activate "$kind" "$key" --expect "$etag" --note "$commit" || exit 1
done
```

Each activation stands alone: one refused (`consent-needed`, `incomplete`, `in-use`) leaves the ones before it in force
and fails the job. The next run, once the secret administrator has consented, activates what is still not in force.
This is a loop of activations, not a change list.

## Audit and history

| Change | History | Audit row |
| --- | --- | --- |
| a save, a discard | `save`, `discard`, with the old and new values | none |
| an import | `import` on each object, with the note | `config.import`: the objects, their changes, the blockers, the note |
| an activation, a removal | `activate`, with the body put in force, its binding and the note | `config.activate`: the version, the changed fields, the confirmation, the pairs consented by the same act, the note |
| a consent, a withdrawal | none | `config.consent`: the pairs, or the withdrawal |
| a stop, a start | `stop`, `start` | `config.stop`, `config.start` |
| the switch of mocks | none | `config.mocks`: the new state, the generation, the confirmation |
| a test | none | `config.test`: the intent before the call, the outcome after (the step that builds the test) |

- An audit row is written in the transaction of its change: a change whose row cannot be written does not happen (503
  `audit-unavailable`). Its subject is `config/<kind>/<key>`. A test is the one act outside a transaction: its intent
  is committed with an id before the adapter is called, no lock is held during the call, and its outcome is a second
  row; a node that dies between them leaves the outcome unknown, and the screen says so.
- The audit names fields, never their values: a production address stays in the history, which the roles that read the
  configuration read, and out of the audit.
- That a secret changed is shown by the read's `secrets`, from the secret store's list, never by its value. An
  object's history merges in, at the read, the secret store's audit rows `secret.overwrite` and `secret.delete` of the
  names its versions held, so it shows that a password changed between two activations, and when.

## A lost or corrupt store

- The backup of Postgres covers every object, draft, consent, history row, the generation, the uses and the audit. It
  does not hold the store's key. Restored with the key, everything verifies. Restored without it, every bound body and
  consent fails to verify: each is absent on every node until a connection administrator saves it again and a secret
  administrator consents; its secrets are unreadable in any case.
- A database set back to an earlier state is read whole by every node, with a WARNING, and a producer of a later
  activation is never used for it.
- An export is not a backup: it holds no state, no history and no secret.

## A new environment and its secrets

- Values never travel. An export names each secret and its kind; the dry run of the import lists the secrets the target
  lacks; the secret administrator there enters each through `POST /admin/secrets`.
- Consents are never exported: the secret administrator of each installation consents there, to its own servers.
- Environments hold different secrets anyway: production's password is not test's.

## The rules of the secret store

1. **No secret to a server the secret administrator did not agree to.** A draft that sends a secret to a server no
   verified version of its connection sent it to, or a secret the connection did not send before, needs a consent to
   the digest of those pairs, given by the same act when its activator holds both roles. It ships with the first step
   that stores a connection.
2. **No test past rule 1.** A test needs the same consent as an activation, and runs on the values it checked.
3. **Nothing a database writer could change unseen.** Every body in force, every kept version and every consent is
   bound under the store's key, which every environment has, and a node refuses what is unbound or does not verify.
   Only a verified version counts as consented, and a consent states the digest its giver saw.
4. **No secret read back, no kind from elsewhere.** Answers and files hold names; the kind is read from the store at each
   check; `secrets` in a file is never read; no route changes a secret's kind; no key is a secret's name.
5. **No value in a draft, a history or a diff.** A secret field holds only a reference, a URL no user information and no
   credential, under a mock too; going back copies a body into a draft and runs every check again against today's
   secrets.
6. **An inbound caller's password only as a reference.** A system's credential, when its kind comes, holds its secret in
   a secret field.
7. **No git base, store's key or `rail8.` name as a setting.** No kind holds them; a key never starts with a `rail8`
   segment, and a value never holds a `rail8.` name.
8. **`secret_uses` only through the store's method, never for a draft.** Only an activation writes them.

## Open places

- **A second person's approval of an activation.** Its place is the activation, after the checks and before the
  binding: a route `/-/approve {expect}` that records an approver of a draft's `etag`, and a check that one is there.
  Not built.
- **A scope of the two roles by partner or by package.** The decision point takes a package with each action today,
  and ignores it. A scope of these roles needs it to take the object's key too: a partner scope matches the keys of
  that partner's systems, a package scope the systems the package defines. `role_assignments.scope` gains values; no
  table of this design changes.

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
| Connections; systems | 20,000; 4,096, as an installation holds; drafts and removed objects count. A later kind sets its own |
| A body; all bodies in force together | 64 KiB as canonical JSON, 128 fields; 64 MiB |
| History | 1,000 rows an object and `RAIL8_AUDIT_MAX_AGE`, its 100 newest activations always kept |
| A request's body; an import | 128 KiB; 1,000 files, 128 KiB a file, 4 MiB a call |
| A page of a list; of a history | 500; 500 |
| Objects a node names as left out | 100, and their count |
| The time a change takes to reach a node | the notice; at most one tick, 5 s, when a notice is lost |
| A node without a read of the store | `DEGRADED` after 30 s; it sends by its last good snapshot |

## Tests

- One contract suite, `ConfigStoreContract`, runs for the Postgres store (Testcontainers) and an in-memory one. Two
  stores over one database, or over one in-memory backing with a notice bus a test can make drop notices, stand for two
  nodes, each with its own fake clock and timer. A tamper hook on each (SQL, or a method) plays the database writer.
  Across them:
  - every refusal code, and `expect` on every verb: a save over another's save is 412; a stop beside a pending draft
    refuses neither; a stop delayed past a stop and a start is 412; an old request against a row created again is 412;
  - a change in force seen on the other node after the notice, and after one tick with the notice dropped; `holds`
    `yes`, `no` and `unknown`, never `yes` for a node one change behind or on the other side of a set back;
  - a database set back; set back and then activated to the same version number; a row deleted: each read right,
    and the next send goes to the receiver in force, never through an old producer;
  - a tampered body, an unbound body, a consent forged, replayed for a later draft or moved to another
    row: each refused, never used; an activation over a tampered version in force asks consent for every send;
  - binding again: a tampered body, history row or consent loses its tag and is not blessed, and the other node leaves
    it out with nobody activating; the tag's encoding tells version 1 with format 12 from version 11 with format 2;
  - consent: a new host or port, a new secret, a new trust secret and an added allowed host need it; a path, a proxy, a
    trust removed, a TLS switch, a field set to its default, a host's case, hosts reordered or removed, a sent secret
    taken away and two secret fields swapped do not; going back to a server of a kept version needs none, and of a
    version beyond retention needs it; a history row changed in SQL consents to nothing; an activation by a holder of
    both roles consents, and names the pairs in its audit row; a draft changed in SQL after the read is refused at the
    consent, and after the consent at the activation; two secret administrators granting and withdrawing; a withdrawal;
    a form with a secret field and no `.server()` field refused;
  - activation: incomplete for every direction refused as 422; incomplete for a direction a use needs, a removal and
    an emptied `allowedHosts` each refused without the digest of the uses, refused again when a deploy changed the uses
    after the refusal, and the next send after it failing, naming the connection and the reason, and retried; a switch
    to the mock with no confirmation; a secret of a kind its field does not take; an adapter changed by a system deployed
    after the save; a mocked body refused where mocks are not allowed; mocks turned off only with the mocked used
    connections confirmed, and turned on without; an instance name in use changed, and its removal refused as
    `name-in-use`;
  - a removal racing a deploy and a deploy racing an activation, both ways, end in a consistent record or a refusal; a
    deploy through another node right after an activation sees it; a retiring flow's uses count; a deploy refused at
    its record after it started flows: every send meanwhile went through a connection in force or failed, and the
    flows are stopped;
  - the history: a secret overwritten between two activations shows between them; a note kept in the history and the
    audit, one over 200 characters refused;
  - retention by age and by count: 100 activations of a quiet object older than the age kept, and of a busy one through
    1,000 stops and starts; going back from a kept version; a version beyond retention refused; two first saves at the
    limit, one refused;
  - the import: the dry run's lists in its scope, `draft-waits`, duplicates, an unknown field, 412 after a change of a
    file or an object, all or none, a format 1 file read once format 2 exists; the pipeline above run twice around a
    consent activates what the first run left.
- The send path: a retry waiting in the queue goes out with the corrected values; a send on the wire ends with the old
  ones; a stop makes the next attempt fail, naming the connection, who stopped it and when, and after a start the next
  retry goes out; a change of one connection rebuilds no other producer, and a new value of a secret rebuilds none.
- A node cut off from Postgres keeps its snapshot and sends by it, is `DEGRADED` after 30 s of the fake clock, naming the
  time of its last read, and takes a stop committed meanwhile at its next read.
- The routes: each called as every role, through a session and a token; each refusal of the codes gives its code.
- No answer, log line, history row, audit row or export of any test holds a secret's value, checked by searching every
  captured text for each value written to the secret store.
- A fake clock and an injected timer move every tick. No test sleeps.
- Step 2b measures, in `bench/`, the time a change takes to reach every node and the time a write takes, with the
  store at its limits, while 1,000 objects are saved and activated one by one, and with notices lost. An index of
  changes, beside the full comparison that a set back needs, comes only if that measurement asks for it.

## Refusals

| Status | Code | When |
| --- | --- | --- |
| 400 | `bad-request` | a path or a body not of the route's form, a key the parser refuses among them, naming the rule it breaks, or without `expect`; after the caller's authorisation, before any read of the store, with no audit row. A well-formed key that no row holds is 404; in an import file a refused key is a problem of that file |
| 404 | `not-found` | a read of a key no row holds and no deployed system names; a `fromVersion` whose history row is gone |
| 409 | `no-draft`, `nothing-to-consent` | a discard or activation without a draft; a consent with no draft, or to one that needs none |
| 409 | `not-set` | a stop or start of a connection with nothing in force |
| 409 | `consent-needed` | an activation or a test of a draft whose consent is missing or does not verify, by a caller who does not consent by the same act; names the pairs |
| 409 | `in-use` | a change that leaves a used connection unusable, without the digest of the uses as they are |
| 409 | `name-in-use` | a removal of an instance name of a system in use; names the flows |
| 409 | `mocks-refused` | an activation of a mocked body in an environment that does not allow mocks |
| 409 | `adapter-differs` | a connection's adapter other than its deployed system definition names |
| 409 | `keys-differ` | an activation or a consent while a live node lacks the key it binds under |
| 409 | `config-full` | beyond a limit |
| 412 | `changed` | `expect` or a digest differs; answers the object, or a new dry run, as it is now |
| 413 | `too-large` | a request's body over its limit |
| 422 | `invalid`, `reserved` | a value against its field, a value in a secret field, a URL with user information, a number beyond ±2^53, a note over 200 characters; a `rail8.` name |
| 422 | `incomplete` | an activation's problems, each with field, code and direction, never a value |
| 422 | `format` | an import file of an unknown kind or a newer format |
| 422 | `problems` | an import whose dry run has problems, `draft-waits` among them |
| 503 | `audit-unavailable` | an audit row not written |

## What it relies on from the design of systems and connections

That design is agreed, and this one follows it. It relies on:
- the keys, and `Environment` with its snapshot: `onActivation` once per new snapshot, a version per connection that
  changes only when its values or its mock change, `stopped`, and `mocksAllowed`. The version this store gives is the
  activation's random id, never reused, so "differs from its producer's" holds after a database set back; and a
  snapshot after a set back may carry a lower generation, which a listener compares as any other;
- producers kept per connection and version, and a secret's handle read at each use;
- an attempt through a connection that is stopped, not set, left out, of another adapter or incomplete, which fails with
  an error naming the connection and the reason (for a stop, who stopped it and when), is retried by the flow's retry,
  and is then parked as any failed send;
- the connection form's format 2, with the mark `.server()` and the rule that a form only adds fields with a default
  meaning "as before", which the first step here adds;
- `deployment_systems` and `deployment_connection_uses` with its directions and its uses through an address field, as
  that design defines them: this store's migration creates them, and that design's step of systems and flows by name
  fills them;
- a system key defined by one package only, so a system has one definition and one adapter per connection;
- the built-in mock, and `GET /admin/systems` reading each connection's state from here.

## Steps

Each step is one issue, built and merged on its own. This store is built before the systems and flows by name of the
design of systems and connections, which is the first to read it: the secret store's store, its one source and its
credentials in adapters, that design's adapter's contract, then steps 1, 2a, 2b and 3 here, then that design's step of systems and
flows by name. Until that step every flow runs on endpoint files, so the demo and the quickstart work after each step
here unchanged.

1. **The mark on the connection form.** `class:C`, after the adapter's contract of systems and connections and the
   secret store's credentials in adapters, since all three change `Field`.
   - `.server()` on a form's fields in `rail8-api`, and `Forms` refusing a connection form with a secret field and no
     `.server()` field.
   - The function that computes a connection's servers, its pairs of secret and server, and their digest.
   - The rule that a form only adds fields, each with a default meaning "as before", and never changes a default,
     checked by the component contract suite against a recorded form.
   - `http`'s `url` and `sftp`'s `host` and `port` marked; a URL with user information refused.
   - No change to the demo, the quickstart or the database.
2. **The store,** in two issues, both `class:C`.
   - **2a, the store itself,** after step 1 and the secret store's one source, which makes its key mandatory.
     - The migration, with the empty `deployment_systems` and `deployment_connection_uses` and
       `deployments.config_generation`, and `ConfigStore` on Postgres and in memory with `ConfigStoreContract`: every
       case of the tests below that names no node's read, deploy, send, route or import. Step 2b adds those of a node
       and the routes; step 3 those of the import and the pipeline; the step of systems and flows by name those of the
       deploy and the send path.
     - The one parser of system and connection keys, in `rail8-core`, with `rail8`, `subscribers` and `by-agreement`
       never first, and its test; the store, its routes and its import read every key through it, and the step of
       systems and flows by name uses it.
     - The objects `connection` and `system`; drafts, activation, removal, going back; the numbers and tokens; the one
       confirmation; the instance name in use.
     - The consent on the object, by the same act too; the binding of the body in force and of the history's
       activations, and binding again.
     - The history with its notes, its secrets' changes and its retention.
     - No node reads the store yet and no route exists.
     - Changes the database. No change to the demo or the quickstart.
   - **2b, the node and the routes,** after 2a.
     - `Environment`, `InMemoryEnvironment` and `StoredEnvironment`, the nodes' report, the rule for Postgres away.
     - Stop and start; the switch of mocks; the routes with their actions and the audit.
     - The roles connection administrator and partner administrator, who may hold each role, and `secret.list` for
       them.
     - A mocked body is accepted once the built-in mock of systems and connections is in, and `allowedHosts` counts as
       servers once a whole address from the message is.
     - The measurement in `bench/`.
     - Changes the database (the columns of `nodes`, and the two roles in the check of `role_assignments.role`). The
       architecture's line on mocks in a deployed environment says the environment decides. No change to the demo or
       the quickstart.
3. **Export, import and `rail8 config`.** `class:C`, after 2b: a file format that is a contract, with its JSON Schemas.
   The format with its chain of upgrades, the dry run and the write, and the command. No change to the demo, the
   quickstart or the database. The step of systems and flows by name comes after it, with the deploy's check and record
   against the store, `GET /admin/systems` reading it, the send path, `deploy/quickstart/config/`, its script and
   `rail8 bootstrap --role`.
4. **Test connection.** `class:C`, after 2b: a new contract of the adapter.
   - A `probe` on `Component` with a default that answers it cannot test; the probes of `http` and `sftp` and their
     mocks, checked by the component contract suite, with Postgres away during a probe among the cases.
   - `/-/test` of the version in force and `/-/test-draft` of a draft, with the consent of rule 2, the copy of the
     values it checked, and the intent and outcome in the audit.
   - No change to the demo, the quickstart or the database.
5. **Editing on the Systems screen under Configure.** `class:B`, after 2b, 3, the Secrets screen and the read-only
   Systems screen of systems and connections.
   - The one form of a connection with its secrets; the draft beside the version in force with its changes, problems
     and the consent it needs; activate, discard, go back, with the confirmation of uses and a note.
   - The history; which nodes hold it; instance names; the switch of mocks.
   - The secret administrator's list of drafts that wait for consent, with consent and withdrawal.
   - An import from a folder through the UI's server, which passes 4 MiB on that route.
   - No change to the demo, the quickstart or the database.
6. **Connections in Monitor, and the test in the UI.** `class:B`, after 2b, 4 and 5.
   - Each connection's state, its last success and last error, stop and start.
   - Each node keeps, per connection, the time of its last success and its last error (the fault's kind and its
     scrubbed text) and writes them to a table of its own at most every 5 s; the view reads the live nodes' rows.
   - Test connection, on the Systems screen's form and in Monitor.
   - Changes the database; no change to the demo or the quickstart.
7. **Partner profiles and identifiers.** `class:C`, with the design of partners first. It adds its kinds to this store.
