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
  agreement (reserved), and holds the settings in the flow, some of which may be expressions.
- A call between flows of one node stays the internal adapter `direct`, with no connection and no system.

## Keys

A key is made of segments: `^[a-z][a-z0-9-]{0,63}$`, never `rail8` as the first one.

- A **system key** is `<name>` or `<partner>/<name>`; a **connection key** is `<system key>/<connection>`. A field that
  takes a connection key reads 2 segments as a system without a partner and 3 as a partner's system.
- In an installation, a first segment is either a partner or a system without a partner, never both: `acme` and
  `acme/erp` cannot both be defined. So `acme/erp` always means one thing to a reader.
- Two partners may each have a system of the same name (`acme/erp`, `globex/erp`). A system cannot later move under a
  partner without a new key; a key is never renamed in place.

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
- A partner is only the first segment of a key here. What a partner is beyond that (its profile, its identifiers, its
  agreements) belongs to the configuration of an environment and to partner communication.
- **Defined once.** A system key is defined by one file in the installation's deployed packages. A second file of the
  same key in another package is accepted only when it is the same definition (the same partner, name, description and
  connections, compared as parsed): this is how a system moves from one package to another without stopping the flows
  that use it, and the system stays defined while any package holds it. A different second definition refuses the
  deploy, naming both packages.
- A deploy that drops a system or one of its connections, or changes a connection's adapter, while a deployed flow of
  any package still names it, is refused, naming those flows. An undeploy of a package is refused likewise, unless
  another package holds the same definition.

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
        "additionalProperties": { "oneOf": [ { "$ref": "#/$defs/connectionKey" },
          { "type": "object", "required": ["connection"], "additionalProperties": false,
            "properties": { "connection": { "$ref": "#/$defs/connectionKey" }, "settings": { "$ref": "#/$defs/settings" } } } ] } },
      "agreement": { "description": "Reserved: a receiver by agreement, designed with partner communication; refused." },
      "settings": { "$ref": "#/$defs/settings" } },
    "oneOf": [ { "required": ["connection"] }, { "required": ["by", "connections"] }, { "required": ["agreement"] },
               { "not": { "anyOf": [ { "required": ["connection"] }, { "required": ["by"] },
                                     { "required": ["connections"] }, { "required": ["agreement"] } ] } } ] } }
```

- **By name**: `connection: acme/erp/rest`.
- **Computed**: `by` is an expression (see below) whose value is one of the keys of `connections`, each naming a
  connection, optionally with settings of its own that replace the step's settings of the same name. The deploy knows
  every connection a step can reach. A value that is not a key of `connections` fails the send as invalid. Today's
  `byProp: PARTNER` is `by: ${prop.PARTNER}`. A step that determines several receivers is the `receivers` step below.
- **By agreement**: `agreement` is reserved and refused until partner communication designs it.
- **No connection**: the last branch, for an adapter that has no connection in that direction (`direct`).
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
literal, and a `${` in it is refused at load. `by` is a template too, rendered as it is.

| Placeholder | Value |
| --- | --- |
| `${key.<name>}`, `${prop.<name>}`, `${header.<name>}` | The message's key, prop or header of that name |
| `${id}`, `${flow}` | The message's id; the flow's qualified id |
| `${date:<pattern>}` | The node's injected clock in UTC, formatted with a `DateTimeFormatter` pattern |
| `$$` | A literal `$` |

- A template never reads the body. A value from the body is taken first by `extractKey` or `route` and read as a key or
  a prop, so the send is fast and what it reads is visible in the flow.
- The field's `Escape` says how a value goes into it: `PATH` (percent-encoded as one path segment), `QUERY` (as a query
  value), `NAME` (a file or queue name: a value holding `/`, `\` or NUL, or that is `.` or `..`, is refused), `NONE` (as
  it is). The literal parts of a template are as written.
- Templates are compiled at load: an unknown placeholder or a bad date pattern refuses the flow. At most 1,024 characters
  and 16 placeholders a template, and 4,096 characters rendered.
- The platform renders the templates before each send and hands the result to the producer; a producer never renders.
  A placeholder with no value, a value `NAME` refuses, or a result over the limit fails the send with a fault of kind
  `INVALID` naming the step, the field and the placeholder: a sync flow answers 422, an at-least-once flow parks the
  message, and it is not retried.

## A whole address from the message

A whole address taken from the message goes only through a connection that allows it, with a list of allowed hosts.

- An adapter declares such a field with `Field.address(name, label, help, schemes...)` in its flow form, for the out
  direction. It takes only a template. The platform adds the field `allowedHosts` to that adapter's connection form: at
  most 256 entries, each an exact host name or `*.<suffix>`, matched as written and never resolved; an IP literal matches
  only itself.
- At each send the platform renders the field and checks it: an absolute URI, a scheme the field names, no user info, a
  host among the connection's allowed hosts. A refused address fails the send as `INVALID`, naming the host and the
  connection, so a hostile message is parked at once; once the host is added, a retry from the dead-letter queue goes
  through.
- The deploy refuses a step that uses an address field through a connection whose `allowedHosts` is empty in the
  environment.
- `http` gains `absoluteUrl` as such a field, which a step uses instead of `path`. The node limits sends in flight by the
  checked address's `scheme://host:port`.

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
    default boolean polls(Inbound in) { return false; }
    default Optional<String> target(Outbound out) { return Optional.empty(); }   // from the connection alone
    default Optional<String> internalAddress(Direction direction, Map<String, Object> settings) { return Optional.empty(); }
}

/** One connection as the environment holds it now: its values checked against the connection form, defaults filled in. */
public record Connection(String key, Map<String, Object> values, Map<String, Secret> secrets) {
    public Optional<Secret> secret(String field) { return Optional.ofNullable(secrets.get(field)); }
}

/** One sender of a flow: its connection when its adapter has one for the in direction, its settings in the flow. */
public record Inbound(String flow, Optional<Connection> connection, Map<String, Object> settings, long maxBodyBytes) {}

/** One send step of a flow and one connection it can reach. maxBodyBytes: the largest reply taken, the flow's limit. */
public record Outbound(String flow, String step, Optional<Connection> connection, long maxBodyBytes) {}

/** One send: every setting in the flow of the step, its templates rendered for this message. */
public record Call(Map<String, Object> settings) {}

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
Tab.of(id, label)
```

- **Two forms.** The connection form and the flow form, each a `Settings` with fields marked `in`, `out` or both, so
  the four forms the UI draws are each place for each direction. `Forms.of` refuses, and so the adapter does not load:
  a field name used twice across both forms; a `secret` field in the flow form; `expression` or `address` in the
  connection form, on a field that is not text, or for the in direction; a `visibleWhen` or `exactlyOne` across the two
  forms; a field named `flow`, `maxBodyBytes` or `allowedHosts`, which are the platform's.
- **What each object sees.** A consumer reads its connection and settings only from its `Inbound`, a producer its
  connection only from its `Outbound` and its settings only from each `Call`. The node may build a fresh consumer or
  producer of the same use at any time, and closes the old one once its sends settle; two producers of one use may live
  side by side meanwhile, and a consumer's successor starts only after it stopped. Building one opens nothing.
- **The limit per target.** `target` names the system a producer calls (`scheme://host:port`) from its connection's
  values. When it is empty, the node limits the sends in flight by the connection's key.
- **The reply's limit** is the flow's `limits.maxBodyBytes`, handed in `Outbound`; `http`'s `maxBodyBytes` field goes.
  For a flow of `schemaVersion: 1`, an out endpoint's `maxBodyBytes` stays the reply's limit.
- **Text keys.** Every field, choice and tab has a label, and every field a help text, in English in the code. Their
  keys are derived and never written: `<scheme>.field.<name>.label`, `<scheme>.field.<name>.help`,
  `<scheme>.field.<name>.choice.<value>`, `<scheme>.tab.<id>`; the platform's own fields use `rail8.` in place of the
  scheme. An adapter's jar may carry `META-INF/rail8/texts/<scheme>/<locale>.properties` with those keys, served at
  `GET /admin/components/<scheme>/texts/<locale>` (404 for a locale it lacks; the UI then shows the English).
- **The description** served at `GET /admin/components` becomes format 2: `{ "format": 2, "connects": { "in": …, "out": … },
  "connection": [fields], "flow": [fields] }`, each field with `label`, `labelKey`, `helpKey`, and `expression` or
  `address` where set. It still serves three readers: the deploy and `rail8 validate` check against it, the UI draws
  the forms from it, and the configuration of an environment checks a connection's values against its connection form.
- **Credentials a caller presents.** Which kinds of credential a consumer can read, and how it hands over what a caller
  presented, are added to `Component` and `Consumer` by the design of who may send to a flow. This contract reserves
  nothing more than that place: those additions arrive as default methods or an overload, so an adapter built on this
  contract keeps compiling.

How the shipped adapters split:

| Adapter | Connection form | Flow form |
| --- | --- | --- |
| `http` in | none | `address` (the path it serves) |
| `http` out | `url` (the base, `scheme://host:port` and a path prefix), `timeoutMs`, `poolSize` | `path` (expression, `PATH`; appended to `url`), later `absoluteUrl` |
| `sftp` in | `host`, `port`, `user`, `password`, `privateKey`, `hostKey`, `connectTimeoutMs`, `ioTimeoutMs` | `dir`, `glob`, `intervalMs`, `maxFilesPerPoll`, `minAgeMs`, `afterSuccess`, `moveTo`, `errorDir` |
| `direct` in and out | none | `address` |

The testkit follows: `ComponentContract` and `PollingConsumerContract` take a `Connection` and settings; they check that
a producer reads nothing but its `Outbound` and `Call` (a second producer of the same use beside the first works, and
closing the first does not break the second), that a consumer's successor starts only after it stopped, and that every
field has a label. `RecordingComponent`, the `mock` adapter and the template of `rail8 new-adapter`
(`examples/webhook-plugin`) are rewritten on the contract, and the template's package gets a system file and a flow of
`schemaVersion: 2`.

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
    Optional<ConnectionState> connection(String connectionKey);
    Optional<String> instanceName(String systemKey);
}
sealed interface ConnectionState permits Values, Mocked {
    String adapter();
    boolean stopped();                     // stopped by a connection administrator
}
record Values(String adapter, Map<String, Object> values, boolean stopped) implements ConnectionState {}   // secret:// as references
record Mocked(String adapter, MockBehaviour behaviour, boolean stopped) implements ConnectionState {}
record MockBehaviour(Reply reply, Duration delay, Optional<Fault.Kind> fail) { enum Reply { ECHO, EMPTY } }
```

The testkit ships `InMemoryEnvironment`, which a test fills and activates by hand.

## What the node does

### At a deploy

Under the pool's deploy lock, with the checks of today, every problem a line of one 422:

1. **Systems**: each file against its schema; the key rules above against the installation's deployed packages and this
   deploy; every adapter known to the node; a drop or a change of a used system or connection, as above.
2. **Flows**: the sender and every send step: the adapter known; the connection named exactly when the adapter connects
   for that direction; every key in `connection` and `connections` defined by this deploy or a deployed package, with the
   step's adapter; the settings checked against the flow form for the direction; templates compiled, only in the fields
   that take them; internal addresses and cycles as today.
3. **The environment**, at the generation `current()` gives: every connection a flow can reach is set for its adapter
   (values that pass its connection form for every direction it is used in, with every secret they name set) or mocked;
   every system with a used connection has an instance name; a step that uses an address field has allowed hosts.
   Each line names the flow, the step, the connection key, the field and the environment, never a value.
4. **The record**: the deployment names the generation it checked against, and the table `deployment_connection_uses`
   (deployment, flow, step, connection key, adapter, direction) records every connection a flow can reach.
5. **The hash** of a flow covers its file and the files it reads, as today. No system file and no value of the environment
   is in it, so a connection's change restarts no flow and needs no new lock.

A follow of the pool's branch is a deploy and checks the same. A restore of what was deployed, at a node's start, does not
check the environment again: a problem shows as below.

### At each send

1. The step picks its connection key: the named one, or the value of `by` among `connections`.
2. It reads the connection's state from `current()`: absent, or `stopped`, fails the send as retryable, naming the
   connection and the environment ("connection acme/erp/rest is not set in prod"); `Mocked` sends through the platform's
   mock; `Values` goes on.
3. It renders the step's settings into a `Call`; a template that fails, fails the send as `INVALID`.
4. It takes the producer of (flow, step, connection key) built at the current generation, building it when there is
   none, and sends with the deadline under the limit of its target.
5. It hands the receiver's facts to the message's record: the connection key, the system key, the partner, the system's
   instance name as it held then, and whether it was mocked. What is written on the message is the design of who may send
   to a flow.

### When the environment changes

`onActivation` names a new generation. The next send through a changed connection builds a new producer from the new
values, and the old one closes once its sends settle: every later attempt, a retry waiting in the queue included, goes out
with the new values. A sender whose connection changed is stopped and started again with a new `Inbound` through today's
restart path, without backoff. Switching to the mock and back is a change like any other. A plain secret value held in a
connection's values that changes in the secret store is a change of that connection the same way, and rebuilds no flow.

### When a connection or a system is removed

- **From git**: refused while a deployed flow names it, as above.
- **From the environment**: the configuration of an environment refuses it while `deployment_connection_uses` names it,
  unless the connection administrator confirms. Once confirmed, every send through it fails as retryable (so an
  at-least-once message waits and is parked after its retries), and a polling sender stops taking files and its flow is
  `DEGRADED` in `/health`, naming the connection. Both recover at the activation that sets it again. A node that starts
  meanwhile starts the flow the same way.

## A system of another package

A flow names a connection by its key against what is deployed in the installation. It needs no dependency on the package
that defines the system, and nothing about it goes into `package.lock`: a connection holds nothing in git that could be
pinned to a commit, and its values change in the environment without a new lock or a new deploy of the flows that use it.

- The package that defines a system deploys it, flows or none: a package of systems alone is a package like any other.
  It must be deployed before a flow that names its connections; that deploy is refused otherwise, naming the key.
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
- **`rail8 test`** replaces every connection by a mock. A test of `schemaVersion: 2` keys `mocks` and `expect.sent` by
  connection key, and a call of another flow by `direct:<address>`; the mock's behaviours do not change.
  `expect.sent.<key>.settings` checks the rendered settings of each send. A connection a flow can reach without a mock in
  the test fails the test, naming it.

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
  or `empty`), `delay` (at most 60 s), and optionally `fail` (`retryable` or `invalid`, every send).
- A mocked polling sender takes nothing; its flow runs, and `/health` says the sender is mocked.
- A send through the mock is marked on the message's receiver, and `/health` lists every mocked connection with the flows
  that use it, so a mock left on in production is in sight.
- A mock is allowed in every environment. The configuration of an environment may refuse it per environment.

## Several receivers

Today the engine does not deliver to several receivers each on its own. A `to` step sends to exactly one connection;
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
# deploy/quickstart/environment.yaml, read once at the node's start from RAIL8_ENVIRONMENT_FILE
schemaVersion: 1
connections:
  acme/erp/rest:   { adapter: http, values: { url: "http://localhost:8090" } }
  globex/erp/rest: { adapter: http, values: { url: "http://localhost:8090" } }
  billing/sftp:    { adapter: sftp, values: { host: sftp, port: 22, user: demo,
                                              password: "secret://sftp-password", hostKey: "secret://sftp-host-key" } }
instanceNames:
  acme/erp: ACME-ERP-DEV
  globex/erp: GLOBEX-ERP-DEV
  billing: BILLING-DEV
```

- The file is at most 1 MiB, its generation is 1, and a change takes a restart. Without the variable the environment is
  empty, and a deploy that names a connection is refused, naming the variable.
- It is not in any package: it belongs to the node, like its other settings. Secrets stay references.
- When the store is built, it replaces this file as the source of `Environment`; whether the file stays as a seed of an
  empty store is decided there.

## Moving to the new shape

The node reads both shapes while the steps land, and the last step removes the old one.

- A flow of `schemaVersion: 1` names endpoint files as today. Inside the node each endpoint becomes one use of the new
  contract: its `config`, with the override of `RAIL8_ENV`, is split by the adapter's forms into a `Connection` of its
  own and the settings in the flow. Its deploy checks and its hash do not change, and it does not touch the environment.
- A flow of `schemaVersion: 2` names connections as this document says. A package may hold both while it moves; a flow of
  one shape cannot name what belongs to the other.
- Once the demo, the template and the documents are on the new shape, the last step removes `kind: endpoint`,
  `endpoint.schema.json`, flows of `schemaVersion: 1` and the Endpoints screen. A package that still holds them is refused,
  naming the file and what replaces it. A package of one's own moves by hand, as the demo's change shows: each endpoint
  file becomes a connection of a system and the connection's values in the environment file.

`examples/demo` gets `systems/acme-erp.yaml`, `systems/globex-erp.yaml` and `systems/billing.yaml`; its flows and tests
move to `schemaVersion: 2` as above; its endpoint files go once the flow editor writes the new shape. The quickstart gets
`deploy/quickstart/environment.yaml`, mounted by `docker-compose.yml`, which still sets the secrets' variables.

## Importing from SAP

A tool that imports from SAP needs these from the format, and nothing else:

- A **communication channel of SAP PO** becomes a connection: its party and communication component give the system key
  (`acme/edi`, or `eshop` without a party), its name the connection's name, its adapter type an adapter. Its attributes
  are split by the adapter's description at `GET /admin/components`, so the tool hard-codes no split: the connection's go
  to the environment of each source system (development, quality, production), a password as a `secret://` name left to
  fill; the flow's go to the flow made from the integrated configuration or receiver agreement that uses the channel.
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
- the `Environment` interface above, implemented over its store, with `onActivation` called on every node;
- holding, per connection key, its adapter and its values or the mock switch and whether it is stopped, and per system key
  its instance name;
- checking a connection's values against the adapter's connection form (format 2) for the directions
  `deployment_connection_uses` names, before an activation;
- refusing to remove the values of a connection that `deployment_connection_uses` names, unless confirmed;
- deciding how the deploy's record names the generation, and whether a mock is allowed per environment.

This design gives it the system and connection keys, `GET /admin/systems` (every deployed system with its connections,
their adapters, descriptions, the flows that can reach each, and each one's state in this environment: set, mocked,
stopped or missing, never a value), and `deployment_connection_uses`.

## The line with who may send to a flow

That design owns credentials of a system, the check at the entry, Allowed senders, the switch and the limit per caller in
the flow, test messages by the Message sender, and writing the sender and the receivers (partner, system, instance name)
on every message.

This design leaves it:
- the key `senders` in a flow's `from`, refused until it ships;
- the place in `Component` and `Consumer` for the kinds of credential a consumer reads and the hand-over of what a caller
  presented, added without breaking adapters built on this contract;
- the receiver's facts at each send (connection key, system key, partner, instance name, mocked), and for a polling
  sender its connection's system as the sender.

## The line with the secret store

That design owns secrets: their kinds, the store, `Secret` handles read at each use, and `secret://<name>` references,
which do not change.

This design relies on it for:
- `secret://` references in a connection's values, resolved as an endpoint's options are today: a plain value into
  `Connection.values`, a handle into `Connection.secrets`;
- telling the node that a secret changed, so that a connection holding its plain value is changed as above;
- the deploy's check that every secret a used connection names is set.

The secrets a connection names join the flow's scrubbing of failures, as an endpoint's do today.

## What this replaces

- The endpoint file (`kind: endpoint`, `endpoint.schema.json`) and the rule that an adapter's form is saved into it.
- Differences between environments as overrides in files (`environments:` in an endpoint), for connections.
- `from.endpoint`, `to.endpoint`, `to.byProp` and `to.endpoints` in `flow.schema.json`.
- `EndpointConfig`, `Component.settings()`, `Component.configSchema()` and `Producer.send(Envelope)` in `rail8-api`.
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
| The mock's delay | 60 s |
| The environment file | 1 MiB |
| Producers of one use | 2, the second only while the first closes |

## Tests

- Keys: the segment rule, `rail8`, the first-segment rule across packages, 2 and 3 segments read apart.
- System files: schema; the same definition in two packages accepted, a different one refused naming both; a drop, an
  adapter change and an undeploy of a used system refused naming the flows; a move between packages without a stop.
- Flows: each branch of `to`; a connection named where the adapter has none, and missing where it has one; a key of
  another adapter; settings against the flow form; a `${` in a literal field; a template that does not compile.
- Templates: each placeholder, each `Escape` with values that must be encoded or refused, the limits, a missing value as
  `INVALID` (422 sync, parked at least once, never retried), the fake clock in `${date:…}`.
- Deploy against `InMemoryEnvironment`: an unset connection, one set for another adapter, incomplete values, an unset
  secret, a missing instance name, each a line naming flow, step, key, field and environment and never a value; the record
  names the generation; `deployment_connection_uses` holds every connection a computed step can reach; the hash unchanged
  by a change of values.
- Activation: a retry waiting in the queue goes out with the new values; the old producer closes only once its sends
  settle; a polling sender restarts with the new values and never polls twice at once; mock and back.
- Removal: a send through a removed connection fails retryably and passes after it is set again; a polling sender is
  `DEGRADED` and recovers; a node started meanwhile does the same.
- `rail8 validate` offline with a key from a dependency, and the note for an unknown one; `rail8 test` with keyed mocks,
  `expect.sent.<key>.settings`, and a missing mock refused.
- The contract suite on every shipped adapter, the template and a fake adapter that breaks each rule of `Forms.of`.
- Both shapes in one package; the demo's tests on both shapes while they move.

## Steps

Each step is one issue and one pull request, keeps the demo package and the quickstart working, and rewrites the
documents its change touches. By the end of the fourth, "draw a flow, deploy it, send a message, see it in Monitor" runs
on the new model, and from the second on the owner sees a flow of the new shape run. The owner tries each one by hand on Windows with WSL; each says whether it changes `examples/demo`, the
quickstart or the database.

1. **The adapter's contract.** `Forms`, labels and text keys, `Connection`, `Inbound`, `Outbound`, `Call`,
   `Producer.send(e, call)`; `http`, `sftp`, `direct`, `mock`, the template, `ComponentContract`,
   `PollingConsumerContract` and `RecordingComponent` on it; endpoint files read through it; format 2 and the texts route.
   The endpoint form shows the connection's fields and the flow's in two groups. No change to the demo, the quickstart or
   the database.
2. **Systems and flows by name.** `system.schema.json`, flows and tests of `schemaVersion: 2` with a sender and a send step
   by name, the key rules, `Environment` with the environment file and `InMemoryEnvironment`, the deploy's checks,
   `deployment_connection_uses`, `GET /admin/systems`, the restart on activation, `rail8 validate` and `rail8 test`. The
   Flows screen draws a flow of either shape and edits only the old one. Changes `examples/demo` (its systems; `sftp-invoice` on the new shape), the
   quickstart (`environment.yaml`, `docker-compose.yml`) and the database (a new table).
3. **Templates and computed receivers.** Expressions in the settings in the flow, `by` with `connections`, `http`'s
   `path`, `expect.sent.<key>.settings`. Changes `examples/demo` (`invoice-sync` and `invoice-async` on the new shape) and
   the quickstart's environment file. No change to the database.
4. **The flow editor on the new model.** The sender and the send step with the adapter, the connection picked from the
   systems of the workspace and the deployed ones, the flow form; New flow and Call a flow write the new shape; New system
   writes a system file. Changes `examples/demo` (`order-jq` and `order-partner` on the new shape, its endpoint files
   gone), the template's package, and the quickstart's text. No change to the database.
5. **The Systems screen under Configure.** Every system with its connections, their adapters, the flows that use each and
   whether it is set, mocked or missing in the environment, and the instance names; editing the values comes with the
   environment's admin API. Replaces the Endpoints screen's place. No change to the demo, the quickstart or the database.
6. **The built-in mock of a connection.** The mocked state in the environment file, the platform's mock, the mark on the
   message's receiver and in `/health`. Changes the quickstart's environment file (the foreign partner mocked). No change
   to the demo or the database.
7. **Several receivers.** The `receivers` step and the work items it starts; its own design first. Changes the database;
   `examples/demo` gains a flow that uses it.
8. **A whole address from the message.** `Field.address`, `allowedHosts`, the platform's check, `http`'s `absoluteUrl`.
   No change to the demo, the quickstart or the database.
9. **The endpoint leaves Rail8.** `kind: endpoint`, `endpoint.schema.json`, flows of `schemaVersion: 1`, the Endpoints
   screen and the words in the documents go. Changes `examples/demo` only if anything of the old shape is left; no change
   to the quickstart or the database.
