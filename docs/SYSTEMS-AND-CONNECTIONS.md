# Systems and connections

This document is the agreed design of how a flow names what it talks to:
- the **system** as a file of a package, with its **connections** by name, and how its key stays unique in an
  installation;
- the new shape of a flow's **sender** and **receiver**, the three ways a send step picks its connection, and the
  expressions in the settings in the flow;
- the adapter's contract in `rail8-api`: two forms where it had one, text keys for every label, the place for the
  credentials a caller presents;
- what the node checks at a deploy, what it does at each send and when a connection changes or disappears;
- what `rail8 validate` and `rail8 test` do without an environment, a built-in mock of a connection, delivery to several
  receivers, and how `examples/demo` and the adapter template move to the new shape.

It is built in the steps listed at the end. The demo package and the quickstart work after every step.

Today a package holds endpoint files (`kind: endpoint`: an id, an adapter's scheme, a direction, the adapter's whole
`config` and overrides per environment under `environments`). A flow names one by id in `from.endpoint` and in a `to`
step (`endpoint`, or `byProp` with `endpoints`). An adapter describes one form, and the UI saves it into the endpoint file.
So an address, a user and its password reference travel through git, and a difference between environments is an
override in a file.

## Names

The word *endpoint* leaves Rail8. These are the names in the UI, the files and the documents.

| Concept | Name |
| --- | --- |
| A company Rail8 exchanges messages with, or one's own | Partner; one's own is marked **Own company** |
| An application that sends or receives | System, with a **Name** and an **Instance name** |
| How Rail8 reaches a system | Connection |
| How a system proves itself to Rail8 | Credential |
| The two ends of a flow | Sender, Receiver |
| Who may send to a flow | Allowed senders |
| The three parts of the UI | Design (what is in git), Configure (what an environment holds), Monitor |

Rail8 supports moving from SAP PO and SAP Integration Suite and is a copy of neither. The SAP words map onto these:

| SAP PO / Integration Suite | Rail8 |
| --- | --- |
| Party | Partner |
| Business system, business component, communication component; a participant of an integration flow | System (its name); the SLD's technical name, such as `EP1CLNT100`, is its instance name |
| Communication channel; an adapter configured on an integration flow's connection | Connection (in the environment) and the settings in the flow (in the flow) |
| Sender agreement, receiver agreement | The sender and the send step of a flow |
| Receiver determination, interface determination | A computed receiver, the `receivers` step |
| Integration flow, integrated configuration | Flow |
| An externalized parameter of a channel or an adapter | The connection's values in each environment |
| Variable substitution, dynamic configuration | An expression in a setting in the flow |

## The model

- A **system** is an application Rail8 sends to or receives from. It is a file of a package and is defined once in an
  installation. Its **key** is its name, or its partner and name: `eshop`, `acme/edi`.
- A **connection** is how Rail8 reaches a system with one adapter. Its key is the system's key and its own name:
  `eshop/rest`, `acme/edi/as2`. A connection is shared by every flow that names it.
- An adapter's settings split in two. The **connection** says where and as whom: address, how to log in, certificates,
  proxy, time limits. The **settings in the flow** say what this flow does there: path, method, folder, file name.
- Git holds the system's partner and name, a description, and for each connection its name, its adapter type and a
  description. Nothing else of a connection is in git: no address, no login, no default.
- Each environment holds every connection's values (or the switch to a built-in mock), and each system's **instance
  name**, outside git. A flow is the same file in every environment. Storing and activating these is the configuration
  of an environment, a design of its own; this one fixes only what it must hold and what the node asks of it.
- A flow's **sender** names the adapter type and the settings in the flow, and leaves a place for who may send. An HTTP
  sender has no connection: nobody is called. A polling sender (SFTP) has one: the system Rail8 fetches from.
- A flow's **receiver** is a send step that names the adapter type, picks its connection by name, computed, or by
  agreement (reserved), and holds the settings in the flow, some of which may be expressions. A flow sends only through
  a connection.
- A call between flows of one node stays the internal adapter `direct`, with no connection and no system.

## Keys

A key is made of segments: `^[a-z][a-z0-9-]{0,63}$`, never `rail8` as the first one.

- A **system key** is `<name>` or `<partner>/<name>`; a **connection key** is `<system key>/<connection>`. A field that
  takes a connection key reads 2 segments as a system without a partner and 3 as a partner's system.
- In an installation, a first segment is either a partner or a system without a partner, never both: `acme` and
  `acme/erp` cannot both be defined. So `acme/erp` always means one thing to a reader, in a list as in a file.
- Two partners may each have a system of the same name (`acme/erp`, `globex/erp`). A system cannot later move under a
  partner without a new key; a key is never renamed in place.
- One parser of keys, in `rail8-core`, serves the node and the command line.

## The system file

A system is a file of `kind: system`, found by its kind anywhere in a package like every other file. The convention is
`systems/<name>.yaml`, or `systems/<partner>-<name>.yaml`. One system a file.

```json
{ "$id": "urn:rail8:schema:system:1", "type": "object", "additionalProperties": false,
  "required": ["schemaVersion", "kind", "name"],
  "properties": {
    "schemaVersion": { "const": 1 }, "kind": { "const": "system" },
    "partner": { "$ref": "#/$defs/segment", "description": "The partner the system belongs to; absent for a system without one." },
    "name": { "$ref": "#/$defs/segment" },
    "description": { "type": "string", "maxLength": 1000 },
    "connections": { "type": "object", "maxProperties": 64, "propertyNames": { "$ref": "#/$defs/segment" },
      "additionalProperties": { "type": "object", "required": ["adapter"], "additionalProperties": false,
        "properties": { "adapter": { "type": "string", "pattern": "^[a-z][a-z0-9+.-]{0,31}$" },
                        "description": { "type": "string", "maxLength": 1000 } } } } },
  "$defs": { "segment": { "type": "string", "pattern": "^(?!rail8$)[a-z][a-z0-9-]{0,63}$" } } }
```

```yaml
# examples/demo/systems/acme-erp.yaml: the domestic partner's ERP, called by the invoice flows
schemaVersion: 1
kind: system
partner: acme
name: erp
description: The domestic partner's ERP, which takes our invoices.
connections:
  rest: { adapter: http, description: Its invoice API. }
```

- `connections` may be empty: a system that only calls Rail8 over HTTP has none, and is still named as an allowed sender.
- A connection's adapter has a connection form: an adapter with none (`direct`, the test adapter `mock`) is refused.
- A partner is only the first segment of a key here. What a partner is beyond that (its profile, its identifiers, its
  agreements) belongs to the configuration of an environment and to partner communication.
- **Defined once.** A system key is defined by one file in the installation's deployed packages. A second file of the
  same key in another package is accepted only when it defines the same system: the same partner and name, and the same
  connections with the same adapters. Descriptions are not compared; the system shows the description of the package
  deployed last. This is how a system moves from one package to another without stopping the flows that use it: the
  new package deploys a copy, then the old one drops its file. The deploy that adds a copy says so as a note, naming the
  other package, and `GET /admin/systems` lists every package that holds a system. A different definition refuses the
  deploy, naming both packages.
- A deploy that drops a system or one of its connections, or changes a connection's adapter, while a deployed flow of
  any package still names it, is refused, naming those flows, unless another package still holds the same definition.
  An undeploy of a package is refused likewise.

## The flow's sender and receiver

A flow of `schemaVersion: 2` names systems' connections; `schemaVersion: 1` keeps naming endpoint files while the steps
land (see "Moving to the new shape"). The other keys of a flow and the other step types do not change.

```json
"from": { "type": "object", "required": ["adapter"], "additionalProperties": false,
  "properties": {
    "adapter":    { "$ref": "#/$defs/adapter" },
    "connection": { "$ref": "#/$defs/connectionKey" },
    "settings":   { "$ref": "#/$defs/settings" },
    "senders":    { "description": "Who may send: the place of the design of the entry, refused until it ships." } } },
"$defs": {
  "adapter": { "type": "string", "pattern": "^[a-z][a-z0-9+.-]{0,31}$" },
  "connectionKey": { "type": "string", "pattern": "^(?!rail8/)[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63}){1,2}$" },
  "settings": { "type": "object", "maxProperties": 128, "propertyNames": { "pattern": "^[a-z][a-zA-Z0-9]{0,63}$" },
                "additionalProperties": { "type": ["string", "integer", "boolean"] } } }
```

The `to` step:

```json
{ "if": { "properties": { "type": { "const": "to" } } },
  "then": { "required": ["adapter"],
    "properties": {
      "adapter": { "$ref": "#/$defs/adapter" },
      "connection": { "$ref": "#/$defs/connectionKey" },
      "by": { "type": "string", "maxLength": 1024, "description": "An expression whose value is a key of connections." },
      "connections": { "type": "object", "minProperties": 1, "maxProperties": 64,
        "propertyNames": { "pattern": "^[A-Za-z0-9_.-]{1,64}$" },
        "additionalProperties": { "$ref": "#/$defs/connectionKey" } },
      "agreement": { "description": "Reserved: a receiver by agreement, designed with partner communication; refused." },
      "settings": { "$ref": "#/$defs/settings" },
      "maxReplyBytes": { "type": "integer", "minimum": 1, "description": "The largest reply taken; the flow's limits.maxBodyBytes when absent." } },
    "oneOf": [ { "required": ["connection"] }, { "required": ["by", "connections"] }, { "required": ["agreement"] },
               { "not": { "anyOf": [ { "required": ["connection"] }, { "required": ["by"] },
                                     { "required": ["connections"] }, { "required": ["agreement"] } ] } } ] } }
```

- **By name**: `connection: acme/erp/rest`.
- **Computed**: `by` is an expression (see below) whose value is one of the keys of `connections`, each naming a
  connection, so the deploy knows every connection a step can reach. A value that is not a key of `connections` fails the
  send as invalid. Today's `byProp: PARTNER` is `by: ${prop.PARTNER}`. Settings that differ per receiver are expressions
  over a prop that a `route` step sets. A step that determines several receivers is the `receivers` step below.
- **By agreement**: `agreement` is reserved and refused until partner communication designs it.
- **No connection**: the last branch, only for the platform's internal adapters (`direct`).
- A sender or a send step names a connection exactly when its adapter's connection form has a field for that direction.
  The named connection's adapter in its system file must be the step's `adapter`.
- `settings` are the settings in the flow, checked against the adapter's flow form for the direction. A sender's are
  literal; a send step's may hold expressions in the fields the adapter marks.
- One adapter per send step. Receivers reached through different adapters are different send steps, or flows of their
  own called through `direct`.

```yaml
# examples/demo/flows/invoice-async.yaml, schemaVersion 2: only from and the last step differ from today's file
schemaVersion: 2
kind: flow
id: invoice-async
version: 0.2.0
from:
  adapter: http
  settings: { address: /invoice/async }        # served at /http/invoice/async; no connection: nobody is called
# delivery, recording, limits, partitionBy, forwardHeaders, retry and the steps validate, extractKey, route, toPartner unchanged
steps:
  - type: to
    name: partner
    retryable: true
    adapter: http
    by: "${prop.PARTNER}"                        # set by the route step: domestic or foreign
    connections:
      domestic: acme/erp/rest
      foreign: globex/erp/rest
    settings: { path: "/invoices/${key.invoiceId}" }
```

```yaml
# examples/demo/flows/sftp-invoice.yaml, schemaVersion 2
from:
  adapter: sftp
  connection: billing/sftp                       # a polling sender: the system Rail8 fetches from
  settings: { dir: /inbox, afterSuccess: delete }
steps:
  # validate, extractKey, toPartner unchanged
  - { type: to, name: partner, retryable: true, adapter: http, connection: acme/erp/rest, settings: { path: /invoices } }
```

A call between flows: the caller's step is `{ type: to, adapter: direct, settings: { address: /orders/partner } }` and the
callee's sender is `{ adapter: direct, settings: { address: /orders/partner } }`.

## Expressions in the settings in the flow

A text field of a send step's flow form that the adapter marks `.expression(Escape)` takes a template. Any other field is
literal, and a `${` in it is refused at load, since it is a template written where none is taken. `by` is a template too.

| Placeholder | Value |
| --- | --- |
| `${key.<name>}`, `${prop.<name>}`, `${header.<name>}` | The message's key, prop or header of that name |
| `${id}` | The message's id |
| `${date:<pattern>}` | The time the message was accepted, in UTC, formatted with a `DateTimeFormatter` pattern in the root locale |
| `$$` | A literal `$` |

- A template never reads the body. A value from the body is taken first by `extractKey` or `route` and read as a key or
  a prop, so the send is fast and what it reads is visible in the flow.
- `${date:…}` is the message's accepted time, never the clock at the send, so a retry after midnight writes the same name.
- The field's `Escape` says how a value goes into it. Every escape refuses an empty value and a value holding a control
  character (below U+0020, and U+007F). `PATH` percent-encodes every character but the unreserved ones of RFC 3986, so a
  value is one path segment, and refuses `.` and `..`. `QUERY` percent-encodes every character but the unreserved ones.
  `NAME` (a file or queue name) refuses a value holding `/`, `\` or NUL, or that is `.` or `..`. `NONE` leaves the value
  as it is. The literal parts of a template are as written, and checked by the adapter's field.
- Templates are compiled at load: an unknown placeholder or a bad date pattern refuses the flow. At most 1,024 characters
  and 16 placeholders a template, and 4,096 characters rendered.
- The platform renders the templates before each send and hands the result to the producer; a producer never renders.
  A placeholder with no value, a value its escape refuses, or a result over the limit fails the send with a fault of kind
  `INVALID`: a sync flow answers 422, an at-least-once flow parks the message, and it is not retried.

## Faults the caller sees

The faults these checks raise carry two texts. The caller of a sync flow gets the public one only ("the message is
invalid", "the receiver cannot be reached now"). The detail (the step, the field, the placeholder, the connection key, a
host, the environment) goes to the message's history, which only the admin API shows. Every refusal of the deploy and
every fault here carries a stable code, so the UI words it in the user's language.

## A whole address from the message

A whole address taken from the message goes only through a connection that allows it, with a list of allowed hosts.

- An adapter declares such a field with `Field.address(name, label, help, schemes...)` in its flow form, for the out
  direction. It takes only a template. The platform adds the field `allowedHosts` to that adapter's connection form, a
  list of text served in the description as the type `hosts`: at most 256 entries, each `<host>` or `<host>:<port>`, a
  host being an exact name or `*.<suffix>`, matched as written and never resolved; an IP literal matches only itself. An
  entry without a port allows only the scheme's default port.
- At each send the platform renders the field and parses it once into a `URI`: absolute, a scheme the field names, no
  user info, a host and port among the connection's allowed hosts. It hands that `URI` to the producer in the `Call`,
  which sends to it as it is and follows no redirect. A refused address fails the send as `INVALID`, so a hostile message
  is parked at once; once the host is added, a retry from the dead-letter queue goes through.
- The node limits the sends in flight through such a field by the connection's key, whatever host each one names.
- The deploy refuses a step that uses an address field through a connection whose `allowedHosts` is empty in the
  environment, and records the use as one with an address, so that the configuration of an environment refuses to empty
  the list while it is used.
- `allowedHosts` is an address under the secret store's rule: a connection that carries a secret and lists allowed hosts
  takes effect only with the secret administrator's consent.
- `http` gains `absoluteUrl` as such a field, which a step uses instead of `path`.

## The adapter's contract

`rail8-api` changes in one step, and every shipped adapter, the adapter template and the testkit follow in it.

```java
package dev.rail8.api;

public interface Component {
    String scheme();                     // the adapter type, as a flow and a system file name it: "http"
    default Forms forms() { return Forms.NONE; }          // replaces settings() and configSchema()
    Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler);
    Producer producer(Outbound out);
    Future<Health> health();
    default Optional<String> httpPrefix() { return Optional.empty(); }                          // as today
    default Optional<InetSocketAddress> listensOn(Inbound in) { return Optional.empty(); }
    default Optional<String> polledPlace(Inbound in) { return Optional.empty(); }   // replaces polls(): what it fetches from
    default Optional<String> target(Outbound out) { return Optional.empty(); }      // from the connection alone
    default Optional<String> internalAddress(Direction direction, Map<String, Object> settings) { return Optional.empty(); }
}

/** One connection as the environment holds it now: its values checked against the connection form, defaults filled in.
 *  values holds no secret; each secret field is a handle in secrets, read at each use. */
public record Connection(String key, Map<String, Object> values, Map<String, Secret> secrets) {
    public Optional<Secret> secret(String field) { return Optional.ofNullable(secrets.get(field)); }
}

/** One sender of a flow: its connection when its adapter has one for the in direction, its settings in the flow. */
public record Inbound(String flow, Optional<Connection> connection, Map<String, Object> settings, long maxBodyBytes) {}

/** What a producer is built for: one connection, or none for an internal adapter. */
public record Outbound(Optional<Connection> connection) {}

/** One send: the flow and step it is for, every setting in the flow with its templates rendered, the largest reply taken,
 *  and the checked address of an address field. */
public record Call(String flow, String step, Map<String, Object> settings, long maxReplyBytes, Optional<URI> address) {}

public interface Producer {
    Future<Envelope> send(Envelope e, Call call);
    default Future<Envelope> send(Envelope e, Call call, Deadline d) { /* refuses a passed deadline, as today */ }
    Future<Void> close();
}
```

```java
package dev.rail8.api.settings;

public record Forms(Settings connection, Settings flow) {
    public static final Forms NONE = new Forms(Settings.NONE, Settings.NONE);
    public static Forms of(Settings connection, Settings flow);      // checks the rules below, throws naming the field
    public boolean connects(Direction direction);                     // the connection form has a field for it
}
// Field: every factory takes a label before the help text
Field.text(name, label, help)   Field.secret(name, label, help)   Field.integer(name, label, help)
Field.bool(name, label, help)   Field.choice(name, label, help, Choice... choices)   Choice.of(value, label)
Field.address(name, label, help, String... schemes)
Field#expression(Escape escape)           // a flow text field of the out direction that takes a template
public enum Escape { PATH, QUERY, NAME, NONE }
Tab.of(id, label)                         // as today
```

- **Two forms.** The connection form and the flow form, each a `Settings` with fields marked `in`, `out` or both, so
  the four forms the UI draws are each place for each direction. `Forms.of` refuses, and so the adapter does not load:
  a field name used twice across both forms; a `secret` field in the flow form; `expression` or `address` in the
  connection form, on a field that is not text, or for the in direction; a `visibleWhen` or `exactlyOne` across the two
  forms; a field named `flow`, `maxBodyBytes`, `maxReplyBytes` or `allowedHosts`, which are the platform's; and a form
  that serves the out direction with no connection field, unless the adapter is one of the platform's internal ones. So
  a flow sends only through a connection.
- **Producers belong to connections.** The node builds one producer per connection, at the connection's version, and
  one per internal adapter. Every flow and step that sends through the connection shares it, so its pool is the
  connection's. A producer reads the flow's settings only from each `Call`.
- **A fresh one at any time.** The node may build a fresh consumer or producer of the same use at any time, and closes
  the old one once its sends settle, at the latest at their deadline. A consumer's successor starts only after it
  stopped. Building one opens nothing.
- **The polled place.** `polledPlace` names what a polling consumer fetches from, within its connection (for `sftp`, its
  directory and glob). The deploy refuses two flows whose senders poll the same connection and place, and the poller's
  lease is keyed by the connection's key and the place, never by a value of the connection.
- **The limit per target.** `target` names the system a producer calls (`scheme://host:port`) from its connection's
  values. When it is empty, the node limits the sends in flight by the connection's key.
- **The reply's limit** is the step's `maxReplyBytes`, else the flow's `limits.maxBodyBytes`, handed in each `Call`;
  `http`'s `maxBodyBytes` field goes. For a flow of `schemaVersion: 1`, an out endpoint's `maxBodyBytes` stays the
  reply's limit.
- **What connects is part of the contract.** Adding the first connection field for a direction makes every flow that uses
  the adapter in that direction name a connection: a breaking change of the adapter, named in its release notes.
- **Text keys.** Every field, choice and tab has a label, and every field a help text, in English in the code. Their
  keys are derived and never written: `<scheme>.field.<name>.label`, `<scheme>.field.<name>.help`,
  `<scheme>.field.<name>.choice.<value>`, `<scheme>.tab.<id>`; the platform's own fields use `rail8.` in place of the
  scheme. An adapter's jar may carry `META-INF/rail8/texts/<scheme>/<locale>.properties` with those keys, at most
  256 KiB each, served at `GET /admin/components/<scheme>/texts/<locale>` for a locale matching
  `^[a-z]{2,3}(-[A-Z]{2})?$` (404 for one it lacks; the UI then shows the English).
- **Problems carry codes.** A problem of `Settings.check` carries a stable code (`required`, `unknown`, `min`, `max`,
  `pattern`, `choice`) besides its English text.
- **The description** served at `GET /admin/components` becomes format 2: `{ "format": 2, "connects": { "in": …, "out": … },
  "connection": [fields], "flow": [fields] }`, each field with `label`, `labelKey`, `helpKey`, and `expression` or
  `address` where set. It still serves three readers: the deploy and `rail8 validate` check against it, the UI draws
  the forms from it, and the configuration of an environment checks a connection's values against its connection form.
- **Credentials a caller presents.** Which kinds of credential a consumer can read, and how it hands over what a caller
  presented, are added by the design of who may send to a flow. That design adds a second `consumer` method whose
  default delegates to this one and presents nothing, and a default method naming no kinds. An adapter built on this
  contract keeps compiling and reads no credential until it overrides them.

How the shipped adapters split:

| Adapter | Connection form | Flow form |
| --- | --- | --- |
| `http` in | none | `address` (the path it serves) |
| `http` out | `url` (the base: scheme, host, port, a path prefix; no user info), `timeoutMs`, `poolSize` | `path` (expression, `PATH`; its literal start is `/`), later `absoluteUrl` |
| `sftp` in | `host`, `port`, `user`, `password`, `privateKey`, `hostKey` (a secret field, so a generated fingerprint is named), `connectTimeoutMs`, `ioTimeoutMs` | `dir`, `glob`, `intervalMs`, `maxFilesPerPoll`, `minAgeMs`, `afterSuccess`, `moveTo`, `errorDir` |
| `direct` in and out | none (internal) | `address` |

`http` joins `url` and `path` itself and refuses, as `INVALID`, a result whose scheme, host or port differ from `url`'s.

The testkit follows. `ComponentContract` and `PollingConsumerContract` take a `Connection` and settings, and check: two
different `Call`s through one producer each go out with their own settings; two producers of one connection work side by
side, and closing the first does not break the second; a consumer's successor starts only after it stopped; every field
has a label; and the rules of `Forms.of`, against a fake adapter that breaks each one. `RecordingComponent`, the `mock`
adapter and the template of `rail8 new-adapter` (`examples/webhook-plugin`) are rewritten on the contract, and the
template's package gets a system file and a flow of `schemaVersion: 2`.

## What the node relies on from the environment

The node reads the environment through one interface inside `rail8-runtime`. Adapters never see it.

```java
interface Environment {
    String name();                         // RAIL8_ENV: dev, test, prod
    Snapshot current();                    // never blocks; the state the node holds in memory
    void onActivation(java.util.function.Consumer<Snapshot> listener);   // once per activation, in order
}
interface Snapshot {
    long generation();                     // raised by every activation; the deploy's record names it
    boolean mocksAllowed();
    Optional<ConnectionState> connection(String connectionKey);
    Optional<String> instanceName(String systemKey);
}
sealed interface ConnectionState permits Values, Mocked {
    String adapter();
    long version();                        // raised only when this connection's state changes
    boolean stopped();                     // stopped by a connection administrator
}
record Values(String adapter, long version, Map<String, Object> values, boolean stopped) implements ConnectionState {}   // a secret field: its name
record Mocked(String adapter, long version, MockBehaviour behaviour, boolean stopped) implements ConnectionState {}
record MockBehaviour(Reply reply, Duration delay) { enum Reply { ECHO, EMPTY } }
```

A send reads `current()` once and uses that snapshot throughout. The testkit ships `InMemoryEnvironment`, which a test
fills and activates by hand.

## What the node does

### At a deploy

Under the pool's deploy lock, with the checks of today, every problem a line of one 422 with its code:

1. **Systems**: each file against its schema; the key rules above against the installation's deployed packages and this
   deploy; every adapter known to the node and with a connection form; a drop or a change of a used system or
   connection, as above.
2. **Flows**: the sender and every send step: the adapter known; the connection named exactly when the adapter connects
   for that direction; every key in `connection` and `connections` defined by this deploy or a deployed package, with the
   step's adapter; the settings checked against the flow form for the direction; templates compiled, only in the fields
   that take them; no two senders on one connection and polled place; internal addresses and cycles as today.
3. **The environment**, in the state the configuration holds when the deploy runs: every connection a flow can reach is
   set for its adapter (values that pass its connection form for every direction it is used in, with every secret they
   name set and of a kind its field takes), or mocked where mocks are allowed; every system with a used connection has
   an instance name; a step that uses an address field has allowed hosts. Each line names the flow, the step, the
   connection key, the field and the environment, never a value.
4. **The record**: the deployment names the generation it checked against, and the table `deployment_connection_uses`
   (deployment, flow, step, connection key, adapter, direction, whether through an address field) records every
   connection a flow can reach, in the transaction that writes the record. In that transaction the deploy checks the
   used connections again, under the lock that every activation also takes, as the secret store does for secrets.
5. **The hash** of a flow covers its file and the files it reads, as today. No system file and no value of the environment
   is in it, so a connection's change restarts no flow and needs no new lock.

Only the node that deploys checks the environment. A node that installs a record, at a follow or at its start, does not
check it again: a problem there shows as below. Before it installs any package, a node reads the system files of every
deployed record, so a flow finds the systems of another package whatever order the packages install in, and whatever
state each one is in. A restore checks the secrets the flows themselves name, as the secret store says; a missing
secret of a connection is a connection that is not set.

### At each send

1. The step picks its connection key: the named one, or the value of `by` among `connections`.
2. It renders the step's settings, and an address field, into a `Call`; a template or an address that fails, fails the
   send as `INVALID`.
3. It reads the connection's state from the one snapshot. Absent or `stopped` holds the send, below. `Mocked` sends
   through the platform's mock. `Values` goes on.
4. It takes the connection's producer at its version, building it when there is none, and sends with the deadline under
   the limit of its target.
5. The send step's entry in the message's history names the connection key and whether it was mocked, never a value. It
   hands the receiver's facts (the connection key, the system key, the partner, the system's instance name as it held
   then, whether it was mocked) to the record that the design of who may send to a flow writes on the message.

**A held send.** A connection that is stopped, or not set, is not the receiver's fault and does not use up the flow's
retries. An at-least-once message is scheduled again every 30 s without counting an attempt, for at most 24 hours; then
it is parked, naming the connection. A sync flow answers 503 at once. An at-most-once flow fails the send. The flow is
`DEGRADED` in `/health` while it holds sends.

### When the environment changes

`onActivation` names a new generation. The next send through a connection whose version changed builds a new producer
from the new values, and the old one closes once its sends settle: every later attempt, a retry waiting in the queue
included, goes out with the new values. A connection whose version did not change keeps its producer. At most 4 old
producers of one connection close at once; a send that would need a fifth waits for the oldest to close. A sender whose
connection changed is stopped and started again with a new `Inbound`, without backoff. Switching to the mock and back is a
change like any other. A secret that a connection names is not a change of the connection: its handle is read at each
use, so a new value reaches the adapter at its next login, session or handshake, and nothing is built again.

### When a connection or a system is removed

- **From git**: refused while a deployed flow names it, as above.
- **From the environment**: the configuration of an environment refuses it while `deployment_connection_uses` names it,
  unless the connection administrator confirms. Once confirmed, every send through it is held, as above. A polling
  sender whose connection is missing or stopped is not built: its flow runs, takes nothing, and is `DEGRADED` in
  `/health`. Today a sender that cannot be built fails its flow until the next deploy; here, the activation that sets the
  connection again builds the sender and starts it. A node that starts meanwhile does the same.
- **An instance name**: the configuration refuses to remove the instance name of a system whose connections are used,
  unless confirmed. Once removed, the receiver's facts say it is not set.

## A system of another package

A flow names a connection by its key against what is deployed in the installation. It needs no dependency on the package
that defines the system, and nothing about it goes into `package.lock`: a connection holds nothing in git that could be
pinned to a commit, and its values change in the environment without a new lock or a new deploy of the flows that use it.

- The package that defines a system deploys it, flows or none: a package of systems alone is a package like any other.
  It must be deployed before a flow that names its connections; that deploy is refused otherwise, naming the key.
- Two packages whose flows each name the other's systems cannot be deployed one before the other. Their systems go to a
  package of their own, deployed first.
- A system file inside a dependency is not deployed by the package that depends on it. `rail8 validate` reads it to
  check offline; the deploy reads only what is deployed.
- A team may keep systems beside its flows, in one package for all of them, or in a package per system; a system moves
  between them by the copy rule above.

## `rail8 validate` and `rail8 test`

Neither needs an environment.

- **`rail8 validate`** checks the schemas, the keys, each use's adapter against the node's adapters and the plugins the
  package names, the settings against each flow form, the templates, and each named connection key against the system
  files of the package and of its locked dependencies, with the step's adapter. A key it finds in neither is a note, not
  a problem: "acme/erp/rest: not defined in this package or its dependencies; checked at deploy". It never reads the
  values of a connection.
- **`rail8 test`** replaces every connection by the mock of its adapter, as it replaces every endpoint today. A test of
  `schemaVersion: 2` keys `mocks` and `expect.sent` by connection key, and a call of another flow by `direct:<address>`;
  the mock's behaviours do not change. `expect.sent.<key>.settings` checks the rendered settings of each send.

```yaml
# examples/demo/flows/sftp-invoice.test.yaml, schemaVersion 2
mocks:
  billing/sftp: { files: fixtures/inbox/*.xml }
  acme/erp/rest: { reply: fixtures/partner-ack.xml, delay: 20ms }
cases:
  - name: invoice file
    expect:
      sent:
        acme/erp/rest: { count: 1, settings: { path: /invoices }, xpath: /PartnerInvoice/@number, equals: "4711" }
```

## The built-in mock of a connection

A connection can be switched to a mock in an environment, in place of its values: a partner whose test system is down,
or a new flow tried before the partner's side exists.

- The mock is the platform's, not the adapter's, so every adapter has it. Its behaviour: `reply` (`echo`, the body sent,
  or `empty`) and `delay` (at most 60 s). It sets none of the adapter's own props on the reply, so a step that reads one
  sees none.
- It runs after the settings are rendered and the address is checked, so a mocked flow shows the same faults as a real
  one.
- A mocked polling sender takes nothing; its flow runs, and `/health` says the flow has a mocked sender.
- An environment allows mocks or not, and one that does not refuses them. The configuration of an environment decides
  which do, and asks the same confirmation to switch a used connection to the mock as to remove it, since a mocked send
  completes the message without reaching the partner. The quickstart's environment file allows them.
- A send through the mock is marked in the message's history, and `/health` counts the mocked connections;
  `GET /admin/systems` names them with the flows that use them.

## Several receivers

Today the engine does not deliver to several receivers each on its own. A `to` step sends to exactly one endpoint;
`route` with `byProp` picks one. `split` runs its parts in the parent's run, so one part that fails fails them all, and a
retry repeats the parts already delivered. A `direct` call to an at-least-once flow saves a work item in that flow with
its own retries, but the call is made inside the caller's run: when a second call fails, the caller's retry makes the first
again.

What is missing is a **work item that starts in the middle of a flow**: written in the transaction that ends the
parent's run, holding the parent's envelope and the receiver it is for, with its own retries, dead-letter row, history
and keys, shown in Monitor under its parent.

The `receivers` step fills it:

```yaml
- type: receivers
  name: receivers
  prop: RECEIVER
  rules:
    - { when: "/Invoice/Currency = 'CZK'", add: domestic }
    - { when: "/Invoice/Total > 100000", add: audit }
  otherwise: foreign                 # when no rule holds; without it, no receiver parks the message as invalid
- { type: to, name: partner, adapter: http, by: "${prop.RECEIVER}",
    connections: { domestic: acme/erp/rest, foreign: globex/erp/rest, audit: auditor/archive/rest } }
```

- Every rule that holds adds its value, at most 16; the steps after `receivers` run once for each value, as a work item
  of its own with `prop` set to it. A value added twice runs once.
- `when` reads the body as `route`'s `when` does.
- Only an at-least-once flow takes it: a sync flow has one reply, an at-most-once flow no work items. The deploy refuses it
  elsewhere.
- How the children are numbered, retried, parked and shown is the design of that step.

## Until the environment's store: the environment file

The store of the configuration of an environment is built in its own steps. Until it is, the node reads the environment
from a file, so the demo and the quickstart work from the second step on:

```yaml
# deploy/quickstart/environment.yaml; the quickstart copies it once to ~/rail8-workspace/environment.yaml
schemaVersion: 1
mocksAllowed: true
connections:
  acme/erp/rest:   { adapter: http, values: { url: "http://localhost:8090" } }
  globex/erp/rest: { adapter: http, values: { url: "http://localhost:8090" } }
  billing/sftp:    { adapter: sftp, values: { host: sftp, port: 22, user: demo, password: sftp-password,
                                              hostKey: sftp-host-key } }
instanceNames:
  acme/erp: ACME-ERP-DEV
  globex/erp: GLOBEX-ERP-DEV
  billing: BILLING-DEV
```

- `RAIL8_ENVIRONMENT_FILE` names it. The quickstart keeps it beside the packages in `~/rail8-workspace`, the directory
  `docker-compose.yml` already mounts, so it is no tracked file of the repository and a new system needs no change there.
- The node reads it at its start and again at each 5 s tick when its size or time changed. A read that parses and passes
  each connection form is an activation: the generation goes up, and so does the version of each connection that
  changed. A read that fails keeps the previous state, with a WARNING naming the line. The file is at most 1 MiB.
  Without the variable the environment is empty, and a deploy that names a connection is refused, naming the variable.
- It belongs to the node, like its other settings, and whoever writes it is the node's operator. A secret field holds the
  secret's name, as in the store of the configuration; every other field holds its value. The SFTP host key's
  fingerprint is a secret of the node's environment, as today, so the stack's wrapper that sets it stays.
- Each node writes the digest of its file into its row of the nodes table, and a deploy is refused while the live nodes'
  digests differ, naming them.
- The deploy records the secrets that the file's connections name as uses of the flows that reach them, as the secret
  store records an endpoint file's, so a secret in use is not deleted unasked.
- When the store is built, it replaces this file as the source of `Environment`; whether the file stays as a seed of an
  empty store is decided there.

## Moving to the new shape

The node reads both shapes while the steps land, and the last step removes the old one.

- A flow of `schemaVersion: 1` names endpoint files as today. Inside the node each endpoint becomes one use of the new
  contract: its `config`, with the override of `RAIL8_ENV`, is split by the adapter's forms into a `Connection` of its
  own and the settings in the flow. Its deploy checks and its hash do not change, it does not touch the environment, and
  its secrets follow the secret store's rules for endpoint files.
- A flow of `schemaVersion: 2` names connections as this document says. A package may hold both while it moves; a flow of
  one shape cannot name what belongs to the other.
- Once the demo, the template and the documents are on the new shape, the last step removes `kind: endpoint`,
  `endpoint.schema.json`, flows of `schemaVersion: 1` and the Endpoints screen. A package that still holds them is refused,
  naming the file and what replaces it. A package of one's own moves by hand, as the demo's change shows: each endpoint
  file becomes a connection of a system and the connection's values in the environment file.

`examples/demo` gets `systems/acme-erp.yaml`, `systems/globex-erp.yaml` and `systems/billing.yaml`; its flows and tests
move to `schemaVersion: 2` as above; its endpoint files go once the flow editor writes the new shape. The quickstart gets
`deploy/quickstart/environment.yaml`, copied once into `~/rail8-workspace`, and `docker-compose.yml` sets
`RAIL8_ENVIRONMENT_FILE`.

## Importing from SAP

A tool that imports from SAP needs these from the format, and nothing else:

- A **communication channel of SAP PO** becomes a connection: its party and communication component give the system key
  (`acme/edi`, or `eshop` without a party), its name the connection's name, its adapter type an adapter. Its attributes
  are split by the adapter's description at `GET /admin/components`, so the tool hard-codes no split: the connection's go
  to the environment of each source system (development, quality, production), a password as the name of a secret left
  to fill; the flow's go to the flow made from the integrated configuration or receiver agreement that uses the channel.
- An **adapter configured inside an integration flow of SAP Integration Suite** becomes a system with a connection, named
  after the flow's participant; its connection's settings and externalized parameters go to the environment, and its
  settings in the flow stay in the flow.
- **Variable substitution** and dynamic configuration become templates: a value read from the payload becomes an
  `extractKey` before the send and a `${key.…}` in the field.
- The SLD's technical system becomes the system's instance name in each environment.
- SAP's names are folded to segments (`BS_ERP_100` to `bs-erp-100`) and kept in the description.

## The line with the configuration of an environment

That design owns the store outside git: drafts and activation per object, history, how every node learns of an
activation within seconds without a restart, the admin API, the export and import format, the `rail8` command, the roles
Connection administrator and Partner administrator, and partner profiles.

This design relies on it for:
- the `Environment` interface above, implemented over its store, with `onActivation` called on every node and a version
  per connection;
- holding, per connection key, its adapter and its values or the mock switch and whether it is stopped, and per system key
  its instance name;
- checking a connection's values against the adapter's connection form (format 2) for the directions
  `deployment_connection_uses` names, before an activation;
- refusing, unless confirmed, to remove the values of a connection that `deployment_connection_uses` names, to switch it to
  the mock, to empty its allowed hosts while a use goes through an address field, and to remove the instance name of a
  system whose connections are used;
- a lock that every activation takes and the deploy takes in its record's transaction, so a deploy's check and an
  activation never cross;
- deciding which environments allow mocks, and how the deploy's record names the generation.

This design gives it the system and connection keys, `GET /admin/systems` (every deployed system with the packages that
hold it, its connections, their adapters, descriptions, the flows that can reach each, and each one's state in this
environment: set, mocked, stopped or missing, never a value), and `deployment_connection_uses`.

## The line with who may send to a flow

That design owns credentials of a system, the check at the entry, Allowed senders, the switch and the limit per caller in
the flow, test messages by the Message sender, and writing the sender and the receivers (partner, system, instance name)
on every message.

This design leaves it:
- the key `senders` in a flow's `from`, refused until it ships;
- the place in `Component` for the kinds of credential a consumer reads and a second `consumer` method that hands over
  what a caller presented, each with a default, so adapters built on this contract keep compiling;
- the receiver's facts at each send (connection key, system key, partner, instance name, mocked), and for a polling
  sender its connection's system as the sender.

## The line with the secret store

That design owns secrets: their kinds, the store, `Secret` handles read at each use, and `secret://<name>` references,
which do not change.

This design relies on it for:
- a connection naming a secret only from a secret field, by the secret's name and with the kinds the field takes, and
  holding no value; the node builds each `Connection` with a handle per secret field, so a change of a secret rebuilds
  nothing;
- the deploy's check that every secret a used connection names is set and of a kind its field takes;
- the record of uses, which the configuration of an environment writes for a connection when it activates it, and which
  lists, for a connection, the deployed flows that name it, from `deployment_connection_uses`;
- its rule that a setting pairing a secret with an address, a proxy or a trust it was not paired with before takes
  effect only with the secret administrator's consent. A connection's `allowedHosts` are such an address.

The handles of a connection's secret fields join the scrubbing of the failures of every flow that uses it, as an
endpoint's do today. A flow's own `secret://` references in its steps' options do not change.

## What this replaces

- The endpoint file (`kind: endpoint`, `endpoint.schema.json`) and the rule that an adapter's form is saved into it.
- Differences between environments as overrides in files (`environments:` in an endpoint), for connections.
- `from.endpoint`, `to.endpoint`, `to.byProp` and `to.endpoints` in `flow.schema.json`.
- `EndpointConfig`, `Component.settings()`, `Component.configSchema()`, `Component.polls()` and
  `Producer.send(Envelope)` in `rail8-api`.
- The Endpoints screen and the endpoint form, which become the Systems screen under Configure.
- In the documents: `docs/ARCHITECTURE.md` (packages and files, components, the UI), `docs/ADAPTERS.md`,
  `docs/FLOW-EDITOR.md`, `docs/PACKAGES-AND-GIT.md` (the lists of artifacts, an endpoint by its settings),
  `docs/QUICKSTART.md` and `docs/UI-PATTERNS.md` where they name endpoints. Each step rewrites those its change touches.

## Limits

| What | Limit |
| --- | --- |
| A key's segment | 64 characters |
| Connections of a system | 64 |
| Systems of a package; of an installation | 1,000; 4,096 |
| Choices of a send step's `connections` | 64 |
| Settings of a sender or a step | 128 |
| A template | 1,024 characters, 16 placeholders; 4,096 characters rendered |
| Receivers a `receivers` step adds | 16 |
| `allowedHosts` of a connection | 256 entries |
| A held send | again every 30 s, for at most 24 hours |
| Old producers of one connection closing at once | 4 |
| The mock's delay | 60 s |
| A bundle of texts | 256 KiB |
| The environment file | 1 MiB |

## Tests

- Keys: the segment rule, `rail8`, the first-segment rule across packages, 2 and 3 segments read apart.
- System files: schema; an adapter without a connection form refused; the same definition in two packages accepted with
  a note, a different one refused naming both; a drop, an adapter change and an undeploy of a used system refused
  naming the flows; a move between packages without a stop; two packages naming each other's systems.
- Flows: each branch of `to`; a connection named where the adapter has none, and missing where it has one; a key of
  another adapter; settings against the flow form; a `${` in a literal field; a template that does not compile; two
  senders on one connection and polled place.
- Templates: each placeholder, each `Escape` with values that must be encoded or refused (empty, control characters,
  `.`, `..`, `/`, `?`, `#`, `@`), the limits, a missing value as `INVALID` (422 sync, parked at least once, never
  retried), `${date:…}` the same on a retry after midnight.
- `http`: a `path` that would change the host refused; `url` with user info refused; the public text to a sync caller
  and the detail in the history only.
- Deploy against `InMemoryEnvironment`: an unset connection, one set for another adapter, incomplete values, an unset
  secret, a mock where mocks are not allowed, a missing instance name, each a line with its code naming flow, step, key,
  field and environment and never a value; the record names the generation; `deployment_connection_uses` holds every
  connection a computed step can reach; an activation between the check and the record; the hash unchanged by a change
  of values.
- Install: a flows package installed before its systems package; a follower that does not check the environment again.
- Activation: a retry waiting in the queue goes out with the new values; only a changed connection's producer is built
  again; old producers close once their sends settle; a polling sender restarts with the new values and never polls
  twice at once; mock and back.
- Held sends: a stopped connection holds an at-least-once message without counting attempts and parks it after 24 hours
  on the fake clock; sync answers 503; a polling sender is `DEGRADED` and recovers at the activation that sets it.
- The environment file: re-read when changed, a bad read keeping the previous state, two nodes with different files.
- `rail8 validate` offline with a key from a dependency, and the note for an unknown one; `rail8 test` with keyed mocks
  and `expect.sent.<key>.settings`.
- The contract suite on every shipped adapter, the template and a fake adapter that breaks each rule of `Forms.of`.
- Both shapes in one package; the demo's tests on both shapes while they move.

## Steps

Each step is one issue and one pull request, keeps the demo package and the quickstart working, and rewrites the
documents its change touches. By the end of the fourth, "draw a flow, deploy it, send a message, see it in Monitor" runs
on the new model, and from the second on the owner sees a flow of the new shape run. The owner tries each one by hand on
Windows with WSL. Each says whether it changes `examples/demo`, the quickstart or the database; a step that changes
`examples/demo` says which files to copy again into `~/rail8-workspace/demo` and commit there.

1. **The adapter's contract.** `Forms`, labels, text keys and codes, `Connection`, `Inbound`, `Outbound`, `Call`,
   `Producer.send(e, call)`, `polledPlace`; `http` (with `path`, and `url` refusing user info), `sftp` (with `hostKey` a
   secret field), `direct`, `mock`, the template, `ComponentContract`, `PollingConsumerContract` and
   `RecordingComponent` on it; endpoint files read through it; format 2 and the texts route. The endpoint form shows the
   connection's fields and the flow's in two groups. The secret store's step for credentials and key pairs in adapters
   changes `Field` and the adapter's config too: the two never run at once, and whichever starts second starts after the
   first is merged and builds on its types. No change to the demo, the quickstart or the database.
2. **Systems and flows by name.** `system.schema.json`, flows and tests of `schemaVersion: 2` with a sender and a send
   step by name, the key rules, `Environment` with the environment file and `InMemoryEnvironment`, the deploy's checks,
   `deployment_connection_uses`, `GET /admin/systems`, the producers per connection and their swap, held sends, the
   connection key in the history, `rail8 validate` and `rail8 test`. The Flows screen draws a flow of either shape and
   edits only the old one. Changes `examples/demo` (its systems; `sftp-invoice` on the new shape, which the quickstart
   does not edit), the quickstart (`environment.yaml`, copied once; `docker-compose.yml`) and the database (a new table).
3. **Templates and computed receivers.** Expressions in the settings in the flow, `by` with `connections`, the public
   texts of faults, `expect.sent.<key>.settings`. Changes `examples/demo` (`invoice-async` on the new shape). The
   quickstart's `invoice-sync`, which its reader edits, stays on the old shape until the editor writes the new one. No
   change to the database.
4. **The flow editor on the new model.** The sender and the send step with the adapter, the connection picked from the
   systems of the workspace and the deployed ones, the flow form; New flow and Call a flow write the new shape; New system
   writes a system file. Changes `examples/demo` (`invoice-sync`, `order-jq` and `order-partner` on the new shape, its
   endpoint files gone), the template's package, and the quickstart's text. No change to the database.
5. **The Systems screen under Configure.** Every system with the packages that hold it, its connections, their adapters,
   the flows that use each and whether it is set, mocked, stopped or missing in the environment, and the instance names;
   editing the values comes with the environment's admin API. Replaces the Endpoints screen's place. No change to the
   demo, the quickstart or the database.
6. **The built-in mock of a connection.** The mocked state and `mocksAllowed` in the environment file, the platform's
   mock, its mark in the history and in `/health`. Changes the quickstart's environment file (the foreign partner
   mocked). No change to the demo or the database.
7. **Several receivers.** The `receivers` step and the work items it starts; its own design first. Changes the database;
   `examples/demo` gains a flow that uses it.
8. **A whole address from the message.** `Field.address`, `allowedHosts`, the platform's check, `http`'s `absoluteUrl`.
   No change to the demo, the quickstart or the database.
9. **The endpoint leaves Rail8.** `kind: endpoint`, `endpoint.schema.json`, flows of `schemaVersion: 1`, the Endpoints
   screen and the words in the documents go. Changes `examples/demo` only if anything of the old shape is left; no change
   to the quickstart or the database.
