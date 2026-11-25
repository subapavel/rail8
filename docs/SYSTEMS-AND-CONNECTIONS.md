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

A key is made of segments: `^[a-z][a-z0-9-]{0,63}$`, never `rail8`, `subscribers` or `by-agreement` as the first one.
The last two are words of a flow's `senders`, which takes a system key in the same place.

- A **system key** is `<name>` or `<partner>/<name>`; a **connection key** is `<system key>/<connection>`. A field that
  takes a connection key reads 2 segments as a system without a partner and 3 as a partner's system.
- In an installation, a first segment is either a partner or a system without a partner, never both: `acme` and
  `acme/erp` cannot both be defined. So `acme/erp` always means one thing to a reader, in a list as in a file. The rule
  is there from the start because a rule added later would refuse keys already in use, and one dropped later refuses none.
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
    "partner": { "$ref": "#/$defs/first", "description": "The partner the system belongs to; absent for a system without one." },
    "name": { "$ref": "#/$defs/segment" },
    "description": { "type": "string", "maxLength": 1000 },
    "connections": { "type": "object", "maxProperties": 64, "propertyNames": { "$ref": "#/$defs/segment" },
      "additionalProperties": { "type": "object", "required": ["adapter"], "additionalProperties": false,
        "properties": { "adapter": { "type": "string", "pattern": "^[a-z][a-z0-9+.-]{0,31}$" },
                        "description": { "type": "string", "maxLength": 1000 } } } } },
  "if": { "not": { "required": ["partner"] } }, "then": { "properties": { "name": { "$ref": "#/$defs/first" } } },
  "$defs": { "segment": { "type": "string", "pattern": "^[a-z][a-z0-9-]{0,63}$" },
             "first": { "$ref": "#/$defs/segment", "not": { "enum": ["rail8", "subscribers", "by-agreement"] } } } }
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
- A connection's adapter must declare that it connects in some direction: one that never does (`direct`, the test
  adapter `mock`) is refused.
- A partner is only the first segment of a key here. What a partner is beyond that (its profile, its identifiers, its
  agreements) belongs to the configuration of an environment and to partner communication.
- **Defined once.** A system key is defined by one file in one deployed package of the installation. A deploy that would
  define a key another deployed package defines is refused, naming both packages, and so is one whose definition is the
  same. So one package holds each system, and whose system it is needs no mark.
- A deploy that drops a system or one of its connections, or changes a connection's adapter, while a deployed flow of
  any package still names it, is refused, naming those flows. An undeploy of a package is refused likewise.
- **Moving a system to another package** goes through a temporary key, in six deploys:
  1. the new package defines the system under a temporary key, such as `acme/erp-moving`, and the instance name and the
     connections' values of that key are entered in each environment;
  2. the flows that name the system are changed to name the temporary key, and deployed;
  3. the old package drops the system;
  4. the new package defines it again under its own key;
  5. the flows are changed back to that key, and deployed;
  6. the new package drops the temporary key, and its values are removed in each environment.

  It costs two changes of every flow that names the system, and the instance name and the connections' values of the
  temporary key entered in every environment. A system kept in a package of its own rarely moves. Open, and not part of
  this design: a deploy of several packages in one transaction would move a system in one step, but it is a new
  contract.

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
  "connectionKey": { "type": "string", "pattern": "^(?!(rail8|subscribers|by-agreement)/)[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63}){1,2}$" },
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
    "oneOf": [
      { "required": ["connection"], "not": { "anyOf": [ { "required": ["by"] }, { "required": ["connections"] }, { "required": ["agreement"] } ] } },
      { "required": ["by", "connections"], "not": { "anyOf": [ { "required": ["connection"] }, { "required": ["agreement"] } ] } },
      { "required": ["agreement"], "not": { "anyOf": [ { "required": ["connection"] }, { "required": ["by"] }, { "required": ["connections"] } ] } },
      { "not": { "anyOf": [ { "required": ["connection"] }, { "required": ["by"] }, { "required": ["connections"] }, { "required": ["agreement"] } ] } } ] } }
```

Each branch refuses the keys of the others, so a stray `by` beside `connection` is refused. In a flow of
`schemaVersion: 2`, `endpoint`, `byProp` and `endpoints` are refused.

- **By name**: `connection: acme/erp/rest`.
- **Computed**: `by` is an expression (see below) whose value is one of the keys of `connections`, each naming a
  connection, so the deploy knows every connection a step can reach. A value that is not a key of `connections` fails the
  send as invalid. Today's `byProp: PARTNER` is `by: ${prop.PARTNER}`. Settings that differ per receiver are expressions
  over a prop that a `route` step sets. A step that determines several receivers is the `receivers` step below.
- **By agreement**: `agreement` is reserved and refused until partner communication designs it.
- **No connection**: the last branch, only for the platform's internal adapters (`direct`).
- A sender or a send step names a connection exactly when its adapter declares that it connects in that direction. The
  named connection's adapter in its system file must be the step's `adapter`.
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
  `INVALID`. That fault is final for the step's own retry and for the work queue's alike, so the send is tried once: a
  sync flow answers 422, an at-least-once flow parks the message.

## Faults the caller sees

The faults these checks raise carry two texts. The caller of a sync flow gets the public one only ("the message is
invalid", "the receiver cannot be reached now"). The detail (the step, the field, the placeholder, the connection key, a
host, the environment) goes to the message's history, which only the admin API shows. Every refusal of the deploy and
every fault here carries a stable code and the values it names beside its English text, as the node's other refusals do,
so the UI words it in the user's language. The detail never holds a value of a connection: the secret store's scrubbing
covers these faults, their causes and their suppressed failures.

## A whole address from the message

A whole address taken from the message goes only through a connection that allows it, with a list of allowed hosts.

- An adapter declares such a field with `Field.address(name, label, help, schemes...)` in its flow form, for the out
  direction. It takes only a template with at least one placeholder, so a whole address never sits in git. The platform
  adds the field `allowedHosts` to that adapter's connection form, a list of text served in the description as the type
  `hosts`: at most 256 entries, each `<host>` or `<host>:<port>`, a host being an exact name or `*.<suffix>`, matched as
  written, case aside, and never resolved. `*.example.com` matches `a.example.com` and `a.b.example.com`, never
  `example.com` or `badexample.com`; an IP literal matches only itself. An entry without a port allows only the scheme's
  default port. It is a policy on names: what the network lets a node reach at connect time is the network's to enforce.
- At each send the platform renders the field and parses it once into a `URI`: absolute, a scheme the field names, no
  user info, a host and port among the connection's allowed hosts. It hands that `URI` to the producer in the `Call`,
  which builds its request from that `URI`'s parts, never from a string parsed again, and follows no redirect. A refused
  address fails the send as `INVALID`, so a hostile message is parked at once; once the host is added, a retry from the
  dead-letter queue goes through.
- The node limits the sends in flight through such a field by the connection's key, whatever host each one names.
- The deploy refuses a step that uses an address field through a connection whose `allowedHosts` is empty in the
  environment, and records the use as one with an address, so that emptying the list while it is used asks the
  configuration's one confirmation; its sends then fail and are retried, as any failed send.
- Each entry of `allowedHosts` is a server of its own under the configuration's rule of consent: a connection that
  carries a secret takes an entry that adds a new pair of a secret and a server only with the secret administrator's
  consent.
- `http` gains `absoluteUrl` as such a field, which a step uses instead of `path`.

## The adapter's contract

`rail8-api` changes in one step, and every shipped adapter, the adapter template and the testkit follow in it.

```java
package dev.rail8.api;

public interface Component {
    String scheme();                     // the adapter type, as a flow and a system file name it: "http"
    Forms forms();                       // replaces settings() and configSchema(); every adapter describes its forms
    Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler);
    Producer producer(Outbound out);
    Future<Health> health();
    default Optional<String> httpPrefix() { return Optional.empty(); }                          // as today
    default Optional<InetSocketAddress> listensOn(Inbound in) { return Optional.empty(); }
    default Optional<String> polledPlace(Inbound in) { return Optional.empty(); }   // replaces polls(): what it fetches from
    default Optional<String> target(Outbound out) { return Optional.empty(); }      // from the connection alone
    default Optional<String> internalAddress(Direction direction, Map<String, Object> settings) { return Optional.empty(); }
}

/** One connection as the environment holds it now, for one direction: the values of the fields that serve it, checked
 *  against the connection form, defaults filled in. values holds no secret; each secret field is a handle in secrets,
 *  read at each use. Both maps are immutable copies, and toString names the key and the fields, never a value. */
public record Connection(String key, Map<String, Object> values, Map<String, Secret> secrets) {
    public Optional<Secret> secret(String field) { return Optional.ofNullable(secrets.get(field)); }
}

/** One sender of a flow: its connection when its adapter has one for the in direction, its settings in the flow. */
public record Inbound(String flow, Optional<Connection> connection, Map<String, Object> settings, long maxBodyBytes) {}

/** What a producer is built for: one connection, or none for an internal adapter. */
public record Outbound(Optional<Connection> connection) {}

/** One send: the flow and step it is for, every setting in the flow with its templates rendered, the largest reply taken,
 *  and the checked address of an address field, empty for a step without one. */
public record Call(String flow, String step, Map<String, Object> settings, long maxReplyBytes, Optional<URI> address) {}

public interface Producer {
    Future<Envelope> send(Envelope e, Call call);
    default Future<Envelope> send(Envelope e, Call call, Deadline d) { /* refuses a passed deadline, as today */ }
    Future<Void> close();
}
```

```java
package dev.rail8.api.settings;

/** The two forms of an adapter and, for each direction it serves, whether it reaches a connection. A direction absent
 *  from reach is one the adapter does not serve. The constructor checks the rules below and throws naming the field. */
public record Forms(Settings connection, Settings flow, Map<Direction, Reach> reach) {
    public enum Reach { CONNECTION, NO_CONNECTION }
    public boolean connects(Direction direction) { return reach.get(direction) == Reach.CONNECTION; }
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
  the four forms the UI draws are each place for each direction. The constructor of `Forms` refuses, and so the adapter
  does not load: a field name used twice across both forms; a `secret` field in the flow form; `expression` or `address`
  in the connection form, on a field that is not text, or for the in direction; a `visibleWhen` or `exactlyOne` across
  the two forms; a field named `flow`, `maxBodyBytes`, `maxReplyBytes` or `allowedHosts`, which are the platform's; a
  direction that reaches a connection with no connection field serving it, or one that does not with such a field; a
  field of either form serving a direction the adapter does not serve.
- **Which directions connect is declared**, never derived from the fields. The node refuses to load an adapter whose out
  direction reaches no connection, unless it is one of the platform's internal adapters (`direct`, and `mock` in tests).
  So a flow sends only through a connection. A deploy that uses an adapter in a direction it does not serve is refused,
  naming the flow. Changing what an adapter declares is a breaking change of the adapter, named in its release notes.
- **One set of values per connection.** A connection's values are checked against the whole connection form: each value
  must be a field's and of its type, and a required field counts as missing only for a direction a flow uses the
  connection in. Each `Connection` handed to an adapter holds the values of the fields that serve its direction.
- **Producers belong to connections.** The node builds one producer per connection, at the connection's version, and
  one per internal adapter. Every flow and step that sends through the connection shares it, so its pool is the
  connection's. A producer reads the flow's settings only from each `Call`.
- **A fresh one at any time.** The node may build a fresh consumer or producer of the same use at any time, and closes
  the old one once its sends settle, at the latest at their deadline. A consumer's successor starts only after it
  stopped and its last poll's files were acknowledged. Building one opens nothing.
- **The polled place.** `polledPlace` names what a polling consumer fetches from, within its connection (for `sftp`, its
  directory and glob). The deploy refuses two flows whose senders poll the same connection and place. The poller's lease
  is keyed by the flow alone, `<package>/<flow>/from`, in both shapes, so the key holds no value of a connection and stays
  the same across an activation and when a flow moves to the new shape. It is the only key from the first step on: the
  release note says to upgrade every node of a pool before its polling flows run again, since a node of the release
  before takes today's key, which holds the endpoint's id.
- **The limit per target.** `target` names the system a producer calls (`scheme://host:port`) from its connection's
  values. When it is empty, the node limits the sends in flight by the connection's key.
- **The reply's limit** is the step's `maxReplyBytes`, an integer from 1 to the largest `long`, as `limits.maxBodyBytes`
  is, else the flow's `limits.maxBodyBytes`, handed in each `Call`;
  `http`'s `maxBodyBytes` field goes. For a flow of `schemaVersion: 1`, an out endpoint's `maxBodyBytes` stays the
  reply's limit when it is given, on any adapter; without it, the flow's `limits.maxBodyBytes` holds.
- **The plugin API's version** goes up with the step that changes this contract. A plugin built on the earlier version
  is refused at load, as the loader refuses any other version, with a line saying that it must be built again on the
  new contract. The secret store's step that changes `Field` raises it too; if both land in one release, it goes up once.
- `HttpIngress.mount` takes the adapter's scheme and the `Inbound` in place of the `EndpointConfig`.
- **Text keys.** Every field, choice and tab has a label, and every field a help text, in English in the code. Their
  keys are derived and never written: `<scheme>.field.<name>.label`, `<scheme>.field.<name>.help`,
  `<scheme>.field.<name>.choice.<value>`, `<scheme>.tab.<id>`; the platform's own fields and tabs (`general`, `retry`,
  `recording`, `security`) use `rail8.` in place of the scheme, and a plugin whose scheme is `rail8` is refused at load.
  Two adapters never clash, since a scheme is unique on a node.
- **An adapter's own translations.** An adapter's jar may carry `META-INF/rail8/texts/<scheme>/<language>.json`: one
  flat JSON object of those keys to ICU messages, the form of the UI's own texts, at most 256 KiB each. The node serves
  it at `GET /admin/components/<scheme>/texts/<language>` for a language matching `^[a-z]{2,3}(-[A-Z]{2})?$`, and 404
  for one the jar lacks. The UI lays it over the adapter's English, which stays the text of every key a language lacks,
  as for the UI's own texts. The contract suite checks that every declared text has its key and its English text, and
  that a translation holds only declared keys, each with the same ICU arguments as its English.
- **Problems carry codes.** A problem of `Settings.check` carries a stable code (`required`, `unknown`, `min`, `max`,
  `pattern`, `choice`) besides its English text.
- **The description** served at `GET /admin/components` becomes format 2: `{ "format": 2, "reach": { "in": …, "out": … },
  "connection": [fields], "flow": [fields] }`, each field with `label`, `labelKey`, `helpKey`, and `expression` or
  `address` where set, and with what format 1 has: tabs, `visibleWhen`, `exactlyOne` and choices. A secret field's
  kinds join it with the secret store's step that adds them.
  It still serves three readers: the deploy and `rail8 validate` check against it, the UI draws
  the forms from it, and the configuration of an environment checks a connection's values against its connection form.
- **Credentials a caller presents.** The design of who may send to a flow adds what a consumer needs for them: the kinds
  of credential it reads, and a call made before the message exists (before its id, its headers and its body), which
  presents what the caller presented. Each comes with a default that answers "not supported", so a flow whose sender
  requires a credential its adapter cannot read is refused at deploy, and an adapter built on this contract keeps
  compiling.

How the shipped adapters split:

| Adapter | Connection form | Flow form |
| --- | --- | --- |
| `http` in | none | `address` (the path it serves) |
| `http` out | `url` (the base: scheme, host, port, a path prefix; no user info), `timeoutMs`, `poolSize` | `path` (expression, `PATH`; when set, its literal start is `/`), later `absoluteUrl` |
| `sftp` in | `host`, `port`, `user`, `password`, `privateKey`, `hostKey` (a secret field of kind value, as the demo already names it), `connectTimeoutMs`, `ioTimeoutMs` | `dir`, `glob`, `intervalMs`, `maxFilesPerPoll`, `minAgeMs`, `afterSuccess`, `moveTo`, `errorDir` |
| `direct` in and out | none (internal) | `address` |

`http` parses `url` once into a `URI`, joins `path` to that `URI`'s path, and builds the request from the parts of the
one result (host, port, path, query), never from a string parsed again. A result whose scheme, host or port differ from
`url`'s, or whose path leaves `url`'s path, is refused as `INVALID`. Without `path` it sends to `url` as it is. A
connection's `url` holds no user info; a `schemaVersion: 1` endpoint's `url` keeps the user info it may hold today until
`http` has login fields of its own, a step below. `sftp`
checks at each login that its `hostKey` is a `SHA256:` fingerprint, and a login with another value fails, naming the
field.

The testkit follows. `ComponentContract` and `PollingConsumerContract` take a `Connection` and settings, and check: two
different `Call`s at once through one producer each reach the receiver with their own settings; two producers of one
connection work side by side, and closing the first, also while a send of it is out, does not break the second; a passed
deadline is refused; a consumer's successor starts only after it stopped; a secret handle is not read before its first
use; every field, choice and tab has a label and every field a help text, with their keys in the serialized description;
and the rules of `Forms`, against a fake adapter that breaks each one. Each adapter keeps its own mock, which passes the
same suite as the real one. `RecordingComponent`, the `mock` adapter and the Java of the template of `rail8 new-adapter`
(`examples/webhook-plugin`) are rewritten on the contract; the template's package moves to the new shape with the demo's.

## What the node relies on from the environment

The node reads the environment through one interface inside `rail8-runtime`. Adapters never see it, and neither does
`rail8-core`: the flow's send step there asks a narrow interface of its own for each attempt (the connection's state, its
producer and the receiver's facts, from one snapshot), which `rail8-runtime` implements over this one.

```java
interface Environment {
    String name();                         // RAIL8_ENV: dev, test, prod
    Snapshot current();                    // never blocks; the state the node holds in memory
    void onActivation(java.util.function.Consumer<Snapshot> listener);   // once per new snapshot, in the order taken;
                                                                         // one snapshot may cover several activations
}
interface Snapshot {
    long generation();                     // raised by every activation; the deploy's record names it
    boolean mocksAllowed();
    Optional<ConnectionState> connection(String connectionKey);
    Optional<String> instanceName(String systemKey);
}
sealed interface ConnectionState permits Values, Mocked {
    String adapter();
    long version();                        // changes only when this connection's values or mock change
    Optional<Stop> stopped();              // stopped by an operator or a connection administrator: by whom and when
}
record Stop(String by, Instant at) {}
record Values(String adapter, long version, Map<String, Object> values, Optional<Stop> stopped) implements ConnectionState {}   // a secret field: its name
record Mocked(String adapter, long version, MockBehaviour behaviour, Optional<Stop> stopped) implements ConnectionState {}
record MockBehaviour(Reply reply, Duration delay, Optional<Failure> fail) {
    enum Reply { ECHO, EMPTY }
    enum Failure { RETRYABLE, INVALID }
}
```

An attempt to send reads `current()` once and uses that snapshot throughout. `InMemoryEnvironment`, which a test fills
and activates by hand, lives in the tests of `rail8-runtime`; the published testkit depends on `rail8-api` alone.


## What the node does

### At a deploy

Under the pool's deploy lock, with the checks of today, every problem a line of one 422 with its code and values. The
checks run against the pool as it would be after this deploy: the package's earlier system files and uses are replaced
by the candidate's first, so a package that drops its last flow of a connection together with the connection is not
refused. What the other packages define is read from the committed records of the pool, `deployment_systems` below,
never from what this node installed, so a package that failed to install here still counts.

1. **Systems**: each file against its schema; the key rules above; every adapter known to the node and declaring a
   connection for some direction; a drop or a change of a used system or connection, as above.
2. **Flows**: the sender and every send step: the adapter known and serving that direction; the connection named
   exactly when the adapter connects in that direction; every key in `connection` and `connections` defined by this
   deploy or a deployed package, with the step's adapter; the settings checked against the flow form for the direction;
   templates compiled, only in the fields that take them; no two senders on one connection and polled place; internal
   addresses and cycles as today.
3. **The environment**, in the state the configuration holds when the deploy runs: every connection a flow can reach is
   set for its adapter (values that pass its connection form for every direction it is used in, with every secret they
   name set and of a kind its field takes), or mocked where mocks are allowed; every system with a used connection has
   an instance name; a step that uses an address field has allowed hosts. Each line names the flow, the step, the
   connection key, the field and the environment, never a value.
4. **The record**: in the transaction that writes the deployment's record, the deploy writes the generation it checked
   against (`deployments.config_generation`), the table `deployment_systems` (deployment, system key, its definition:
   partner, name, connections and their adapters) with every system the package defines, and the table
   `deployment_connection_uses` (deployment, flow, step, connection key, adapter, direction, whether through an address
   field, and a sender's polled place) with every connection a flow can reach, each choice of a computed step included.
   In that transaction it checks the used connections again, under the lock on the environment that every activation
   takes, as the secret store does for secrets. A check that fails there rolls the deploy back as a failed save of the
   record does today, and the earlier rows stay. The flows that phase B started before the save read the snapshot at
   each send, so a send through a connection removed meanwhile fails, as below.
5. **The hash** of a flow covers its file and the files it reads, as today. No system file and no value of the environment
   is in it, so a connection's change restarts no flow and needs no new lock.

Only the node that deploys checks the environment. A node that installs a record, at a follow or at its start, does not
check it again: a problem there shows as below. At its start a node reads the environment before it restores its
packages, and before it installs any package it reads `deployment_systems` of every record, so a flow finds the systems
of another package whatever order the packages install in, and whatever state each one is in. A restore checks the
secrets the flows themselves name, as the secret store says; a missing secret of a connection is a connection that is
not set.

**A flow that retires.** An at-least-once flow that its package's new version drops, or whose package is undeployed,
keeps its rows of `deployment_connection_uses` until its drain ends, so the connections its queued work still needs
cannot be removed unasked.

### At each send

Each attempt reads one snapshot and keeps it, and the producer it took, until its send settles. A retry is a new attempt.

1. The step picks its connection key: the named one, or the value of `by` among `connections`.
2. It renders the step's settings, and an address field, into a `Call`; a template or an address that fails, fails the
   send as `INVALID`.
3. It reads the connection's state from the snapshot. Absent, `stopped`, set for another adapter than the step's, or
   with values incomplete for the step's direction, the send fails, below. `Mocked` sends through the platform's mock.
   `Values` goes on.
4. It takes the connection's producer at its version, building it when there is none, and sends with the deadline under
   the limit of its target.
5. The send step's entry in the message's history names the connection key and whether it was mocked, never a value. It
   hands the receiver's facts (the connection key, the system key, the partner, the system's instance name, whether it
   was mocked), all from the attempt's snapshot, to the record that the design of who may send to a flow writes on the
   message, whether the send succeeded or not. A system in use always has an instance name: the deploy refuses one
   without, and the configuration refuses its removal.

**A connection that cannot serve the send.** A send through a connection that is absent from the snapshot, stopped, set
for another adapter than the step's, or with values incomplete for the step's direction fails, with an error that names
the connection key and the reason: stopped, by whom and when; not set; set for another adapter; incomplete, naming the
fields; or left out by this node, naming why, such as a binding that fails or a mock where mocks are not allowed. The
failure is retryable, as a receiver that cannot be reached is: it counts as an attempt, its steps are in the history,
and the flow's `delivery.retries` and the queue's rules retry it and then park it, as any failed send. Once the
connection serves, the next retry goes through. A sync flow answers 503, as for any failed send to a receiver that
cannot be reached. An at-most-once flow fails the send. Such a send marks nothing in `/health`, as any failed send does
not; the Systems screen, `GET /admin/systems` and Monitor show the connection's state.

### When the environment changes

`onActivation` hands a new snapshot. The next attempt through a connection whose version differs from its producer's,
higher or lower (a store set back counts), builds a new producer from the new values, and the old one closes once its
sends settle: every later attempt, a retry waiting in the queue included, goes out with the new values. A connection
whose version did not change keeps its producer. At most 4 old producers of one connection close at once; an attempt
that would need a fifth waits for the oldest to close, at most until its own deadline. A sender whose connection changed
is stopped and started again with a new `Inbound`, without backoff; a restart that a later snapshot makes obsolete is
cancelled, so only the newest values start. Switching to the mock and back is a
change like any other. A secret that a connection names is not a change of the connection: its handle is read at each
use, so a new value reaches the adapter at its next login, session or handshake, and nothing is built again.

### When a connection or a system is removed

- **From git**: refused while a deployed flow names it, as above.
- **From the environment**: the configuration of an environment asks one confirmation before any change that leaves a
  connection that `deployment_connection_uses` names unusable, its removal among them. Once confirmed, every send
  through it fails, as above, and is retried. A polling sender whose connection is missing, stopped, of another adapter
  or incomplete for the in direction fails its start, naming the connection and the reason, as any consumer whose start
  fails: its flow is listed `RESTARTING`, the `deployments` check of `/health` is `DEGRADED` naming it, and it is tried
  again by the restart's rule. An activation, a stop or a start of its connection restarts it at once, so it starts
  when its connection serves again. A node that starts meanwhile does the same.
- **An instance name**: the configuration refuses to remove the instance name of a system whose connections are used,
  with no confirmation. It can be changed.

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
  between them through a temporary key, as above.

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

- The mock is the platform's, not the adapter's, so every adapter has it. It is not a component and not the mock that
  each adapter ships for its contract suite. Its behaviour: `reply` (`echo`, the body sent, or `empty`), `delay` (at most
  60 s), and `fail` (`retryable` or `invalid`: the send fails as the receiver being down, or as `INVALID`). It sets none
  of the adapter's own props on the reply, so a step that reads one sees none.
- It runs after the settings are rendered and an address field is parsed and checked, so a mocked flow shows the same
  faults as a real one, but for the list of allowed hosts: a mocked connection sends nowhere, and the deploy does not ask
  it for one.
- A mocked polling sender takes nothing; its flow runs, and `/health` says the flow has a mocked sender.
- An environment allows mocks only when it says so, and one that does not refuses them; without a setting, it does not,
  since a mocked send completes the message without reaching the partner, and Rail8 has no replay. The configuration of
  an environment decides which do. Turning mocks off while a used connection is mocked leaves it unusable, and asks
  that design's one confirmation; a switch of a used connection to the mock leaves it usable, and asks none. The
  quickstart's environment allows them.
- A send through the mock is marked in the send step's record, which the flow's recording keeps as it keeps every
  record, and in the receiver's facts, which the record of receivers that the design of who may send to a flow writes
  keeps whatever the flow records. The mock ships after that record, so no mocked send is without a trace. `/health` counts the mocked connections; `GET /admin/systems` names them with the
  flows that use them.

## Several receivers

Today the engine does not deliver to several receivers each on its own. A `to` step sends to exactly one endpoint;
`to` with `byProp` picks one. `split` runs its parts in the parent's run, so one part that fails fails them all, and a
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
  elsewhere, and in the steps after another `receivers` step: a child starts no children at first.
- That step's own design settles how the children are numbered, retried, parked and shown, and must hold: the children
  are written in the transaction that ends the parent's run; each child keeps one id across its retries, distinct from
  its siblings'; the parent's body is kept while a child needs it; a child names the flow's revision and the step it
  continues at, so a redeploy meanwhile does not change what it runs; a child that fails is retried without repeating a
  sibling that succeeded.

## The environment of the demo and the quickstart

No node reads a file of the environment. The store of the configuration of an environment is built before the second
step, which is the first to read it, and the quickstart, the demo and CI configure their environment as a production
team does, through that design's import:

```yaml
# deploy/quickstart/config/connection/billing/sftp.yaml
schemaVersion: 1
kind: environment.connection
key: billing/sftp
adapter: sftp
values: { host: sftp, port: 22, user: demo, password: "secret://sftp-password", hostKey: "secret://sftp-host-key" }
```

- `deploy/quickstart/config/` holds a file per connection and per system of the demo: `acme/erp/rest` and
  `globex/erp/rest` at the partner echo, `http://localhost:8090`; `billing/sftp` at the stack's SFTP server, above; and
  the instance names `ACME-ERP-DEV`, `GLOBEX-ERP-DEV` and `BILLING-DEV`. Its secrets are those that the secret store's
  import took from the stack's variables at the node's first start.
- `deploy/quickstart/configure` imports the folder and activates each object, by that design's pipeline. The quickstart
  runs it after `rail8 bootstrap`, with the technical account's token. `rail8 bootstrap --role connection-administrator
  --role secret-administrator` gives that account the two roles beside the developer's, so each activation consents by
  the same act; outside the quickstart an administrator gives them to an account on purpose, and the quickstart's text
  says so. A second run activates nothing that is in force. The stack of `deploy/compose` and the checks of `ci/check`
  run it the same way.
- A change of the quickstart's environment is a change of a file there and a second run of the script, or a change
  through the UI or `rail8 config`.
- The platform's own tests use `InMemoryEnvironment`.

## Moving to the new shape

The node reads both shapes while the steps land, and the last step removes the old one.

- A flow of `schemaVersion: 1` names endpoint files as today. Inside the node each endpoint becomes one use of the new
  contract: its `config`, with the override of `RAIL8_ENV`, is split by the adapter's forms into a `Connection` of its
  own and the settings in the flow. Its deploy checks and its hash do not change, it does not touch the environment, and
  its secrets follow the secret store's rules for endpoint files. The exceptions, each a fallback that took the
  endpoint's id as a host: an `http` out endpoint without `url` and an `sftp` endpoint without `host` are refused with
  `is required`, and the release notes say to set them. An `http` or `webhook` out endpoint without `maxBodyBytes` now
  deploys, with the flow's limit as the reply's.
- A flow of `schemaVersion: 2` names connections as this document says. A package may hold both while it moves; a flow of
  one shape cannot name what belongs to the other.
- From the step of the flow editor on, a deploy of a package that holds an endpoint file or a flow of `schemaVersion: 1`
  warns, naming each file and what replaces it.
- `rail8 migrate` moves a package of one's own: it writes, beside the old files, a system file for each endpoint a flow
  sends through or polls, flows and tests of `schemaVersion: 2`, and, outside the package, a folder of the
  configuration's import files for each environment the endpoints' overrides name: a connection's file with the split
  the node already makes of an endpoint's `config`; the instance names are entered in each environment. That folder is
  imported and activated in an environment before the migrated package is deployed there, since the deploy checks the
  environment. A system is named after its endpoint's id unless a map given to the command names its key. Secrets stay
  `secret://` references. It deletes nothing; its author reviews the result and commits it.
- Once the demo, the template and the documents are on the new shape, the last step removes `kind: endpoint`,
  `endpoint.schema.json`, flows of `schemaVersion: 1` and the endpoint form. A package that still holds them is refused,
  naming the file and what replaces it.

`examples/demo` gets `systems/acme-erp.yaml`, `systems/globex-erp.yaml` and `systems/billing.yaml`; its flows and tests
move to `schemaVersion: 2` as above; its endpoint files go once the flow editor writes the new shape. The quickstart gets
`deploy/quickstart/config/` and `deploy/quickstart/configure`, above.

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
- the `Environment` interface above, implemented over its store, with `onActivation` called on every node once per new
  snapshot and a version per connection;
- the mark `.server()` on the connection form's fields, and the rule that a form only adds fields with a default meaning
  "as before", checked against a recorded form, which its steps add;
- holding, per connection key, its adapter and its values or the mock switch and whether it is stopped, and per system key
  its instance name;
- checking a connection's values against the adapter's connection form (format 2) for the directions
  `deployment_connection_uses` names, before an activation;
- one confirmation before a change that leaves a connection that `deployment_connection_uses` names unusable: removed,
  incomplete for a direction a use needs, without allowed hosts for a use through an address field, or mocked when
  mocks are turned off; once confirmed, its sends fail, as above, and are retried;
- refusing to remove the instance name of a system whose connections are used, which can only be changed;
- allowing mocks only in an environment that says so, none without a setting;
- a lock that every activation takes and the deploy takes in its record's transaction (its environment's counter, which
  an activation takes for update and the deploy for share), so a deploy's check and an activation never cross;
- deciding which environments allow mocks;
- the generation that `deployments.config_generation` holds. That design's store is built before this design's second
  step: its migration creates the column, `deployment_systems` and `deployment_connection_uses`, empty, and this
  design's second step fills them at a deploy.

This design gives it the system and connection keys, `GET /admin/systems` (every deployed system with the package that
defines it, its connections, their adapters, descriptions, the flows that can reach each, and each one's state in this
environment: set, mocked, stopped or missing, never a value), and `deployment_connection_uses`.

## The line with who may send to a flow

That design owns credentials of a system, the check at the entry, Allowed senders, the switch and the limit per caller in
the flow, test messages by the Message sender, and writing the sender and the receivers (partner, system, instance name)
on every message.

This design leaves it:
- the key `senders` in a flow's `from`, refused until it ships;
- the place in `Component` for the kinds of credential a consumer reads and a call made before the message exists that
  presents them, each with a default that answers "not supported"; keeping what a caller presented out of the message's
  headers, the templates, the forwarded headers and the records is that design's;
- the receiver's facts at each attempted send, failed and mocked ones included (connection key, system key, partner,
  instance name, mocked), and for a polling sender its connection's system as the sender, taken at intake;
- what a `direct` call carries of its caller's sender.

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
- its rule that a setting that sends a secret to a server it was not sent to before, or that starts to send another
  secret, takes effect only with the secret administrator's consent. Each entry of a connection's `allowedHosts` is
  such a server.

The handles of a connection's secret fields join the scrubbing of the failures of every flow that uses it, as an
endpoint's do today. A flow's own `secret://` references in its steps' options do not change.

## What this replaces

- The endpoint file (`kind: endpoint`, `endpoint.schema.json`) and the rule that an adapter's form is saved into it.
- Differences between environments as overrides in files (`environments:` in an endpoint), for connections.
- `from.endpoint`, `to.endpoint`, `to.byProp` and `to.endpoints` in `flow.schema.json`.
- `EndpointConfig`, `Component.settings()`, `Component.configSchema()`, `Component.polls()` and
  `Producer.send(Envelope)` in `rail8-api`.
- The endpoint form, which becomes the Systems screen under Configure.
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
| Old producers of one connection closing at once | 4 |
| The mock's delay | 60 s |
| A bundle of texts | 256 KiB |

## Tests

- Keys: the segment rule, `rail8`, `subscribers` and `by-agreement` first and elsewhere, the first-segment rule across
  packages, 2 and 3 segments read apart.
- System files: schema; an adapter without a connection form refused; a key another deployed package defines refused
  naming both, with the same definition too; a drop, an adapter change and an undeploy of a used system refused naming
  the flows; a move between packages through a temporary key, in its six deploys; two packages naming each other's
  systems.
- Flows: each branch of `to`, and each with a stray key of another; `byProp` in a flow of `schemaVersion: 2`; a
  connection named where the adapter has none, and missing where it has one; an adapter in a direction it does not
  serve; a key of another adapter; settings against the flow form; a `${` in a literal field; a template that does not
  compile; two senders on one connection and polled place.
- Templates: each placeholder, each `Escape` with values that must be encoded or refused (empty, control characters,
  `.`, `..`, `/`, `?`, `#`, `@`), the limits, a missing value as `INVALID` on a step with `retryable: true` (422 sync,
  parked at least once, tried once by the step's retry and the queue's), `${date:…}` the same on a retry after midnight.
- `http`: a `path` that would change the host or leave `url`'s path refused; `url` with user info refused; the request
  built from the one parsed `URI`; the public text to a sync caller and the detail in the history only.
- Addresses: an address field without a placeholder refused; wildcard boundaries; a port not listed; no redirect.
- Leaks: a recognizable secret named by a connection never shows in a fault, a log, the history, `/health`, the admin
  API or the hash; a recognizable value of a field that is not secret never shows to a sync caller, in `/health`, in
  `GET /admin/systems`, in a deploy's problem or in the hash; changing only values leaves the hash as it was.
- Deploy against `InMemoryEnvironment`: an unset connection, one set for another adapter, incomplete values, an unset
  secret, a mock where mocks are not allowed, a missing instance name, each a line with its code naming flow, step, key,
  field and environment and never a value; the record names the generation; `deployment_connection_uses` holds every
  connection a computed step can reach; an activation between the check and the record; a package that drops a flow and
  its connection together.
- Install: a flows package installed before its systems package; a systems package that failed to install on this node;
  a follower that does not check the environment again; a retiring flow's uses kept until its drain ends.
- Activation: a retry waiting in the queue goes out with the new values; only a changed connection's producer is built
  again; old producers close once their sends settle; two activations in quick succession during a slow send, on the
  fake timer; a version set back; a polling sender restarts with the new values, never polls twice at once, and on two
  nodes holds one lease; a restart made obsolete is cancelled; mock and back.
- A connection that cannot serve: a stopped connection, one not set, one of another adapter, one left out, and values
  incomplete for the step's direction each fail the send with its error, naming the connection and the reason, a stop
  with who and when; the failure counts as an attempt, is retried by `delivery.retries` and then parked; once the
  connection serves, the next retry goes through; sync answers 503; at-most-once fails the send; nothing in `/health`
  for the send; a polling sender whose connection is stopped or missing is `RESTARTING`, and starts at the start of its
  connection.
- The lease: two nodes poll one directory under `<package>/<flow>/from`, and only one polls; the key stays when the
  endpoint's id or URI changes.
- The quickstart's configuration: `deploy/quickstart/configure` run twice activates once, and the demo's flows deploy
  against it.
- `rail8 migrate` on the demo as it was before the second step: its result deploys and its tests pass.
- `rail8 validate` offline with a key from a dependency, and the note for an unknown one; `rail8 test` with keyed mocks
  and `expect.sent.<key>.settings`.
- The contract suite on every shipped adapter, the template and a fake adapter that breaks each rule of `Forms`.

- Both shapes in one package; the demo's tests on both shapes while they move.

## Steps

Each step is one issue and one pull request, keeps the demo package and the quickstart working, and rewrites the
documents its change touches. The second step comes after the first and after the store of the configuration of an
environment, which it reads: the secret store's store, its one source and its credentials in adapters, then that
design's mark, its
store, its node and routes, and its export and import. By the end of the fourth, "draw a flow, deploy it, send a message, see it in Monitor" runs
on the new model, and from the second on the owner sees a flow of the new shape run. The owner tries each one by hand on
Windows with WSL. Each says whether it changes `examples/demo`, the quickstart or the database; a step that changes
`examples/demo` says which files to copy again into `~/rail8-workspace/demo` and commit there.

An adapter's own translations, with their route and the UI laying them over the English, are a step of their own,
built at any time after the first: the bundles of `http`, `sftp` and the template in the UI's four languages, and the
contract suite's checks of them. It changes no file of the demo, the quickstart or the database.

1. **The adapter's contract.** `Forms` with its declared reach, labels, text keys and codes, `Connection`, `Inbound`,
   `Outbound`, `Call` (its `address` empty until the step of a whole address), `Producer.send(e, call)`,
   `polledPlace`, `HttpIngress.mount`, the plugin API's version, the poller's lease key by flow alone, with the release
   note to upgrade every node of a pool before its polling flows run again; `http` (with a literal `path`, the request built from one
   parsed `URI`, and a connection's `url` refusing user info), `sftp` (with `hostKey` a secret field), `direct`, `mock`,
   the Java of the template,
   `ComponentContract`, `PollingConsumerContract` and `RecordingComponent` on it; INVALID final for the step's own retry;
   format 2 with the text keys. Endpoint files are read through it unchanged: an endpoint's `config` with the override
   of `RAIL8_ENV`, its whole `url`, its `maxBodyBytes` as the reply's limit and its hash stay as they are. The endpoint
   form shows the connection's fields and the flow's in two groups. The secret store's step for credentials and key
   pairs in adapters changes `Field` and the adapter's config too: the two never run at once, and whichever starts
   second starts after the first is merged and builds on its types. No change to the demo, the template's package, the
   quickstart or the database.
2. **Systems and flows by name.** After the configuration's export and import. `system.schema.json`, flows and tests of
   `schemaVersion: 2` with a sender and a send step by name, the key rules through the one parser of keys, which the
   configuration's store built with `rail8`, `subscribers` and `by-agreement` reserved, a system key defined by one
   package, the deploy's checks against the pool's resulting state and the store's snapshot, its record in
   `deployments.config_generation`, `deployment_systems` and `deployment_connection_uses`, `GET /admin/systems` reading
   the store, the producers per connection and their swap, a send through a connection that cannot serve failing with
   its reason and retried, the connection key in the history, `rail8 validate`, and `rail8 test` with
   `expect.sent.<key>.settings`. The Flows
   screen draws a flow of either shape and edits only the old one. Changes `examples/demo` (its systems; `sftp-invoice`
   and its test on the new shape, which the quickstart does not edit) and the quickstart (`deploy/quickstart/config/`,
   `deploy/quickstart/configure` and `rail8 bootstrap --role`, run by the quickstart's text, the stack of
   `deploy/compose` and `ci/check`). No change to the database: the configuration's store created its tables.
3. **Templates and computed receivers.** Expressions in the settings in the flow, `by` with `connections`, the public
   texts of faults, a missing value tried once on a step with `retryable: true`. Changes `examples/demo`
   (`invoice-async` and its test on the new shape; the test checks the path each receiver saw, and both invoice tests
   keep their partner-down retry counts). The quickstart's `invoice-sync`, which its reader edits, stays on the old
   shape until the editor writes the new one. No change to the database.
4. **The flow editor on the new model.** The sender and the send step with the adapter, the connection picked from the
   systems of the workspace and the deployed ones, the flow form; New flow and Call a flow write the new shape; New system
   writes a system file. The editor keeps comments, line ends and its compare on save in a package of both shapes.
   Changes `examples/demo` (`invoice-sync`, `order-jq` and `order-partner` on the new shape, with the call from
   `order-jq` through `direct` to `order-partner` run for real; its endpoint files gone), the template's package with
   the configuration's files for its partner on port 18091, `rail8 new-adapter` and `bin/run-adapter`, which imports
   and activates them with its technical account's token after `rail8 bootstrap --role connection-administrator --role
   secret-administrator`, as the quickstart does (the account keeps `operator` and `message-bodies`, and the script's
   header and `docs/ADAPTERS.md` name the two roles, given on purpose elsewhere), the quickstart check of `ci/check`
   (which creates `endpoints/call-audit.yaml` and looks for `partner-domestic` in invoice-sync's file), and the
   quickstart's text. From this step a deploy warns of each file of the old shape. No change to the database.
5. **The Systems screen under Configure.** Every system with the package that defines it, its connections, their
   adapters, the flows that use each and whether it is set, mocked, stopped or missing in the environment, and the
   instance names; editing the values comes with the configuration's own screen, and until then the quickstart's text
   says how to change a file of `deploy/quickstart/config/` and run its script again, or use `rail8 config`. Takes
   the endpoint form's place. No change to the demo, the quickstart's files or the database.
6. **The built-in mock of a connection**, after the record of receivers that the design of who may send to a flow
   writes. The mocked state as the store holds it, the platform's mock with its `fail`, its mark in the send step's
   record and in the receiver's facts, its count in `/health`. Changes the quickstart's configuration (the foreign
   partner's file mocked, and its script allowing mocks with `rail8 config mocks` when the environment does not allow
   them yet, so a second run changes nothing). No change to the demo or the database.
7. **Several receivers.** The `receivers` step and the work items it starts; its own design first. Changes the database;
   `examples/demo` gains a flow that uses it, on connections the quickstart's configuration already sets.
8. **A whole address from the message.** `Field.address`, `allowedHosts`, the platform's check, `http`'s `absoluteUrl`.
   Its own design states connect-time enforcement as an accepted limit or closes it. No change to the demo, the
   quickstart or the database.
9. **`http`'s login**, after the secret store's step for credentials in adapters. Secret fields for `http`'s login in
   its connection form, with the kinds the secret store gives. No change to the demo, the quickstart or the database.
10. **`rail8 migrate`.** The command above, tried on the demo as it was before the second step. No change to the demo,
    the quickstart or the database.
11. **The endpoint leaves Rail8**, after the two steps before it. `kind: endpoint`, `endpoint.schema.json`, flows of
    `schemaVersion: 1`, the endpoint form and the words in the documents go. The
    quickstart's text tells its reader to copy the demo again into `~/rail8-workspace/demo` and commit before pulling
    this release, and to run `rail8 migrate` on a package of their own, since a package of the old shape is refused at
    restore; the step is tried on a fresh install and on a restart with the volumes of the day before. Changes
    `examples/demo` only if anything of the old shape is left; no change to the quickstart's files or the database.


