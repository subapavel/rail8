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
- **Systems and connections.** A system's key is a partner and a system, the partner optional: `eshop`, `acme/erp`.
  That design defines a flow's sender, the `Inbound` a consumer is built for, and the facts each send hands over
  about its receiver.
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
  generated value, or a slow hash of a typed one.
- No request reads Postgres to find a system's credential. A personal token of a Message sender is the one exception.
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
  checked once the body is stored; the step that adds them says how such a body is bounded and deleted on a refusal.
- A system rotates a credential by having two for a while: a second is added and activated, the caller switches, and
  the first is removed.

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
user: acme-erp                       # unique among the environment's basic credentials
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
  - the basic users, pinned fingerprints, (issuer, subject) pairs and (issuer, client) pairs are unique in the
    environment;
  - certificates parse;
  - a system holds at most 16 credentials.
- Each credential's secrets are written to `secret_uses` through the store's one method, as `credential` and the
  credential's key, at its activation.
- **The binding.** The credential's body is bound under the store's key, as every body in force is. A database writer
  cannot plant a credential, a certificate or another system's verifier unseen. A credential whose tag fails is absent
  from the node's snapshot, and its caller is refused as unknown.
- **An `issuer` object** (kind `issuer`, a key of one segment) holds an OAuth2 issuer: `{url, audience, clientClaim}`,
  where `clientClaim` is `azp`, `client_id` or `sub`. The node reads the issuer's keys from its JWKS over HTTPS. The
  read has a bounded size, a timeout and a refresh at most once a minute for an unknown key id, and it keeps the last
  good keys. The issuer's URL is bound under the store's key with its body. For a node with no way out, the keys may
  be pasted into the object instead.

### The verifier, a kind of secret

The secret store gains a fourth kind, `verifier`, which answers its open point: a node checks a caller without holding
a value it could send on. A verifier has one of three forms.

| Form | Made by | Sealed in the store | Checked by |
| --- | --- | --- | --- |
| `generated` | the store: `rail8c_<id>_<secret>`, answered once | `{id, fingerprint}`, the SHA-256 of the whole string | the id, then `MessageDigest.isEqual` on the fingerprint |
| `password` | the secret administrator types 16 to 256 characters | an Argon2id hash with the users' parameters (`docs/USERS-AND-ROLES.md`) | Argon2id |
| `key` | the secret administrator types 16 to 256 characters | an HMAC-SHA256 under a subkey of the store's key (HKDF info `rail8 verifier key v1`) | the fingerprint, looked up |

- **The generated value** has the form of the platform's other credentials: `<id>` is 16 lowercase hex digits and
  `<secret>` is 43 base64url characters, 256 random bits from `SecureRandom`. `POST /admin/secrets {name, kind:
  "verifier", form: "generated"}` answers it once, with a copy button in the UI, and nothing else ever holds it. It is
  what the credential's form offers first, and the only form until the typed forms ship.
- **A typed value** is for a move that keeps the callers' password, as from SAP PO behind a proxy that keeps their URL.
  It must not start with `rail8`, so that it can never be mistaken for a generated value or a personal token.
- A `basic` credential's password takes `generated` or `password`. An `api-key` credential's key takes `generated` or
  `key`. Activation refuses any other kind or form.
- Nobody reads a verifier back, and it is never in a draft, a history, a diff or an export. Since a verifier can be
  sent nowhere, a credential needs no consent.

### Found on every request

Each node holds an index, built from its snapshot and the store's verifiers in memory, rebuilt and swapped as one
immutable object at every activation and every change of a secret it names:
- generated id → verifier fingerprint and credential;
- basic user → credential and its password's form;
- typed key's fingerprint → credential;
- pinned certificate fingerprint, and (issuer, subject) → credential;
- (issuer, client) → credential, with the issuer's keys;
- for each deployed flow: its named systems, or the systems subscribed to a product that holds it.

The index decides each request in memory:
- An unknown id or user is compared against a fixed dummy fingerprint, or hashed once against a dummy Argon2id hash, so
  that known and unknown take the same time.
- A typed password costs one Argon2id hash. A node runs at most 2 for the entry at once, apart from the two of the
  logins, with no queue. Beyond that the answer is 503 `busy`; callers with generated values never wait for a place.
- A typed password that verified is kept in a cache on each node: an HMAC-SHA256 under a key the node draws at its
  start, over the credential's key, the verifier's version and the password. It holds at most 10,000 entries, each for
  at most 10 minutes, and it empties with every change of the credential or its verifier.
- A message entered under one index keeps its sender. A change activated after it reaches only the requests that
  enter after the new index is in force, within the configuration's own delay.

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
  as for connection keys. A named system with no active credential is a note of the deploy, not a refusal.
- **`subscribers`** admits every system that holds a subscription to an API product that holds this flow.
- **`by-agreement`** is for partner communication and is refused until that is designed.
- **`{check: off, reason}`** is the only way off. The reason is shown in the flow's details and in the deploy's record.
- **A polling sender** (with a connection) has no `senders`: its sender is the system of its connection. Naming one is
  refused.
- **`direct`** has no `senders`: a call between flows keeps the caller's sender. Naming one is refused.
- **`limits.perSender`** applies to a listening sender whose check is on. It is refused with the check off, on a polling
  sender and on `direct`.
- The design of systems and connections reserves `subscribers` and `by-agreement` as system keys, so that `senders`
  reads one way.
- A flow of `schemaVersion: 1` has no `senders` and keeps no check, until it moves to the shape of systems and
  connections.

## The adapter's contract

`rail8-api` gains these types, beside the consumer of the design of systems and connections:

```java
package dev.rail8.api;

/** The kinds of credential a caller can present. */
public enum CredentialKind { BASIC, API_KEY, CERTIFICATE, OAUTH2 }

/** What the caller of one message presented, as an adapter read it. toString never shows a password, key or token. */
public sealed interface Presented {
    /** The caller's address: the peer, or the client a trusted proxy names. Failures are counted by it. */
    String remote();
    record Basic(String user, String password, String remote) implements Presented {}
    record ApiKey(String key, String remote) implements Presented {}
    /** A chain whose key the caller proved in a TLS handshake: the listener's own, or a trusted proxy's. Leaf first. */
    record Certificate(List<X509Certificate> chain, String remote) implements Presented {}
    record Bearer(String token, String remote) implements Presented {}
    record Nothing(String remote) implements Presented {}
}

/** The entry of one listening sender of a flow. The node makes it; a consumer built with it enters every message. */
public final class Entry {
    /** An entry over a gate: for the node and the testkit. */
    public static Entry of(Function<Presented, Future<Function<Envelope, Future<Ack>>>> gate);

    /**
     * Hands over what the caller of one message presented, before any of the message's body is read. Completes with the
     * handler of that one message, or fails with Refused. Never blocks, and may be called on any thread. The handler
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
    public void answer(HttpServerResponse response);   // the whole HTTP answer, ended; the connection closes
}

public interface Component {
    // … the contract of systems and connections, consumer(Inbound, Function<Envelope, Future<Ack>>) among it
    /** The kinds of credential this adapter's consumers read from callers. */
    default Set<CredentialKind> reads() { return Set.of(); }
    /** The consumer of one sender, which hands each message over through the entry. */
    default Consumer consumer(Inbound in, Entry entry) {
        return consumer(in, e -> entry.enter(new Presented.Nothing("")).compose(handler -> handler.apply(e)));
    }
}

public interface HttpIngress {
    // … as today
    /** The kinds the port reads for every adapter on it. */
    Set<CredentialKind> READS = Set.of(CredentialKind.BASIC, CredentialKind.API_KEY, CredentialKind.CERTIFICATE, CredentialKind.OAUTH2);
    /**
     * What the caller of a request this port handed to a mount presented. The first proof present decides, in this order:
     * Authorization (Basic, or Bearer), then X-API-Key, then the client certificate. It reads the head and the TLS session
     * only, never the body.
     */
    Presented presented(HttpServerRequest request);
}
```

- **The node builds every sender's consumer through `consumer(Inbound, Entry)`.** The default keeps an adapter built on
  the contract of systems and connections compiling: it stores the body, then enters with `Nothing`. Such an adapter
  reads no credential, so a flow can use it as a listening sender only with the check off.
- **An adapter that overrides it holds no handler of its own.** A message reaches the flow only through the handler
  `enter` gave, so an adapter cannot deliver around the check or write a sender.
- **`Entry` is a class, not a functional interface,** so a lambda passed to `consumer(in, …)` in today's adapters and
  tests still means the handler.
- **Enter first.** A consumer calls `enter` before it reads any byte of the body, and a refusal's message is never
  stored. The HTTP port pauses the request; an adapter keeps it paused until `enter` completes.
- **A handler that is never applied holds nothing**: the caller left, or the body was over the limit. It needs no
  release.
- **Errors.** `enter` fails only with `Refused`. Any other failure is a fault of the platform; the adapter answers it as
  it answers a failed handler. The handler fails with `IllegalArgumentException` for an envelope of another flow and
  with `IllegalStateException` when applied twice.
- **A presented value is bounded.** A user of more than 256 characters, a password, key or token of more than 16 KiB, or
  a chain of more than 10 certificates is unknown, with no lookup.
- **What the HTTP port reads.** The remote address is the peer, or the client a trusted proxy names in
  `X-Forwarded-For`. The client certificate is the TLS peer chain, or the one a trusted proxy forwards in its header.
  From any other address both headers are ignored.

An HTTP adapter on the node's port, such as `http`, the `webhook` sample and the adapter template:

```java
@Override public Set<CredentialKind> reads() { return HttpIngress.READS; }
@Override public Consumer consumer(Inbound in, Entry entry) { return new HttpConsumer(in, entry); }

private void handle(HttpServerRequest req) {                      // the event loop; no byte of the body is read yet
    req.pause();
    entry.enter(ctx.http().presented(req)).onComplete(entered -> {
        if (entered.failed()) {
            if (entered.cause() instanceof Refused r) r.answer(req.response()); else req.response().setStatusCode(500).end();
            return;
        }
        streamBodyThenRun(req, entered.result());                 // as today, with the handler enter gave
    });
}
```

An adapter with a listener of its own, such as MLLP over TLS, declares `reads()` as `Set.of(CERTIFICATE)` and calls
`entry.enter(new Presented.Certificate(socket.peerCertificates(), remote))` for each message. It answers a refusal in its
own protocol, from `reason()` and `retryAfter()`. A polling adapter and `direct` present nothing: the platform gives a
poller's message the system of its connection, and a callee the caller's sender.

## At the entry

The order of one request on the node's HTTP port, all of it platform code. Steps 1 to 3 and 5 to 7 run inside `enter`;
on a path no mount holds, the port runs steps 1 to 3 itself before step 4.

0. The port's own admission: the requests in flight per node and per remote address. It is designed apart; its answer
   is 503.
1. **Clear text.** A request that presents a credential from an address other than loopback, not over TLS and not from
   a trusted proxy, is 403 `tls-required`, unless `RAIL8_HTTP_TLS=off`. The credential is not looked at.
2. **Failures.** A presented name or id whose failure row waits, or a remote address whose row waits, is 429
   `too-many` with `Retry-After`. Nothing is looked up.
3. **Find the credential (1).** A `rail8t_` password takes the personal token's path (below). Anything else is looked up
   in the index. None found is 401 `unknown-caller`, and a failure is counted on both rows.
4. **The path.** The port answers a path no mount holds only now: 404 for a path no flow serves, 503 for a flow that has
   no mount at the moment. An unknown caller has already had its 401, on every path.
5. **Allowed senders (2).** The system must be one the flow allows: else 403 `not-allowed`. A personal token picks the
   system as described below, or is 400 `sender-needed`.
6. **Room.** The flow's admission is looked at, without taking a permit. No room is 503 `busy` with `Retry-After`, and
   nothing is counted.
7. **The limit per caller.** One message is counted on (flow, system). Over the limit is 429 `too-many` with
   `Retry-After`.
8. **The handler.** The adapter streams the body; its own refusals are its own (405, 413). Delivery takes the flow's
   permit by the body's real size, as today, and a flow that filled meanwhile answers 503.
9. **The sender is written (3)** with the arrival's save (at-least-once), or with the run's first records (`sync`,
   `at-most-once`).

- `100 Continue` is answered only after step 7. A refusal at steps 1 to 7 closes the connection (an HTTP/2 stream is
  reset), so a refused body is never read.
- **With the check off** the entry never refuses on who the caller is. A known credential or a valid personal token
  gives the sender. Anything else passes with no sender and the proof `none`, and counts no failure. A credential in
  clear text, or one whose failure row waits, is not looked at. Steps 6 and 8 still hold.
- **The proof is removed.** Before the first step and the first record, the platform removes `authorization`,
  `x-api-key`, `rail8-sender` and the trusted proxy's certificate header from the envelope. It does so whether they
  matched or not, with the check on or off.
- A refusal is logged as one WARNING per flow, reason and system or remote address a minute, and counted per flow and
  reason. It never holds what was presented.

## The answers

| Status, code | When | Headers | Never says |
| --- | --- | --- | --- |
| 401 `unknown-caller` | nothing presented; a malformed, unknown, wrong or removed credential; a personal token that fails its check, or test sending off; an unknown path | `WWW-Authenticate: Basic realm="rail8", charset="UTF-8"` | which of these, whether a name, an id or a flow exists |
| 403 `not-allowed` | a known caller that this flow does not allow | | the systems allowed, the products, any other system |
| 400 `sender-needed` | a personal token on a flow that allows several systems, without `Rail8-Sender` | | the systems allowed |
| 404 `not-found` | a known caller on a path no flow serves | | |
| 429 `too-many` | a failure row waits; over the limit per caller | `Retry-After` | other callers' counts; whether a name exists |
| 503 `busy` | the port's own admission; no room in the flow; no place to hash or to check a token; a count that cannot be claimed | `Retry-After` | |
| 403 `tls-required` | a credential in clear text | | |

- The body is `{"code": "…", "error": "…"}`, the error one fixed English text per code. The reason, the system and the
  address go only to the log and the counts.
- A known caller learns that a flow it may not send to exists. It learns nothing about who may.

## Brute force and enumeration

- **Generated values give nothing to guess.** A password or key of 256 random bits is found by its id, its fingerprint
  compared in constant time, and an unknown id is compared against a dummy.
- **Failure rows** are kept in memory on each node:
  - one for each (presented name or id, remote address), whether the name exists or not, with 5 free failures;
  - one for each remote address, with 100 free failures;
  - after the free failures, each attempt waits 1 s after the last failure, doubling up to 300 s;
  - a success forgives the failures of its own row only, so an attacker at another address cannot lock a partner out;
  - a row is dropped 24 hours after its last failure; each table holds at most 100,000 rows, and the row with the
    oldest last failure goes first.
  On a pool of several nodes each node counts on its own, so an attacker gets that many times the attempts.
- **Equal answers.** Known and unknown get the same code, the same text and about the same time.
- **Paths stay hidden.** An unknown caller gets 401 on every path, so it learns no path a flow serves.
- **Personal tokens** are checked in at most 4 places a node, and the failure rows are read before the check, so a
  flood from one address is answered 429 without a read of Postgres.

## The limit per caller

- **What is counted.** Each message that passed the entry's steps 1 to 6 counts once, on its flow and its sending
  system. The window is fixed: `600/m` counts from the start of each minute of the injected clock, in UTC. Across a
  window's edge a caller can send up to twice the rate.
- **Where the count lives: Postgres**, the same with one node or many, with or without Valkey. Each node claims a slice
  of the window and admits within it. The slice is the limit divided by 8 times the live nodes, at least 1.

  ```sql
  create table entry_counts (
      flow         text        not null,   -- <package>/<flow>
      sender       text        not null,   -- a system key
      window_start timestamptz not null,   -- the injected clock floored to the window; SQL never reads now()
      used         bigint      not null check (used >= 0),
      primary key (flow, sender, window_start));
  -- a claim of $k (the slice, or 1 when the slice no longer fits), on a virtual thread:
  insert into entry_counts as c (flow, sender, window_start, used) values ($1, $2, $3, $k)
  on conflict (flow, sender, window_start) do update set used = c.used + $k
    where c.used + $k <= $limit
  returning used;                          -- no row back: over the limit
  ```

- **The bound.** A window never admits more than the limit. It may admit fewer, by at most (live nodes − 1) × (slice −
  1), which is less than an eighth of the limit: the slices that nodes have claimed but not used when the window ends.
- **Over the limit:** 429 `too-many`, with `Retry-After` the seconds to the window's end.
- **A claim that fails**, because Postgres does not answer within 1 s, is 503 `busy`. A lost count loses no message.
- Rows older than two windows are deleted with retention. Only flows with `limits.perSender` write here.
- **With admission.** The count comes after the flow's room is looked at, so a caller is not counted for a 503 the
  platform caused. The port's own admission comes before the whole entry.

## API products and subscriptions

Both are objects of the configuration of an environment, written by the partner administrator through the action
`config.partners`. Neither is in git.

| Kind | Key | Body | Activation checks |
| --- | --- | --- | --- |
| `api-product` | one segment, `orders-api` | `{description?, flows: ["<package>/<flow>", …]}`, at most 256 flows | each flow is deployed; a flow undeployed later drops out of the product |
| `subscription` | the product's key, then the system's key: `orders-api/acme/shop` | `{}` | the product and the system exist |

- A flow with `senders: subscribers` admits every system with an active subscription to an active product that holds
  the flow. The index holds the list per flow.
- An API product's application is a system: the same object that has credentials. A customer with two applications has
  two systems under its partner.
- A system is defined in a package, as for every system. A new customer of an API is a commit and a deploy of its
  system file, then its credential and its subscription in the environment.
- Subscriptions count against the environment's 20,000 objects.

## Test messages

A user with the role **Message sender** sends a message to a flow from SoapUI, Postman or `curl`. It uses basic
authentication: the user's own name, and a personal token (`rail8t_<id>_<secret>`) as the password. A technical
account that holds the role sends the same way, with one of its tokens.

- **The role and the action.** `message-sender` grants the new action `message.send`. A person or a technical account
  may hold it, a token may do it, and the emergency key may not. A developer does not hold it unless it is granted.
- **The admin API stays closed on the port.** The port never routes to `/admin`. It recognises only a personal token,
  by its form, in the password of basic authentication. A session (`rail8s_`), a device (`rail8d_`), the emergency key
  (`rail8k_`) and a token sent as `Bearer` are unknown callers there.
- **The check** is the users' own check: the token is parsed, then read by its id with its user and the user's roles
  in one query. Then the node compares the fingerprint in constant time, and checks expiry, revocation and the user's
  block. The name must be the token's user, and the user must hold `message.send`. A node runs at most 4 such checks
  at once, with no queue; beyond that the answer is 503 `busy`. So a revoke holds for the next request on every node.
- **No browser.** A request that carries `Sec-Fetch-Site` with any value but `none` has its personal token refused. So
  a page cannot send as a tester whose browser keeps the token for the port.
- **Which system sends.**
  - The flow's one named system, by default.
  - The header `Rail8-Sender: <system key>` picks another, and must name a system the flow allows; under
    `subscribers`, one that holds a subscription.
  - A flow that names several systems, or `subscribers`, needs the header: without it the answer is 400
    `sender-needed`.
  - With the check off, the header may name any defined system; without it the message has no sender.
  - The header counts only with a personal token. From any other caller it is ignored, and it is always removed.
- **The record.** The message counts on that system's limit. Its proof is `token`, with the token's id as the
  credential and the user's name as the tester. An audit row `message.send` (the actor, via the token, the token's id,
  the subject `message/<id>`, the flow and the system) is written before the handler runs. When it cannot be written,
  the answer is 503.
- **Switching test sending off.** `RAIL8_TEST_SENDING=off` on a node closes the path: a personal token is then an
  unknown caller. The default is `on`, because a deployed test environment is where testers send. Production should
  set `off`: a test message has no flag and reaches the flow's real receivers. The variable is the operator's and not
  a setting of the UI, so a stolen admin account cannot open it. Every node of a pool must hold the same value;
  `/health` shows each node's.

## The record of the sender and the receivers

```sql
create table message_parties (
    message_id    text        not null,
    side          text        not null check (side in ('sender', 'receiver')),
    connection    text        not null default '',  -- a receiver's connection key; a polling sender's; '' otherwise
    flow          text        not null,
    at            timestamptz not null,             -- the entry; a receiver's first send
    partner       text,                             -- null: a system without a partner, or no sender
    system        text,                             -- null: no sender (the check off and nothing proved)
    instance_name text,                             -- as it held then
    proof         text        not null,             -- a sender's: basic, api-key, certificate, oauth2, token, connection, none;
                                                    -- a receiver's: sent, mocked
    credential    text,                             -- the credential's key, the token's id, or the polling connection's key
    tester        text,                             -- a Message sender's user name
    remote        text,                             -- the caller's address, as the failure rows read it
    called_from   text,                             -- the calling message's id, for a flow called by another
    primary key (message_id, side, connection));
create index message_parties_system     on message_parties (system, side, at desc);
create index message_parties_partner    on message_parties (partner, side, at desc) where partner is not null;
create index message_parties_credential on message_parties (credential, at desc) where credential is not null;
create index message_parties_tester     on message_parties (tester, at desc) where tester is not null;
```

- **The sender's row** is written in the arrival's transaction (at-least-once), or in the run's first group of records
  (`sync`, `at-most-once`). It is written at every recording level and with every payload setting, `off` included. A
  flow that records nothing else still writes one row for each message, plus one for each receiver.
- **A receiver's row** is written at the end of the first send through each connection, from the facts the send hands
  over: the connection key, the system key, the partner, the instance name as it held then, and whether it was mocked.
  A retry or a second send through the same connection keeps the row.
- **A polling sender** writes the system of its connection, with the proof `connection` and the connection's key as the
  credential.
- **A call between flows** writes the caller's sender row again on the callee's message, with the caller's proof and
  `called_from` the calling message's id. The sender also travels with the message's work across retries and nodes, so
  a retry on another node writes the same sender.
- **Retention** is that of the messages (`RAIL8_RETENTION_MAX_AGE`).
- **Monitoring.**
  - `GET /admin/messages` gains the parameters `sender`, `receiver`, `partner`, `instance`, `proof`, `credential` and
    `tester`. `sender` and `receiver` take a system key; `partner` matches the partner of either side.
  - `GET /admin/messages/{id}` gains `sender` and `receivers`.
  - A message is listed by its recorded arrival, or else by its sender's row. A message of a flow that records nothing
    shows its flow, its time, its sender and its receivers, with the status `unrecorded`.
  - `GET /admin/flows` gains each flow's allowed senders, its limit per caller and its counts of refusals by reason.
    `GET /admin/components` gains each component's `reads`.

## Roles, actions and settings

- The role `message-sender` and the action `message.send` (above) join `docs/USERS-AND-ROLES.md`.
- `credential` and `issuer` are written through `config.connections`, by the connection administrator.
- `api-product` and `subscription` are written through `config.partners`, by the partner administrator. `config.partners`
  is that role's first write action.
- The secret store's create takes `kind: "verifier"` with a `form`, through `secret.write`.
- **`RAIL8_HTTP_TLS`**, as `RAIL8_ADMIN_TLS` is for the admin port:
  - unset: a credential is taken only over the node's own TLS (once HTTPS on the HTTP port ships), on loopback, or from
    a trusted proxy;
  - `proxy=<cidr>,…`: the addresses of TLS-terminating proxies, whose `X-Forwarded-For` and client certificate header
    the node believes. `RAIL8_HTTP_CLIENT_CERT_HEADER` names that header, `X-Forwarded-Client-Cert` by default;
  - `off`: the network is protected another way. The node logs a WARNING at its start.
  Until HTTPS on the HTTP port ships, every deployment sets `proxy=` or `off`, and the quickstart's compose files set
  `off`.
- **`RAIL8_TEST_SENDING`**: `on` (the default) or `off`.
- `/health` gains `entry: {tls: "tls" | "proxy" | "off", testSending: "on" | "off"}`.

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
| Argon2id for the entry | 2 at once a node, no queue |
| The cache of typed passwords that verified | 10,000 entries, 10 minutes each |
| Personal token checks | 4 at once a node, no queue |
| Failure rows | 100,000 per table a node; dropped 24 hours after the last failure |
| `perSender` | 999,999,999 a window |
| Flows of an API product | 256 |
| An issuer's JWKS | 64 KiB, a 5 s timeout, read at most once a minute |
| A claim of the count | 1 s |

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
  - the browser guard, and the headers removed;
  - the rows written at the recording level `off`;
  - no answer, log line or record holds a presented secret, searched in every encoding the platform writes.

## What depends on the other designs

- **Systems and connections:** `Inbound` and `consumer(Inbound, handler)`, which the default overload calls; the key
  `senders` in `from`, which it reserves; the system keys and the reserved words; the receiver's facts at each send;
  `rail8 test` cases of the new shape.
- **The configuration of an environment:** the object store, its snapshot and the notice of each activation; the
  binding; the kinds `credential`, `issuer`, `api-product` and `subscription` joining its tables and routes; the action
  `config.partners`; how long an activation takes to reach a node, which is how long a revoked credential keeps working.
- **The secret store:** the kind `verifier`, its forms, and the store's values held in memory on every node.

A change in any of these is a change here, made in that design's pull request.

## What this replaces

- The HTTP adapter's lack of any check of its caller.
- "Authentication of senders", which `docs/ARCHITECTURE.md` listed as planned.
- "A partner that sends messages is not a user: that is the security of an adapter" (`docs/USERS-AND-ROLES.md`) stays
  true, and this is that security.
- "Five fixed roles": Message sender is the sixth of the eight roles.
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
   (`CredentialKind`, `Presented` with all its kinds, `Entry`, `Refused`, `reads()`, `consumer(Inbound, Entry)`,
   `HttpIngress.presented`); the order at the entry, the answers, the failure rows, `RAIL8_HTTP_TLS` and the headers
   removed; `senders` as Named systems and `{check: off, reason}`; `message_parties`, written for the sender, the
   receivers, pollers and calls between flows; `GET /admin/messages/{id}` with `sender` and `receivers`; the
   contract suite and `FakeEntry`; `http`, the `webhook` sample, the template and `docs/ADAPTERS.md`. `class:C`.
2. **The sender and receivers in the Messages view**, the search parameters of `GET /admin/messages`, and the status
   `unrecorded`. `class:B`.
3. **The Message sender role and test sending:** `message.send`, the token's path, `Rail8-Sender`, the browser guard,
   the audit row, `RAIL8_TEST_SENDING` and `/health`. `class:C`.
4. **The limit per caller:** `limits.perSender` and `entry_counts`. `class:C`.
5. **API products, subscriptions and Subscribers:** the kinds `api-product` and `subscription`, `config.partners`, and
   `senders: subscribers`. `class:C`.
6. **Typed passwords and keys:** the verifier's forms `password` and `key`, Argon2id at the entry and its cache.
   `class:C`.
7. **Client certificates:** the `certificate` credential, from a trusted proxy's header, and from the node's own TLS
   once HTTPS on the HTTP port ships. `class:C`.
8. **OAuth2 tokens:** the `issuer` kind, the JWKS read, and the `oauth2` credential. `class:C`.
