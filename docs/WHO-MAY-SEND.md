# Who may send to a flow

This document is the agreed design of the entry of a message:
- how a calling system proves who it is, and how its credential is kept and found on every request;
- the contract by which an adapter hands over what a caller presented;
- how a flow says who may send to it, how the check is switched off, and the limit per caller;
- the answers a refused caller gets, and brute force and enumeration at the entry;
- API products and subscriptions;
- a test message sent by a user with the role Message sender;
- the sender and the receivers written on every message, and what monitoring searches by.

It is built in the steps listed at the end, and no step reworks what an earlier one built.

Today the HTTP adapter checks no caller: whoever reaches the node's HTTP port may send to every flow on it. Users,
roles and tokens exist for the admin API only (`docs/USERS-AND-ROLES.md`), and a message records no sender.

The design stands on three others and uses their names:
- **Systems and connections** (`docs/SYSTEMS-AND-CONNECTIONS.md`). A system's key is a partner and a system, the
  partner optional: `eshop`, `acme/erp`. That design defines a flow's sender, the `Inbound` a consumer is built for,
  and the facts each send hands over about its receiver.
- **The configuration of an environment.** Objects with a draft, an activation, a history and a binding under the
  secret store's key; an immutable snapshot on each node, so that no message reads Postgres for it. Credentials, API
  products and subscriptions are kinds of its objects.
- **The secret store** (`docs/SECRET-STORE.md`). A secret's value is entered only by the secret administrator, and a
  setting holds a caller's password or key only as a reference to a secret.

## Rules

- A credential is what a system proves itself to Rail8 with. It belongs to one system, a system may have several, and
  it is entered in the environment. It is apart from the users of the platform: a credential never logs in to the UI
  or the admin API, and a user's session never sends a message.
- How a caller proves itself is never in the flow. The same flow file takes a password in test and a certificate in
  production.
- At the entry, always in this order:
  1. the platform finds the credential that what the caller presented matches; none found, the caller is refused as
     unknown and no message exists;
  2. the platform checks that this system may send to this flow; if not, the caller is refused and no message exists;
  3. the sender is written to the message.
- The flow says who may send: Named systems, Subscribers or By agreement. Every listening sender says it. The check is
  off only where the flow says so, with a reason.
- Rail8 never holds a caller's password or key in a form it could send on. It holds a verifier: a fingerprint of a
  generated value, a slow hash of a typed password, or a keyed fingerprint of a typed key.
- No request reads Postgres to find a system's credential; a Message sender's personal token is the one exception. The
  entry's other contact with Postgres is the count of a limit per caller, a test message's audit row, and the sender's
  row on the message.
- What a caller presented never reaches a step, a record or a log line.
- An unknown caller learns nothing. Not which name exists, not which part was wrong, not which paths a flow serves.
- Every message records its sender and every receiver, whatever the flow records.

## Credentials

### Kinds

| Kind | The caller presents | The credential holds | A secret | Found by |
| --- | --- | --- | --- | --- |
| Name and password (`basic`) | `Authorization: Basic` with a user and a password | the user, and the password as a reference to a verifier | the verifier | the id inside a generated password; the user for a typed one |
| API key (`api-key`) | `X-API-Key: <key>`, or `Authorization: Bearer <key>` for a generated key | the key as a reference to a verifier | the verifier | the id inside a generated key; a keyed fingerprint for a typed one |
| Client certificate (`certificate`) | the TLS peer chain, or the certificate a trusted proxy forwards | a pinned leaf certificate, or an issuing CA and a subject | none: certificates are public | the SHA-256 of the leaf's DER; or the issuer and the subject |
| OAuth2 token (`oauth2`) | `Authorization: Bearer <JWT>` | an issuer and the client's id | none: the issuer's keys are public | the token's issuer and client, after its signature verifies |

- Later kinds: the signature of a webhook, and an AS2 certificate. Each is a proof over the body, so the caller is
  checked once the body is staged: within the flow's `maxBodyBytes` and the 5 minutes a handler lives, and left to the
  retention job on a refusal, as every stored body is. The step that adds them says the rest, and amends the contract
  suite's rule that `enter` comes before any byte is stored.
- A system rotates a credential by having two for a while: a second is added and activated, the caller switches, and
  the first is removed. A basic caller that keeps its user has the two credentials share it.

### The credential object

A credential is an object of the configuration of an environment. Its kind is `credential` and its key is the system's
key and one more segment, the credential's name: `eshop/main`, `acme/erp/edi-2026`. The system's key is every segment
but the last. The connection administrator writes it, through the action `config.connections`, and the same draft,
activation, history, removal and export apply as to every object.

```yaml
# credential/acme/erp/main.yaml
schemaVersion: 1
kind: environment.credential
key: acme/erp/main
type: basic
user: acme-erp                       # one system's; at most 2 of its credentials share it
password: secret://acme-erp-main     # a verifier
```

The other types differ only in their fields:

| `type` | Fields |
| --- | --- |
| `basic` | `user`, `password: secret://<a verifier>` |
| `api-key` | `apiKey: secret://<a verifier>` |
| `certificate` | `pinned: <PEM of the leaf>`, or `issuer: <PEM of a CA>` and `subject: <a distinguished name>` |
| `oauth2` | `issuer: <the key of an issuer object>`, `client: <the client's id in the token>` |

- **Activation checks** that:
  - the system is defined in the installation, by a deployed package or by the deploy at hand;
  - every secret is set, and is of the kind and form its field takes;
  - a basic user belongs to one system, and at most 2 of that system's credentials share it;
  - typed keys' fingerprints, pinned fingerprints, (issuer, subject) pairs and (issuer, client) pairs are unique in the
    environment;
  - a verifier is named by one credential only, and no other credential holds its generated id;
  - certificates parse;
  - a system holds at most 16 credentials.
- Each credential's secrets are written to `secret_uses` through the store's one method, as `credential` and the
  credential's key, at its activation.
- **The binding.** The credential's body is bound under the store's key, as every body in force is. A database writer
  cannot plant a credential, a certificate or another system's verifier unseen. A credential whose tag fails is absent
  from the node's snapshot, and its caller is refused as unknown. The binding stops planting, not restoring: a database
  writer can put back an older bound row, a removed credential with its verifier among them, as the configuration of
  an environment accepts for every object. Users, roles and tokens are not bound (`docs/USERS-AND-ROLES.md`), so a
  database writer can still make a user who sends test messages, as it can make any administrator.
- **Without the store's key** no verifier can be written (503 `no-key`), so no `basic` or `api-key` credential can be
  activated. The four kinds that grant entry, `credential`, `issuer`, `api-product` and `subscription`, are held as
  bodies that send a secret, though they need no consent: in a deployed environment without the key their activation
  is 503 `no-key`, and when a key is set for the first time no row of theirs is bound. Such a row stays left out until
  it is activated again under the key, so nothing that grants entry is ever bound as it stands. The quickstart holds no
  store key, so its listening senders keep no check (`schemaVersion: 1`, or `{check: off, reason}`) until it does.
- **An `issuer` object** (kind `issuer`, a key of one segment) holds an OAuth2 issuer: `{url, audience, clientClaim,
  type?}`, where `clientClaim` is `azp`, `client_id` or `sub`. The issuer's URL is bound under the store's key with its
  body.
  - **Its keys.** The node reads the issuer's JWKS over HTTPS every hour, and for an unknown key id at most once a
    minute, one read at a time an issuer, with a bounded size and a timeout. It keeps the last good keys. Keys not
    read again for 24 hours are not used, and the issuer's tokens are then 503 `busy`, so that a partner's client
    retries rather than drops a good token; that answer tells a stranger the issuer is configured here. For a node
    with no way out, the keys may be pasted into the object instead: they never go stale, and are retired by
    activating the object without them.
  - **Its tokens.** A token is taken only when it is signed with RS256, PS256, ES256 or EdDSA, its `iss` equals the
    issuer's URL, its `aud` holds the audience, it has an `exp`, and, when the object sets `type`, its `typ` is that
    type (such as `at+jwt`). An issuer that gives other tokens with the same audience needs `type`. `exp`, `nbf` and
    `iat` are read on the injected clock with 60 s of leeway. A node checks at most 16 signatures at once; beyond that
    the answer is 503 `busy`.

### The verifier, a kind of secret

The secret store gains a fourth kind, `verifier`, which answers its open point: a node checks a caller without holding
a value it could send on. A verifier has one of three forms.

| Form | Made by | Sealed in the store | Checked by |
| --- | --- | --- | --- |
| `generated` | the store: `rail8c_<id>_<secret>`, answered once | `{id, fingerprint}`, the SHA-256 of the whole string | the id, then `MessageDigest.isEqual` on the fingerprint |
| `password` | the secret administrator types 16 to 256 characters | an Argon2id hash with the users' parameters (`docs/USERS-AND-ROLES.md`) | Argon2id |
| `key` | the secret administrator types 16 to 256 characters | an HMAC-SHA256 under the store's lookup key | the fingerprint, looked up |

- **The generated value** has the form of the platform's other credentials: `<id>` is 16 lowercase hex digits and
  `<secret>` is 43 base64url characters, 256 random bits from `SecureRandom`. `POST /admin/secrets {name, kind:
  "verifier", form: "generated"}` answers it once, with a copy button in the UI, and nothing else ever holds it. It is
  what the credential's form offers first, and the only form until the typed forms ship. The store never issues an id
  that already exists, an overwrite of the secret included. The pattern that keeps credentials out of the logs takes
  the form `rail8c_` too.
- **The lookup key** of typed keys is 256 random bits that the store generates once and keeps in one row, sealed under
  the store's key and created with insert-or-keep, so two nodes never make two. A change of the store's key seals it
  again, so every fingerprint stays valid. Losing it means every typed key is entered again.
- **Who made it could send with it.** Whoever generated a value held it once, so the sender on a message holds against
  partners, not against the platform's own staff. The secret store's audit names who generated each value.
- **A typed value** is for a move that keeps the callers' password, as from SAP PO behind a proxy that keeps their URL.
  It must not start with `rail8`, so that it can never be mistaken for a generated value or a personal token. Whoever
  holds both the database and the store's key can test guesses of a typed value offline, as the store says of its
  backups; a generated value gives nothing to guess.
- A `basic` credential's password takes `generated` or `password`. An `api-key` credential's key takes `generated` or
  `key`. Activation refuses any other kind or form. With a generated password the presented user must still be the
  credential's user; any other user is an unknown caller.
- Nobody reads a verifier back, and it is never in a draft, a history, a diff or an export. Since a verifier can be
  sent nowhere, a credential needs no consent.

### Found on every request

Each node holds an index, built from its snapshot and the store's verifiers in memory, rebuilt and swapped as one
immutable object at every activation and every change of a secret it names:
- generated id → verifier fingerprint and credential;
- basic user → its credentials, at most 2, and their passwords' forms;
- typed key's fingerprint → credential;
- pinned certificate fingerprint, and (issuer, subject) → credential;
- (issuer, client) → credential, with the issuer's keys;
- for each API product: the systems subscribed to it.

A rebuild that finds two credentials for one lookup (a generated id, a typed key's fingerprint, a certificate, an
(issuer, client)), or two systems for one basic user, leaves all of them out, with a WARNING.

A flow's Allowed senders and its check come with its deployment: the node holds them with the flow's mount and swaps
them with it at every deploy, rollback and undeploy, so no request is checked under another deployment's rule.

The index decides each request in memory:
- An unknown id is compared against a fixed dummy fingerprint. So a generated value's known and unknown ids take the
  same time.
- A typed password costs one Argon2id hash. A node runs at most 2 for the entry at once, apart from the two of the
  logins, with no queue. The place is decided before the name is looked up: a basic password not of the generated form
  is 503 `busy` when no place is free, whatever its name. Only a hit in the cache below comes first, since it tells
  only the holder of the right password. An unknown user takes no place: its answer waits on a timer of its own for
  the node's median time of a hash, so known and unknown take about the same time. A node holds at most 256 such
  timers, and beyond that every typed basic request is 503 too. So a flood of made-up names can hold typed callers at
  503, never callers with generated values. A typed password whose user two credentials share is tried against both,
  at most two hashes.
- The hash and a personal token's check run on a virtual thread, never on the event loop; `enter` returns its future
  at once.
- A typed password that verified is kept in a cache on each node: an HMAC-SHA256 under a key the node draws at its
  start, over the credential's key, the verifier's version and the password. It holds at most 10,000 entries, each for
  at most 10 minutes, and it empties with every change of the credential or its verifier.
- A message entered under one index keeps its sender. A change activated after it reaches only the requests that
  enter after the new index is in force: on each node within one notice, or the 5 s tick when a notice is lost.
- A node that cannot read Postgres keeps its index, is `DEGRADED` after 30 s, and goes on taking a removed credential
  until it reads again. A removal is itself a write to Postgres, so none is activated while Postgres is away.

## Who may send, in the flow file

```json
"from": { "properties": {
  "senders": { "oneOf": [
    { "$ref": "#/$defs/senderKey" },
    { "type": "array", "minItems": 1, "maxItems": 64, "uniqueItems": true, "items": { "$ref": "#/$defs/senderKey" } },
    { "enum": ["subscribers", "by-agreement"] },
    { "type": "object", "required": ["check", "reason"], "additionalProperties": false,
      "properties": { "check": { "const": "off" }, "reason": { "type": "string", "minLength": 1, "maxLength": 200 } } }
  ] } } },
"limits": { "properties": {
  "perSender": { "type": "string", "pattern": "^[1-9][0-9]{0,8}/(s|m|h|d)$",
                 "description": "Messages one sending system may send to this flow in each second, minute, hour or day." } } },
"$defs": {
  "senderKey": { "type": "string",
    "pattern": "^(?!(rail8|subscribers|by-agreement)(/|$))[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63})?$" } }
```

```yaml
from: { adapter: http, settings: { address: /orders }, senders: eshop }              # one system
from: { adapter: http, settings: { address: /invoices }, senders: [eshop, acme/erp] } # Named systems
from: { adapter: http, settings: { address: /v1/orders }, senders: subscribers }     # Subscribers
from: { adapter: http, settings: { address: /status }, senders: { check: off, reason: "public status page" } }
limits: { perSender: 600/m }
```

- **A listening sender** is one with no connection that is not internal, such as `http` or a plugin's `webhook`. It
  must name `senders`; `rail8 validate` says "name the systems that may send, or switch the check off with a reason".
  An adapter whose `reads()` is empty accepts only `{check: off, reason}`.
- **Named systems** are one system key or a list. Each must be defined in the installation, else the deploy is refused,
  as for connection keys. A named system with no active credential is named in the deploy's answer, not refused, and
  `GET /admin/flows` says that no allowed sender of the flow has a credential.
- **`subscribers`** admits every system that holds a subscription to an API product that holds this flow.
- **`by-agreement`** is for partner communication and is refused until that is designed.
- **`{check: off, reason}`** is the only way off. The deploy's answer, its record and `GET /admin/flows` list every flow
  with the check off, with its reason. Nothing in an environment forbids it: the review of the package in git and the
  deploy's record are the guard.
- **A polling sender** (with a connection) has no `senders`: its sender is the system of its connection. Naming one is
  refused.
- **`direct`** has no `senders`: a call between flows keeps the caller's sender, and counts on no limit per caller.
  Naming one is refused.
- **`limits.perSender`** applies to a listening sender whose check is on. It is refused with the check off, on a polling
  sender and on `direct`.
- The design of systems and connections reserves `subscribers` and `by-agreement` as first segments of a key, so that
  `senders` reads one way.
- A flow of `schemaVersion: 1` has no `senders` and keeps no check, until it moves to the shape of systems and
  connections. The deploy's answer and `GET /admin/flows` list each such flow with a listening sender as "check off:
  schemaVersion 1". In a deployed environment a deploy refuses a version-1 flow whose listening sender is new, or whose
  adapter or address changed against the deployment in force: it moves to `schemaVersion: 2` and names its senders.

## The adapter's contract

`rail8-api` gains these types, beside the consumer of the design of systems and connections:

```java
package dev.rail8.api;

/** The kinds of credential a caller can present. */
public enum CredentialKind { BASIC, API_KEY, CERTIFICATE, OAUTH2 }

/**
 * What the caller of one message presented, as an adapter read it. Each record overrides toString, which shows no
 * password, key or token.
 */
public sealed interface Presented {
    Via via();
    record Basic(String user, String password, Via via) implements Presented {}
    record ApiKey(String key, Via via) implements Presented {}
    /** A chain whose key the caller proved in a TLS handshake: the listener's own, or a trusted proxy's. Leaf first. */
    record Certificate(List<X509Certificate> chain, Via via) implements Presented {}
    record Bearer(String token, Via via) implements Presented {}
    record Nothing(Via via) implements Presented {}

    /**
     * How one request reached the node.
     *
     * @param remote the caller's address: the peer, or the client a trusted proxy names. Failures are counted by it.
     * @param secure over the listener's own TLS, from a trusted proxy, or from loopback
     * @param browser the request carries Origin, Referer, or Sec-Fetch-Site with any value but none
     * @param senderChoice the one Rail8-Sender header
     */
    record Via(String remote, boolean secure, boolean browser, Optional<String> senderChoice) {
        /** For a listener of an adapter's own, which reads no browser and no Rail8-Sender. */
        public static Via of(String remote, boolean secure) { return new Via(remote, secure, false, Optional.empty()); }
    }
}

/** The entry of one listening sender of a flow. The node makes it; a consumer built with it enters every message. */
public final class Entry {
    /** An entry over a gate: for the node and the testkit. */
    public static Entry of(Function<Presented, Future<Function<Envelope, Future<Ack>>>> gate);

    /**
     * Hands over what the caller of one message presented, before any of the message's body is read. Completes with the
     * handler of that one message, or fails with Refused. Never blocks, and may be called on any thread; called on a
     * Vert.x context, its future completes on that context, so the caller may touch its request there. The handler
     * takes one envelope of this entry's flow, once.
     */
    public Future<Function<Envelope, Future<Ack>>> enter(Presented presented);
}

/** A refusal at the entry. No message exists. */
public final class Refused extends RuntimeException {
    public enum Reason { UNKNOWN, NOT_ALLOWED, SENDER_NEEDED, TOO_MANY, BUSY, TLS_REQUIRED }
    public Refused(Reason reason, Optional<Duration> retryAfter);   // the platform's and the testkit's
    public Reason reason();
    public String code();                              // unknown-caller, not-allowed, sender-needed, too-many, busy, tls-required
    public Optional<Duration> retryAfter();            // TOO_MANY and BUSY
    /** Ends the request with the whole HTTP answer; a request with a body has its connection closed, or its HTTP/2 stream reset. */
    public void answer(HttpServerRequest request);
}

public interface Component {
    // … the contract of systems and connections, consumer(Inbound, Function<Envelope, Future<Ack>>) among it
    /** The kinds of credential this adapter's consumers read from callers. */
    default Set<CredentialKind> reads() { return Set.of(); }
    /** The consumer of one sender, which hands each message over through the entry. */
    default Consumer consumer(Inbound in, Entry entry) {
        return consumer(in, e -> entry.enter(new Presented.Nothing(Presented.Via.of("", false))).compose(h -> h.apply(e)));
    }
}

// in dev.rail8.api.plugin
public interface HttpIngress {
    // … as today
    /** The kinds the port reads for every adapter on it. */
    Set<CredentialKind> READS = Set.of(CredentialKind.BASIC, CredentialKind.API_KEY, CredentialKind.CERTIFICATE, CredentialKind.OAUTH2);
    /**
     * What the caller of a request this port handed to a mount presented. The first proof present decides, in this order:
     * Authorization (Basic, or Bearer), then X-API-Key, then the client certificate. It reads the head and the TLS session
     * only, never the body. The node's port overrides it; the default presents nothing, which a check refuses.
     */
    default Presented presented(HttpServerRequest request) { return new Presented.Nothing(Presented.Via.of("", false)); }
}
```

- **The node builds every sender's consumer through `consumer(Inbound, Entry)`.** The default keeps an adapter built on
  the contract of systems and connections compiling: it stores the body, then enters with `Nothing`. Such an adapter
  reads no credential, so a flow can use it as a listening sender only with the check off, and it answers a refusal as
  it answers a failed handler.
- **A sender with a connection, and `direct`,** are never refused at the entry: `enter` completes at once, with the
  system of the connection or the caller's sender, and the flow's room is taken at delivery as today. The node refuses at
  registration a component whose `reads()` is not empty and that does not override `consumer(Inbound, Entry)`.
- **An adapter that overrides it holds no handler of its own.** A message reaches the flow only through the handler
  `enter` gave, so an adapter cannot deliver around the check or write a sender. This guards against mistakes, not
  against a hostile plugin, which runs with the node's rights.
- **`Entry` is a class, not a functional interface,** so a lambda passed to `consumer(in, …)` in today's adapters and
  tests still means the handler.
- **The default of `HttpIngress.presented`** keeps `HttpIngress.NONE`, a lambda, compiling, and fails closed.
- **Enter first.** A consumer calls `enter` before it reads any byte of the body, and a refusal's message is never
  stored. The HTTP port pauses the request; an adapter keeps it paused until `enter` completes.
- **A handler lives until its body is received,** and the body must end within 5 minutes of the entry. On that
  timeout, a disconnect or the end of the mount, the platform drops the handler; no message refers to the partial
  body, which the retention job removes by age like every body. An upload slower than 5 minutes fails. A handler never applied holds nothing else and needs no release.
- **Errors.** `enter` fails only with `Refused`. Any other failure is a fault of the platform; the adapter answers it as
  it answers a failed handler. The handler fails with `IllegalArgumentException` for an envelope of another flow and
  with `IllegalStateException` when applied twice.
- **A presented value is bounded.** A user of more than 256 characters, a password, key or token of more than 16 KiB, or
  a chain of more than 10 certificates is unknown, with no lookup.
- **A kind before its step.** Until the step that builds a kind's lookup, a caller that presents it is an unknown
  caller, with no lookup.
- **What the HTTP port reads.**
  - A trusted proxy is the peer of the socket, an address in `RAIL8_HTTP_TLS=proxy=`. The port keeps the peer apart from
    the client it names, and `via.remote` is the client's.
  - The remote address is the peer; from a trusted proxy, it is the right-most address of `X-Forwarded-For` that is not
    a trusted proxy. A malformed `X-Forwarded-For` from a trusted proxy leaves the proxy's own address.
  - The client certificate is the TLS peer chain, or the one a trusted proxy forwards in its header together with the
    proxy's secret (`RAIL8_HTTP_PROXY_SECRET`). From any other address, or without the secret, those headers are
    ignored. Two certificate headers are no certificate, and when the peer is a trusted proxy its own TLS chain is
    never the client's.
  - A CA and subject credential takes a chain of at most 10 certificates and 64 KiB, validated to its CA, with the
    validity read on the injected clock and client authentication required where the key usage is set. A leaf that
    matches two systems, by a pin or by a CA, is unknown.
  - The hop between the proxy and the node must be private or protected: on an open hop, the proxy's secret and every
    forwarded identity are exposed. The node cannot check that, before HTTPS on the HTTP port ships.
  - `via.browser` is set from `Origin`, `Referer` and `Sec-Fetch-Site`; `via.senderChoice` is the one `Rail8-Sender`.
    The port answers a request with more than one `Rail8-Sender` itself, 400 `sender-needed`, before any mount sees it.

An HTTP adapter on the node's port, such as `http`, the `webhook` sample and the adapter template:

```java
@Override public Set<CredentialKind> reads() { return HttpIngress.READS; }
@Override public Consumer consumer(Inbound in, Entry entry) { return new HttpConsumer(in, entry); }

private void handle(HttpServerRequest req) {                      // the event loop; no byte of the body is read yet
    req.pause();
    entry.enter(ctx.http().presented(req)).onComplete(entered -> {
        if (entered.failed()) {
            if (entered.cause() instanceof Refused r) r.answer(req); else req.response().setStatusCode(500).end();
            return;
        }
        streamBodyThenRun(req, entered.result());                 // as today, with the handler enter gave
    });
}
```

An adapter with a listener of its own, such as MLLP over TLS, declares `reads()` as `Set.of(CERTIFICATE)` and enters
each message with the peer's chain:

```java
Presented presented(NetSocket socket, String remote) {
    Presented.Via via = Presented.Via.of(remote, socket.isSsl());
    try {
        List<X509Certificate> chain = socket.peerCertificates().stream().map(X509Certificate.class::cast).toList();
        return chain.isEmpty() ? new Presented.Nothing(via) : new Presented.Certificate(chain, via);
    } catch (SSLPeerUnverifiedException none) {                    // the peer sent no certificate
        return new Presented.Nothing(via);
    }
}
```

It answers a refusal in its own protocol, from `reason()` and `retryAfter()`. A polling adapter and `direct` present
nothing: the platform gives a poller's message the system of its connection, and a callee the caller's sender.

## At the entry

The order of one request on the node's HTTP port, all of it platform code. Steps 1 to 3 and 5 to 7 run inside `enter`;
on a path no mount holds, the port runs steps 1 to 3 itself before step 4. A path held by a deployed flow whose check is
off, a version-1 flow among them, but with no mount at the moment is answered 503 `busy` with `Retry-After` to every
caller, before step 1, as today.

0. The port's own admission: the requests in flight per node and per remote address. It is designed apart; its answer
   is 503.
1. **Clear text.** A request that presents a credential and whose `via` is not secure is 403 `tls-required`, unless
   `RAIL8_HTTP_TLS=off`. The credential is not looked at.
2. **Failures.** A presented name or id whose own row waits is 429 `too-many` with `Retry-After`, and nothing is
   looked up. A remote address whose row waits holds only the costly checks: a typed password, a personal token and,
   later, a token whose key id the issuer's keys lack. A generated value is always looked up.
3. **Find the credential (1).** A `rail8m_` password takes the personal token's path (below). Anything else is looked up
   in the index. None found is 401 `unknown-caller`, and a failure is counted on both rows. An attempt is charged to
   its rows before its check and refunded when it succeeds, so attempts at once cannot pass the free failures.
4. **The path.** The port answers a path no mount holds only now: 404 for a path no deployed flow serves. For a path
   held by a deployed flow with no mount at the moment, it checks the Allowed senders of the deployment in force first:
   403 `not-allowed` for a caller the flow does not allow, else 503. An unknown caller has already had its 401, on
   every path.
5. **Allowed senders (2).** The system must be one the flow allows: else 403 `not-allowed`. A personal token picks the
   system as described below, or is 400 `sender-needed`.
6. **Room.** The flow's admission is looked at, without taking a permit. No room is 503 `busy` with `Retry-After`, and
   nothing is counted.
7. **The limit per caller.** One message is counted on (flow, system), a test message on (flow, tester). Over the
   limit is 429 `too-many` with `Retry-After`.
8. **The handler.** The adapter streams the body; its own refusals are its own (405, 413). Delivery takes the flow's
   permit by the body's real size, as today, and a flow that filled meanwhile answers 503. Such a message was counted
   at step 7, and its stored body is left to the retention job. The port's own admission bounds the uploads in flight; until it is
   built, nothing does.
9. **The sender is written (3)** with the arrival's save (at-least-once), or with the run's first records (`sync`,
   `at-most-once`).

- `100 Continue` is answered only after step 7. A refusal at steps 1 to 7 of a request with a body closes the
  connection (an HTTP/2 stream is reset), so a refused body is never read. A request with no body keeps its
  connection.
- **What waits inside the entry is bounded:** at most 1,000 requests a node and 32 a remote address, 503 `busy` beyond.
  A disconnect frees the request, its timers and its place. A personal token's read has a deadline of 1 s, then 503
  `busy`.
- **The proof is removed.** Before the first step and the first record, the platform removes `authorization`,
  `x-api-key`, `apikey`, `rail8-sender`, `rail8-proxy-secret` and the trusted proxy's certificate header from the
  envelope, every copy in any case. It cuts the platform's own cookies (`rail8-session`, `__Host-rail8-session`,
  `rail8-device`, `__Host-rail8-device`) out of `cookie`, because cookies are not kept apart by port. It does so
  whether they matched or not, with the check on or off.
- **A reply never sets the platform's cookies.** A reply on the HTTP port never carries a `Set-Cookie` for those four
  names. A flow can still set other cookies on a host it shares with the UI, so the UI and the HTTP port are best on
  separate hosts.
- A refusal is logged as one WARNING per flow, reason and system or remote address a minute, and at most 100 lines a
  minute a node, then one line with the count of the rest. It is counted per flow, reason and known system over the
  last 24 hours, in memory on each node, and unknown callers count under one entry. The line names the flow, or
  `unmatched` for a path no mount holds; never the path, the query or what was presented.

With the check off, the entry never refuses on who the caller is and counts no failure. A typed password is not
hashed there: its caller has no sender. Steps 6 and 8 still hold.

| The caller presents | The message's sender | Its proof |
| --- | --- | --- |
| a credential found in the index | the credential's system | its kind |
| a valid personal token | the system `Rail8-Sender` names, any defined system; none without the header | `token` |
| nothing; an unknown, wrong or waiting credential; a typed password; a credential in clear text | none | `none` |

## The answers

| Status, code | When | Headers | Never says |
| --- | --- | --- | --- |
| 401 `unknown-caller` | nothing presented; a malformed, unknown, wrong or removed credential; a personal token that fails its check, or test sending off; an unknown path | `WWW-Authenticate: Basic realm="rail8", charset="UTF-8"` | which of these, whether a name, an id or a flow exists |
| 403 `not-allowed` | a known caller that this flow does not allow | | the systems allowed, the products, any other system |
| 400 `sender-needed` | a personal token on a flow that allows several systems, without `Rail8-Sender`; more than one `Rail8-Sender`, from the port | | the systems allowed |
| 404 `not-found` | a known caller on a path no deployed flow serves | | |
| 429 `too-many` | a failure row waits; over the limit per caller | `Retry-After` | other callers' counts; whether a name exists |
| 503 `busy` | the port's own admission; the entry's own bounds; a flow with no mount, to a caller it allows; no room in the flow; no place to hash, to check a token or a signature; a token read past its deadline; an issuer's stale keys; a count that cannot be claimed | `Retry-After` | |
| 403 `tls-required` | a credential in clear text | | |

- The body is `{"code": "…", "error": "…"}`, the error one fixed English text per code. The reason, the system and
  the address go only to the log and the counts.
- A known caller learns that a flow it may not send to exists. It learns nothing about who may.

## Brute force and enumeration

- **Generated values give nothing to guess.** A password or key of 256 random bits is found by its id, its fingerprint
  compared in constant time, and an unknown id is compared against a dummy.
- **Failure rows** are kept in memory on each node:
  - one for each (presented name or id, remote address), whether the name exists or not, with 5 free failures. Its key
    is the presented user, the generated id, or the SHA-256 of a typed key, never the value;
  - one for each remote address, with 100 free failures. It holds only the costly checks (step 2), so a caller with a
    generated value is never held by another caller's failures from its address;
  - an IPv6 address counts by its /64;
  - after the free failures, each attempt waits 1 s after the last failure, doubling up to 300 s;
  - a success forgives the failures of its own row only, so an attacker at another address cannot lock a partner out;
  - a row is dropped 24 hours after its last failure; each table holds at most 100,000 rows, and a full table drops
    first the rows that do not wait, then the row whose wait ends first.
  On a pool of several nodes each node counts on its own, so an attacker gets that many times the attempts. Without
  `proxy=`, every caller behind a proxy has the proxy's address and shares its row, as callers behind one NAT share a
  (name, address) row. A partner whose row a stranger at the same address holds recovers with a second credential of
  its system, under another id or user.
- **Typed values across addresses.** The step that adds typed passwords and keys also bounds the guesses against one
  known user or key across all addresses (100 free failures a day). Its wait holds only for the addresses that never
  succeeded for that user: each node remembers up to 16 good addresses per credential for 30 days, so that a stranger
  who knows the name cannot lock the partner out at the addresses it sends from.
- **Equal answers.** Known and unknown get the same code, the same text and about the same time.
- **Paths stay hidden.** An unknown caller gets 401 on every path, so it learns no path a flow with the check on
  serves. A flow with the check off is found by anyone.
- **Personal tokens** are checked in at most 4 places a node, and the failure rows are read before the check, so a
  flood from one address is answered 429 without a read of Postgres. Made-up tokens from many addresses can hold the 4
  places; that delays testers, never a system's caller.

## The limit per caller

- **What is counted.** Each message that passed the entry's steps 1 to 6 counts once, on its flow and its sending
  system; a test message counts on its flow and its tester. A message counted and then refused at delivery (413, 503)
  stays counted. The window is fixed: `600/m` counts from the start of each minute of the injected clock, in UTC.
  Across a window's edge a caller can send up to twice the rate.
- **Where the count lives: Postgres**, the same with one node or many, with or without Valkey. Each node claims a slice
  of the window and admits within it. The slice is the limit divided by 8 times the live nodes, at least 1; the live
  nodes are the rows of `nodes` whose lease holds. With a slice of 1, every message is one write.

  ```sql
  create table entry_counts (
      flow         text        not null,   -- <package>/<flow>
      sender       text        not null,   -- a system key, or tester:<user name> for test messages
      window_start timestamptz not null,   -- the injected clock floored to the window; SQL never reads now()
      used         bigint      not null check (used >= 0),
      primary key (flow, sender, window_start));
  -- a claim of $k (the slice, or 1 when the slice no longer fits), on a virtual thread:
  insert into entry_counts as c (flow, sender, window_start, used) values ($1, $2, $3, $k)
  on conflict (flow, sender, window_start) do update set used = c.used + $k
    where c.used + $k <= $limit
  returning used;                          -- no row back: over the limit
  ```

- **The bound.** A window never admits more than the limit. It may admit fewer: by the unused part of each node's last
  slice, and by a whole slice for each claim whose reply was lost or whose node restarted. A fixed share per node would
  strand no slice, but would strand capacity when traffic is uneven; the slices were chosen for that.
- **The window and the clocks.** A slice is spent only in the window it names, by the node's injected clock. A claim
  that finds the window full is remembered until the window ends, since `used` never falls, and later requests get 429
  without Postgres. Each window's row never passes the limit; nodes whose clocks differ spend neighbouring windows at
  once, so what a caller sends across an edge grows with the skew.
- **A changed limit** applies to the claims made after the deploy reaches the node, and slices granted before are
  spent. During a rolling deploy a window admits up to the higher of the two limits.
- **Over the limit:** 429 `too-many`, with `Retry-After` the seconds to the window's end plus a random 0 to 2 s from the
  injected source, so that the callers held do not all return at once.
- **A claim that fails**, because Postgres does not answer within 1 s, is 503 `busy`. A lost count loses no message.
- Rows older than two windows are deleted with retention. Only flows with `limits.perSender` write here.
- **With admission.** The count comes after the flow's room is looked at, so a caller is not counted for a 503 the
  platform caused. The port's own admission comes before the whole entry.

## API products and subscriptions

Both are objects of the configuration of an environment, written by the partner administrator through the action
`config.partners`. Neither is in git.

| Kind | Key | Body | Activation checks |
| --- | --- | --- | --- |
| `api-product` | one segment, `orders-api` | `{description?, flows: ["<package>/<flow>", …]}`, at most 256 flows | each flow is deployed |
| `subscription` | the product's key, then the system's key: `orders-api/acme/shop` | `{}` | the product and the system exist |

- A flow with `senders: subscribers` admits every system with an active subscription to an active product that holds
  the flow. The index holds the systems per product.
- A product names flows by key. A flow that is not deployed is skipped, and when it is deployed again the product holds
  it again; the deploy's answer names the products that hold it. The flow file must still say `subscribers`.
- Both kinds grant entry, so in a deployed environment they need the store's key (above), and Subscribers works only
  with it.
- An API product's application is a system: the same object that has credentials. A customer with two applications has
  two systems under its partner.
- A system is defined in a package, as for every system. A new customer of an API is a commit and a deploy of its
  system file, then its credential and its subscription in the environment.
- An environment holds at most 1,000 API products and 20,000 subscriptions.

## Test messages

A user with the role **Message sender** sends a message to a flow from SoapUI, Postman or `curl`. It uses basic
authentication: the user's own name, and a personal token for sending (`rail8m_<id>_<secret>`) as the password. A
technical account that holds the role sends the same way, with one of its tokens.

- **A token for sending.** A token has one use, chosen when it is issued: `admin` (`rail8t_`), the default and the use
  of every token issued before, or `send` (`rail8m_`). Both are rows of the same table, with the column `use`. Since
  the fingerprint covers the whole string, its prefix included, a `send` token is never an admin token: the admin API,
  an older node's or a rolled-back one's included, never parses it, and the HTTP port takes no `rail8t_` token. So a
  token that leaks from a test tool opens nothing else. The pattern that keeps credentials out of the logs takes the
  form too.

- **The role and the action.** `message-sender` grants the new action `message.send`. A person or a technical account
  may hold it, a token may do it, and the emergency key may not. A developer does not hold it unless it is granted.
- **The admin API stays closed on the port.** The port never routes to `/admin`. It recognises only a `send` token, by
  its form, in the password of basic authentication. An `admin` token (`rail8t_`), a session (`rail8s_`), a device
  (`rail8d_`), the emergency key (`rail8k_`) and a token sent as `Bearer` are unknown callers there.
- **The check** is the users' own check: the token is parsed, then read by its id with its user and the user's roles
  in one query. Then the node compares the fingerprint in constant time, and checks expiry, revocation and the user's
  block. The row's use must be `send`, the name must be its user, and the user must hold `message.send`. A node runs
  at most 4 such checks at once, with no queue; beyond that the answer is 503 `busy`. So a token's revoke holds for
  the next request on every node; a credential's removal follows its activation (above).
- **No browser.** A request whose `via.browser` is set, because it carries `Origin`, `Referer`, or `Sec-Fetch-Site`
  with any value but `none`, has its personal token refused. SoapUI, Postman and `curl` send none of them, and a
  cross-site request from a page carries at least one, over plain HTTP too. What is left over plain HTTP is a
  navigation with no referrer, by a tester who typed the token into the browser's prompt; a `send` token limits what
  that gains.
- **Which system sends.**
  - The flow's one named system, by default.
  - The header `Rail8-Sender: <system key>` (`via.senderChoice`) picks another, and must name a system the flow
    allows; under `subscribers`, one that holds a subscription.
  - A flow that names several systems, or `subscribers`, needs the header: without it the answer is 400
    `sender-needed`.
  - With the check off, the header may name any defined system; without it the message has no sender.
  - The header counts only with a personal token. From any other caller it is ignored, and it is always removed.
    More than one copy is 400 `sender-needed`, from the port.
- **The record.** The message counts on the flow's `perSender` under its tester, never on the system's count. Its proof
  is `token`, with the token's id as the credential and the user's name as the tester. An audit row `message.send`
  (the actor, via the token, the token's id, the subject `message/<id>`, the flow and the system) is written before the
  handler runs. When it cannot be written, the answer is 503.
- **Switching test sending off.** `RAIL8_TEST_SENDING` opens or closes the path on a node; closed, a personal token is
  an unknown caller. It is `on` by default where `RAIL8_ENV` is `dev` or `ci`, and `off` in a deployed environment
  unless the operator sets `on`, because a test message has no flag and reaches the flow's real receivers. A test
  environment that testers send to sets `on`. The variable is the operator's and not a setting of the UI, so a stolen
  admin account cannot open it. `/health` shows each node's value, and a node left unset in a deployed environment
  stays closed.

## The record of the sender and the receivers

```sql
create table message_parties (
    message_id    text        not null,
    side          text        not null check (side in ('sender', 'receiver')),
    connection    text        not null default '',  -- a receiver's connection key; a polling sender's; '' otherwise
    instance_name text        not null default '',  -- as it held then; '' when the system has none
    mocked        boolean     not null default false, -- a receiver reached through the built-in mock
    flow          text        not null,
    at            timestamptz not null,             -- the entry; a receiver's first attempt
    partner       text,                             -- null: a system without a partner, or no sender
    system        text,                             -- null: no sender (the check off and nothing proved)
    proof         text        not null,             -- a sender's: basic, api-key, certificate, oauth2, token, connection, none;
                                                    -- a receiver's: tried, then sent once an attempt succeeded
    credential    text,                             -- the credential's key, the token's id, or the polling connection's key
    tester        text,                             -- a Message sender's user name
    remote        text,                             -- the caller's address, as the failure rows read it
    called_from   text,                             -- the calling message's id, for a flow called by another
    primary key (message_id, side, connection, instance_name, mocked));
-- the step that brings the searches adds the indexes they read:
create index message_parties_system     on message_parties (system, side, at desc);
create index message_parties_partner    on message_parties (partner, side, at desc) where partner is not null;
create index message_parties_credential on message_parties (credential, at desc) where credential is not null;
create index message_parties_tester     on message_parties (tester, at desc) where tester is not null;
```

- **The sender's row** is written in the arrival's transaction (at-least-once), or in the run's first group of records
  (`sync`, `at-most-once`). It is written at every recording level and with every payload setting, `off` included. A
  flow that records nothing else still writes one row for each message, plus one for each receiver, by the same
  writer. For `sync` and `at-most-once` the rows share the fate of the run's records: when Postgres is away, the run
  goes on, and its records, these rows among them, are lost, counted and logged. The sender's instance name is read
  from the entry's snapshot, which holds every system.
- **A receiver's row** is written at each attempted send, failed and mocked ones included, from the facts the send hands
  over: the connection key, the system key, the partner, the instance name as it held then, and whether it was mocked.
  There is one row per connection, instance name and mocked. A later attempt keeps it, and turns its proof from
  `tried` to `sent` once an attempt succeeds, never back.
- **A polling sender** writes the system of its connection, taken at intake, with the proof `connection` and the
  connection's key as the credential.
- **A call between flows** writes the caller's sender row again on the callee's message, with the caller's proof and
  `called_from` the calling message's id. The sender, its proof, credential and tester travel with the message's work
  across `direct`, retries and nodes, the empty sender of a flow with the check off included, so a retry on another
  node writes the same sender.
- **Retention** is that of the messages (`RAIL8_RETENTION_MAX_AGE`).
- **Monitoring.**
  - `GET /admin/messages` gains the parameters `sender`, `receiver`, `partner`, `instance`, `proof`, `credential` and
    `tester`. `sender` and `receiver` take a system key; `partner` matches the partner of either side.
  - `GET /admin/messages/{id}` gains `sender` and `receivers`.
  - A message is listed by its recorded arrival, or else by its sender's row. A message of a flow that records nothing
    shows its flow, its time, its sender and its receivers, with the status `unrecorded`.
  - `GET /admin/flows` gains each flow's allowed senders, its limit per caller, whether its check is off and why, and
    its counts of refusals by reason and known system over the last 24 hours.
    `GET /admin/components` gains each component's `reads`.

## Roles, actions and settings

- The role `message-sender` and the action `message.send` (above) join `docs/USERS-AND-ROLES.md`.
- `credential` and `issuer` are written through `config.connections`, by the connection administrator.
- `api-product` and `subscription` are written through `config.partners`, by the partner administrator.
  `config.partners` is that role's first write action.
- The secret store's create takes `kind: "verifier"` with a `form`, through `secret.write`.
- **`RAIL8_HTTP_TLS`**, as `RAIL8_ADMIN_TLS` is for the admin port:
  - unset: a credential is taken only over the node's own TLS (once HTTPS on the HTTP port ships), on loopback, or from
    a trusted proxy;
  - `proxy=<cidr>,…`: the addresses of TLS-terminating proxies. The node believes their `X-Forwarded-For`, and their
    client certificate header only with the secret below. Such a proxy sets both headers itself and drops what a
    caller sent in them. `RAIL8_HTTP_CLIENT_CERT_HEADER` names the certificate header, `X-Forwarded-Client-Cert` by
    default;
  - `off`: the network is protected another way. The node logs a WARNING at its start.
  Until HTTPS on the HTTP port ships, every deployment sets `proxy=` or `off`, and the quickstart's compose files set
  `off`.
- **`RAIL8_HTTP_PROXY_SECRET`** (or `_FILE`): 32 to 256 characters that the trusted proxy sends in `Rail8-Proxy-Secret`
  beside a forwarded certificate, compared in constant time. Without it the node ignores the certificate header. It
  is the one forwarded header that gives an identity, and an address range is no proof of it.
- **`RAIL8_TEST_SENDING`**: `on` or `off`; by default `on` where `RAIL8_ENV` is `dev` or `ci`, else `off`.
- `/health` gains `entry: {tls: "tls" | "proxy" | "off", proxySecret: true | false, testSending: "on" | "off",
  indexAgeSeconds}`, the last the age of the index the node applies.

## Open points the design proposes

- **Testing a flow that allows several senders.** In `rail8 test` a case takes `sender: <system key>`, by default the
  flow's first named system, and `expect.refused: unknown-caller | not-allowed | too-many`. In a running environment a
  Message sender picks the system with `Rail8-Sender`.
- **A test flag on a message.** None. A test message is found by its proof `token`, and by its tester. A test that
  must not reach a real receiver uses the built-in mock of a connection.
- **Callers and an API product's applications** are one object: the system, with its credentials and its
  subscriptions.
- **For the owner:** under the decided rule that every system is a file in a package, each new customer of an API is a
  commit and a deploy. Whether a system that only sends may also be created in the environment is the owner's to
  decide; this design holds to the rule as it stands.

## Moving from SAP

- **SAP PO.**
  - A sender channel's service user in UME, holding `SAP_XI_APPL_SERV_USER`, becomes a `basic` credential of the
    sending system. A move that keeps the URL keeps its password as a typed verifier; otherwise it gets a generated one.
  - A certificate mapped to a user becomes a `certificate` credential.
  - The users assigned in a sender agreement become `senders: [systems]`.
  - In PO any user with the role may send as any sender component, which the URL names (`senderService=`). Here the
    credential decides the sender, and a URL cannot change it.
  - A REST sender channel without authentication becomes `{check: off, reason}`.
  - The message monitor's search by sender party and component becomes `sender=`.
  - The test tool of the Runtime Workbench, or SoapUI with a PO user, becomes the Message sender.
- **SAP Integration Suite.**
  - An HTTPS sender's "User Role" or "Client Certificate" is set inside the iFlow. Here it moves out of the flow: the
    flow names the systems, and the environment holds how each proves itself.
  - The runtime's own service key, which SAP calls an anti-pattern for consumers, becomes one credential per calling
    system.
  - Tokens of the BTP XSUAA become an `issuer` and `oauth2` credentials, so callers keep their token flow.
  - In API Management, VerifyAPIKey or OAuth with a Quota per application becomes `subscribers` with `perSender`.
    Product, application and subscription become `api-product`, a system with an `api-key` credential, and
    `subscription`.
  - `SAP_Sender` and `SAP_Receiver`, free text set in a content modifier, become the sender and receivers recorded from
    the credential and the connection, which no flow's author writes.
- **Trading Partner Management and AS2** become `senders: by-agreement` later. At the entry the credential gives the
  system. After the interchange is parsed, the platform narrows the sender to the partner the envelope names, and it
  requires an active agreement with that partner, whose channel is the credential's system.

## Limits

| What | Limit |
| --- | --- |
| Credentials of a system | 16 |
| Named systems in a flow | 64 |
| A presented user; a password, key or token; a chain | 256 characters; 16 KiB; 10 certificates |
| A typed verifier | 16 to 256 characters |
| Basic credentials of one system that share a user | 2 |
| Argon2id for the entry | 2 at once a node, no queue; at most 256 unknown users waiting a node |
| The cache of typed passwords that verified | 10,000 entries, 10 minutes each |
| Personal token checks | 4 at once a node, no queue; a read of 1 s |
| Requests waiting inside the entry | 1,000 a node, 32 a remote address |
| A body after its entry | 5 minutes |
| Refusal lines | 100 a minute a node, then one line with the count |
| Failure rows | 100,000 per table a node; dropped 24 hours after the last failure |
| `perSender` | 999,999,999 a window |
| Flows of an API product | 256 |
| Credentials; issuers; API products; subscriptions, in an environment | 20,000; 64; 1,000; 20,000; drafts and removed objects count |
| An issuer's JWKS | 64 KiB, a 5 s timeout; read every hour, for an unknown key id at most once a minute, one read at a time; unused after 24 hours without a read |
| Signature checks of OAuth2 tokens | 16 at once a node |
| A chain to a CA | 10 certificates, 64 KiB |
| A claim of the count | 1 s |
| A proxy's secret | 32 to 256 characters |
| A removed credential keeps working | one notice, or the 5 s tick; on a node that cannot read Postgres and is still reached, until it reads |

## Tests

- **`ComponentContract`** gains these checks, for a component whose `reads()` is not empty and for its mock:
  - a message reaches the flow only through the handler `enter` gave, once per message;
  - `enter` comes before any byte is stored: the suite's blob store fails a write that comes before its request's
    `enter`;
  - a refused caller gets the refusal's answer, and nothing is stored or delivered;
  - each declared kind reaches `enter` as that kind: the suite presents the HTTP kinds itself, and an adapter with a
    listener of its own supplies a presenter for each kind;
  - no delivered envelope holds the presented password, key or token in its headers, props, keys or body.
- **The testkit's `FakeEntry`**, built with `Entry.of`, admits as a system or refuses with a reason, by script. It
  records every `Presented` and every delivered envelope, fails a second apply, and removes the same headers as the
  platform. `RecordingComponent` declares its real component's `reads()`. In `rail8 test` the platform's mock enters
  with the case's `sender`.
- **The entry's own tests** run on a fake clock and a fake timer, with Postgres in Testcontainers, and never sleep:
  - every reason, and the order of the steps;
  - the equal paths for known and unknown ids, users and keys;
  - the failure rows and their waits;
  - two nodes that claim slices never pass the limit, and a window turns over on the clock;
  - an index swapped while messages are in flight;
  - a removed credential refused after its activation, and a revoked token refused at the next request;
  - the audit row required for a test message;
  - the browser guard with `Origin`, `Referer` and `Sec-Fetch-Site`, and the headers and cookies removed;
  - a `send` token refused on the admin API, and an `admin` token refused at the port;
  - a caller with a generated value let through while its address's row waits, and an IPv6 /64 counted as one;
  - a forwarded certificate ignored without the proxy's secret, and the right-most untrusted `X-Forwarded-For`;
  - the `toString` of every `Presented` record, and two presentations equal but for `via` that get opposite answers;
  - a `send` token of a tester who holds another role refused on the admin API, and that token rewritten to `rail8t_`
    unknown there;
  - a verifier named by two credentials refused, and a rebuild that finds two credentials for one lookup leaving both
    out;
  - a deploy, a rollback and an undeploy changing who may send, with no notice of the configuration;
  - typed basic requests with the places free and full, by status, headers and time; a generated value with a wrong
    user and its right secret;
  - attempts at once against one row, charged before their check;
  - the entry's bounds, a disconnect that frees its place, and a handler dropped with its partial body after 5 minutes;
  - a committed claim whose reply is lost, a skewed clock, and a lower limit during a rolling deploy;
  - typed keys found and their duplicates refused after the store's old key is gone;
  - a component whose `reads()` is not empty and that does not override `consumer(Inbound, Entry)` refused at
    registration;
  - a reply's `Set-Cookie` for the platform's cookies dropped;
  - the rows written at the recording level `off`, with Postgres up, at each attempt;
  - no answer, log line or record holds a presented secret, searched in every encoding the platform writes.

## What depends on the other designs

- **Systems and connections:** `Inbound` and `consumer(Inbound, handler)`, which the default overload calls; the key
  `senders` in `from`, which it reserves; the system keys and the first segments it reserves, `rail8`, `subscribers`
  and `by-agreement`; the receiver's facts at each attempted send; `rail8 test` cases of the new shape.
- **The configuration of an environment:** the object store, its snapshot and the notice of each activation; the
  binding; the kinds `credential`, `issuer`, `api-product` and `subscription` joining its tables and routes, held as
  bodies that send a secret without the store's key and at the first key, though they need no consent; the action
  `config.partners`; how long an activation takes to reach a node, which is how long a revoked credential keeps working.
- **The secret store:** the kind `verifier`, its forms, and the store's values held in memory on every node.

A change in any of these is a change here, made in that design's pull request.

## What this replaces

- The HTTP adapter's lack of any check of its caller.
- "Authentication of senders", which `docs/ARCHITECTURE.md` listed as planned.
- "A partner that sends messages is not a user: that is the security of an adapter" (`docs/USERS-AND-ROLES.md`) stays
  true, and this is that security.
- "Five fixed roles": Message sender is the sixth of the eight roles.
- The secret store's three kinds, its rule that no answer holds a value and no digest of one is stored, and its open
  point on a verifier (`docs/SECRET-STORE.md`): the `verifier` is a fourth kind, a digest by its nature, whose generated
  value is answered once when it is made.
- The order in `docs/ADAPTERS.md` (pause, store the body, run the flow) becomes pause, enter, store, run. The first
  step rewrites that guide with the contract it changes.

## Out

- A limit on messages in flight per caller, and a quota per API product or per plan.
- A rule of the environment such as "certificates only".
- Checking a foreign `Authorization` header in a flow's own step: it becomes a credential kind instead.
- A scope of the Message sender by package or by flow.
- Proofs over the body: a webhook's signature and AS2.

## Steps

Each step is one issue. Every step needs the steps of systems and connections that bring `Inbound` and the shape of
`from`, the steps of the configuration of an environment that bring its store and snapshot, and the secret store.

1. **Credentials, the check at the entry in the HTTP adapter with Named systems, and the sender and receivers on a
   message.** The `verifier` kind in its generated form; the `credential` kind for `basic` and `api-key`; the contract
   (`CredentialKind`, `Presented` with all its kinds, `Via`, `Entry`, `Refused`, `reads()`, `consumer(Inbound,
   Entry)`, `HttpIngress.presented` and its default, the check at registration); the order at the entry, the answers,
   the failure rows, the entry's bounds, a handler's lifetime, `RAIL8_HTTP_TLS`, the remote address behind a proxy,
   the headers removed and the platform's cookies kept out of replies; `senders` as Named systems and `{check: off,
   reason}`, held with the mount; version-1 flows listed, and new ones refused in a deployed environment;
   `message_parties` with its primary key only, written for the sender, the receivers at each attempt, pollers and
   calls between flows; `GET /admin/messages/{id}` with `sender` and `receivers`; what `GET /admin/flows`,
   `GET /admin/components` and `/health` gain; `rail8 test`'s `sender` and `expect.refused`; the contract suite and
   `FakeEntry`; `http`, the `webhook` sample, the template and `docs/ADAPTERS.md`. `class:C`.
2. **The sender and receivers in the Messages view**, the search parameters of `GET /admin/messages` with the indexes
   they read, and the status `unrecorded`. `class:B`.
3. **The Message sender role and test sending:** `message.send`, the token's use `send` and its form `rail8m_`, the
   token's path, `Rail8-Sender`, the browser guard, the audit row, `RAIL8_TEST_SENDING` and `/health`. `class:C`.
4. **The limit per caller:** `limits.perSender`, `entry_counts`, the window across nodes and clocks, and a changed
   limit. `class:C`.
5. **API products, subscriptions and Subscribers:** the kinds `api-product` and `subscription`, `config.partners`, a
   product's flows skipped while they are not deployed, and `senders: subscribers`. `class:C`.
6. **Typed passwords and keys:** the verifier's forms `password` and `key`, the store's lookup key, Argon2id at the
   entry with its place decided before the name and its cache, and the bound on guesses across addresses. `class:C`.
7. **Client certificates:** the `certificate` credential, from a trusted proxy's header with `RAIL8_HTTP_PROXY_SECRET`,
   and from the node's own TLS once HTTPS on the HTTP port ships; the validation of a chain to a CA. `class:C`.
8. **OAuth2 tokens:** the `issuer` kind with its `type`, the JWKS read and its staleness, the bound on signature
   checks, and the `oauth2` credential. `class:C`.
