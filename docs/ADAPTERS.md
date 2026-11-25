# Writing an adapter

An adapter connects Rail8 to one kind of outside system: it is a component of its own scheme, with a consumer that takes
messages in, a producer that sends them out, and two forms from which the node checks endpoint files and the UI draws its
screens. It is built with the development kit:

- **the contract**, `rail8-api`: the `Plugin` and `Component` interfaces and the settings types;
- **the test suite**, `rail8-testkit`: the contract suites `ComponentContract` and `PollingConsumerContract`, the fakes, and
  `RecordingComponent`, the mock of any scheme;
- **the sample**, `examples/webhook-plugin`: an adapter of the scheme `webhook`, an HTTP consumer and producer, and a package
  that uses it;
- **the forms**, `Component.forms()`: the connection form and the flow form, every field an endpoint of the adapter may set
  (see "The two forms" below);
- **the template**, `bin/rail8 new-adapter`: the skeleton of a new adapter;
- **the local run**, `bin/run-adapter`: a node with the adapter and the UI on your machine.

What a component promises the node is in `docs/ARCHITECTURE.md`, part 5, "Writing an adapter plugin". Commands run from the
root of a Rail8 checkout.

## Prerequisites

A JDK 25, Maven 3.9 for the adapter's own build (the checkout builds itself with its `./mvnw`), Node.js 22.19 or newer
with npm, Docker, and `curl`. On Windows run everything inside WSL 2.

Build the kit once, and again after a `git pull`:

```sh
./mvnw -B -ntp -q install -DskipTests -pl :rail8-cli -am   # the contract and test suite in ~/.m2, the node's and the cli's jars
(cd ui && npm ci)                                           # the UI
```

## Create an adapter

```sh
bin/rail8 new-adapter ~/acme-adapter --scheme acme --package com.acme.rail8
```

The folder must not exist or be empty. The scheme is a lower-case letter followed by lower-case letters and digits, and no
scheme the node already has (`http`, `sftp`, `mock`) nor `rail8`; the package defaults to `com.example.<scheme>`. The skeleton is the
sample under the new names:

| File | What it is |
| --- | --- |
| `pom.xml` | the build: `mvn package` writes the adapter's jar into `target/plugin` |
| `src/main/java/…/AcmeComponent.java` | the component: its forms, consumer, producer and health |
| `src/main/java/…/AcmePlugin.java`, `src/main/resources/META-INF/services/…` | the plugin that adds the component, and the file that names it |
| `src/test/java/…/AcmeComponentContractTest.java` | the component against the contract suite |
| `src/test/java/…/AcmeMockContractTest.java` | the adapter's mock against the same suite |
| `src/test/java/…/AcmeComponentTest.java` | what the suite leaves to the component: its own answers and refusals |
| `package/` | a package that uses the adapter: an in endpoint, an out endpoint, a flow, a test case and a fixture |
| `.gitattributes`, `.gitignore` | `* -text`, so git keeps every file's bytes as written, and the build's `target/` left out |

The folder is a git repository on `main`, and its first commit holds every file above; the commit's author is your git
identity. A node deploys the package from a commit, never from the files beside it: commit what you want deployed.

The adapter's own address on the node's HTTP port is its prefix, `/<scheme>` in the template (`Component.httpPrefix()`), as
`/http` is the http adapter's: the node opens one HTTP port, shared by every adapter that takes HTTP requests, and routes a
request by its path. An in endpoint names the rest of the path in its `address` option, so the template's `address: /orders`
is served at `/acme/orders`; the consumer mounts it on the port when it starts (`ComponentContext.http()`) and closes the
mount when it stops. A prefix that another adapter on the node declares, or `/rail8`, refuses the node's start, and
`rail8 validate --plugins` reports it; two flows at one path are refused at deploy. An adapter that takes no HTTP requests
declares no prefix.

An adapter runs inside the node as trusted code, and every adapter's requests share the node's event loops. The mount's
handler is called on an event loop, so it must never block there: the template pauses the request, streams its body into
the blob store and runs the flow on the message's own virtual thread. Keep that order when you add your protocol.

It builds and passes the contract suite as written; it speaks HTTP. The comments marked `HTTP:` in the component are where
your protocol goes; the rest (the body streamed through the blob store, the handler's acknowledgement, the deadline, the
stop) is what every adapter keeps. Then change the forms to your fields, and the package's endpoint files with them.

A consumer that loses its connection after its start fails `broken()` with the cause; leave the waiting and the next
attempt to the node, which stops the consumer and starts a fresh one of the same endpoint after a delay that grows with
each failure in a row. A consumer that cannot break once started, such as one that mounts its endpoint on the node's HTTP
port, as in the sample, keeps the default.

## The two forms

`Component.forms()` answers `new Forms(connection, flow, reach)`:

- **The connection form** describes what is the same for every flow that talks to one system: where it is and how to log
  in, such as a URL, a host and port, a user, a password. A secret field (`Field.secret`) is never handed over as text: the
  adapter reads it through `Connection.secret(name)`, a `Secret` whose text is only its reference. See
  [Secret fields](#secret-fields).
- **The flow form** describes what one flow sets on its use of the system: a path, a directory, a polling interval.
- **The reach** says, for each direction the adapter serves (`IN`, `OUT`), whether a use of that direction reaches a
  connection (`Reach.CONNECTION`) or not (`Reach.NO_CONNECTION`). A direction missing from the map is one the adapter does
  not serve, and an endpoint of it is refused at deploy. Only the platform's own internal adapters, `direct` and `mock`,
  send without a connection: a plugin whose out direction reaches none is refused when the node starts.

A field is built with `Field.text`, `Field.integer`, `Field.bool`, `Field.secret` or `Field.choice` (its values made with
`Choice.of(value, label)`), each with a name, a short English label and a help text, then narrowed with `.in()` or
`.out()`, `.required()`, `.byDefault(..)`, `.min(..)`, `.max(..)`, `.range(..)`, `.pattern(..)`, `.tab(..)` and
`.visibleWhen(..)`. The node sets `flow` and `maxBodyBytes` itself, so no form has a field of those names. On a connecting
out endpoint `maxBodyBytes` is the reply's limit, which the node hands in each call.

What the node hands the adapter:

| Type | What it is |
| --- | --- |
| `Inbound(flow, connection, settings, maxBodyBytes)` | a sender: the flow it feeds, the connection when its direction reaches one, the flow form's values, the body limit; `consumer(in, handler)` builds its consumer |
| `Outbound(connection)` | what a producer is built for: one connection, or none on an internal adapter; `producer(out)` builds one producer per connection |
| `Connection(key, values, secrets)` | the connection form's values, a secret field's under `secrets`; for an endpoint file the key is the endpoint's id |
| `Call(flow, step, settings, maxReplyBytes, address)` | one send: the flow and step that send, the flow form's values of the step's endpoint and the reply's limit; `send(e, call)` |

Both forms give each value its default when the endpoint file sets none. A component whose connection form describes
nothing (`Settings.NONE`) takes every key the flow form does not, as it is written.

The UI reads the forms from `GET /admin/components`, format 2: for each scheme its `reach`, the fields of each form with
their label, help, type, default and rules, and the tabs. Every label has a text key that the UI's translations may
override: `<scheme>.field.<name>.label` and `<scheme>.field.<name>.help` for a field, `<scheme>.field.<name>.choice.<value>`
for a choice, `<scheme>.tab.<id>` for a tab of the adapter's own and `rail8.tab.<id>` for a common one. A text key without a
translation shows the label as the adapter wrote it. The scheme `rail8` is the platform's own and no adapter may take it.

### Secret fields

A secret field names the kinds of secret it takes, in the order the adapter prefers them:

```java
Field.secret("password", "Password", "The user's password, or the user and the password together.",
        Secret.Kind.VALUE, Secret.Kind.CREDENTIALS)
```

`Field.secret(name, label, help)` with no kind takes a plain value (`Secret.Kind.VALUE`). The forms serve the kinds as
`"kinds": ["value", "credentials"]`. A kind is one of:

- `VALUE`, a plain text, read with `value()`;
- `CREDENTIALS`, a user and a password, read with `credentials()`;
- `KEY_PAIR`, a private key, its public key and its certificates, read with `keyPair()`.

In an endpoint file a secret field holds a reference, `secret://<name>`, to a secret in the node's secret store. Its kind
must be one the field takes, or the deploy is refused naming the endpoint, the option and the secret, never its value.
A field that takes `VALUE` also takes a plain value written in the file. Any other option takes only a reference to a
plain value.

The adapter finds the handle under `connection.secret(field)` and asks `kind()` which kind it holds; asking `kind()` or
`reference()` reads nothing. It reads the value once at each connection, login, session or handshake, and uses that one
value for the whole of it. It never reads a handle while it builds a consumer or a producer, and keeps no value beyond
one session: the contract suite fails an adapter whose build reads a handle. A secret overwritten in the store is then
what the adapter's next session uses, with no new deploy. A secret deleted and created again with another kind fails
the handle's next read, naming both kinds, until the flow is built again.

`rail8 validate` and `rail8 test` hand out placeholders: a producer is built with the placeholder of each kind its secret
fields take, in their order, until one builds.

A problem the node finds in an endpoint's config is one line, `endpoint '<id>' config (environment <env>) at '<path>':
<message>`, and in the forms' own check (`Settings.check`) each problem has a stable code: `required`, `unknown`, `min`,
`max`, `pattern`, `choice`, `type`, `direction` or `exactlyOne`. An endpoint file of `schemaVersion: 1` keeps what it may
hold today: a connection field's `pattern` is not checked on it, so an http URL with user info still deploys.

A plugin is built against one version of `rail8-api`, and a node loads only plugins of its own: a plugin built on api 0.3
is refused with a line naming its version and saying to build it again on the node's. Build an older adapter again on
this contract: replace `settings()` with `forms()`, take `Inbound` in `consumer` and `Outbound` in `producer`, and send with
`send(e, call)`.

Upgrade every node before polling flows run. A node leases a polling flow under the key `<package>/<flow>/from`, and a node
of the release before takes another key, so the two would poll one directory together.

Two endpoint files of `schemaVersion: 1` that deployed before are refused now, each with `is required`: an `http` out
endpoint without `url`, and an `sftp` endpoint without `host`. Both took the endpoint's id as the host before. Set `url`
or `host` in the endpoint's `config`. An out endpoint's `maxBodyBytes` is optional on every adapter: without it, the
reply's limit is the flow's `limits.maxBodyBytes`.

## Test it

```sh
mvn -f ~/acme-adapter/pom.xml verify                                     # the contract tests
bin/rail8 validate --plugins ~/acme-adapter/target/plugin ~/acme-adapter/package
bin/rail8 test --plugins ~/acme-adapter/target/plugin ~/acme-adapter/package   # the flow tests, the mock in the adapter's place
```

## Run it with the UI

```sh
bin/run-adapter ~/acme-adapter
```

It builds the adapter's jar, starts an empty Postgres of its own in Docker, starts a node with the adapter's
`target/plugin` as its plugins (admin API on `127.0.0.1:18081`, with an emergency key and a secret store's key made for
the run, as `openssl` makes it), makes the node's
first users with `rail8 bootstrap`, deploys `package/` as the adapter's `HEAD` commit holds it
(the node reads the folder's repository, with `RAIL8_GIT_PATH=package`), starts a partner that answers every
request with the body it was sent on `127.0.0.1:18091`, and runs the UI on **http://localhost:5173**. The UI needs no
login: it runs as the technical account `deploy`, which the script gives the roles `operator` and `message-bodies` so that
Messages reads what the node stored. Its token is in `target/run-adapter/token`. The node's HTTP
port, where the adapter's in endpoints are served, is `127.0.0.1:18090`. The ports 18081, 18090, 18091 and 5173 must be
free; the quickstart's node may keep running, its UI may not.

- **Packages** lists `package` under the folders that are no package, since git keeps the adapter's whole folder, which
  is the run's working folder. Its tab lists the package's flows and endpoints; an endpoint opens in its form, drawn from
  your forms, and Ctrl+K finds it too. Edit, change a setting, Check, and Save writes the file in your folder.
- **Send a test message** through the adapter. For the template's package, from another terminal:

  ```sh
  curl -X POST --data-binary @$HOME/acme-adapter/package/fixtures/order.xml -H 'Content-Type: application/xml' localhost:18090/acme/orders
  ```

  The flow keys the order by its id and sends it out through the adapter's producer to the partner, whose answer is the
  reply: `<Order><Id>7</Id></Order>`.
- **Messages** finds it: key name `orderId`, value `7`. Its timeline shows the steps `ingress`, `orderId` and `partner`.

Ctrl+C stops everything and removes the Postgres container. The node's log is `target/run-adapter/node.log`. Every run
starts empty: after a change to the adapter, or a Save you want deployed, commit it and run `bin/run-adapter` again. When
`package/` differs from `HEAD`, it says so in one line before the deploy: the uncommitted changes are not deployed.

## Install it on a node

Put the jar, or the folder of jars, into the folder that `RAIL8_PLUGINS` names and restart the node. The node logs the
adapter's name, version, api version and the SHA-256 of its jars.
