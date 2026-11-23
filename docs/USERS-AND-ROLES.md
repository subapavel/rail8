# Users, roles and the permission check

This document is the agreed design of who uses Rail8 and what they may do:
- the user model and the five roles;
- the one decision point that every admin route asks, and the list of actions;
- password storage, the limit of login attempts and the first password;
- the session of a user logged in to the UI, and the tokens of the command line and of machines;
- the emergency key, the audit, the tables in Postgres;
- the transition from the shared token.

It is built in the steps listed at the end, and no step reworks what an earlier one built.

Today the admin API has one shared bearer token (`RAIL8_ADMIN_TOKEN`) and no users, and the audit takes the actor's name
from the header `X-Rail8-Actor`.

## Rules

- A user is a person or a machine that uses the UI, the command line or the admin API. A partner that sends messages is
  not a user: that is the security of an adapter.
- Accounts are Rail8's own: a name and a password in Postgres. An administrator creates them and resets a forgotten
  password. Rail8 sends no e-mail and has no second factor of its own.
- An account has a `source`, and roles belong to the account, not to its password, so that sign-in through OIDC can be
  added later. A technical account, such as `ci-deploy`, has no password: it has tokens only.
- There are five fixed roles. A user may hold several, and they add up. No role implies another.
- A role holds for the whole installation. An environment is an installation of its own, so who may do what in
  production is who holds the role in the production installation. Nothing inside Rail8 decides by environment.
- Every permission check asks three things: the user, the action and the package. The package is ignored for now.
- The node authenticates every admin request from Postgres and caches nothing. A revoke, a block, a logout or a change
  of roles holds from the next request, on every node of the pool.
- The node still answers no browser. The UI server carries a browser's session to the node as a bearer credential.
- Every credential is random, is handed out once, and is stored only as its fingerprint.
- The audit names who acted from the login or the token, never from a header.
- Two independent locks decide a deploy. The git host decides what may be deployed: who changes a package and who
  approves the pull request into the release branch. Rail8 decides who may deploy: it checks the developer role in this
  installation and nothing more. Rail8 accounts and git host accounts are not linked.

## Roles

| Role | Id | Holds |
| --- | --- | --- |
| Administrator | `administrator` | users, their roles, technical accounts and their tokens, sessions, the Audit screen |
| Developer | `developer` | deploys and undeploys a commit, sees the deployed configuration, calls the modules' services (the mapping studio) |
| Operator | `operator` | monitoring, retry, cancel, release and discard. Sees a message's status, times, error, flow, headers and the lines its steps logged |
| Message bodies | `message-bodies` | sees and downloads a message's body after each step |
| Secret administrator | `secret-administrator` | creates, overwrites and deletes secrets (the secret store, designed apart) |

An administrator does not hold the message bodies role unless it is assigned. Assigning it to oneself is allowed and is
in the audit, marked as one's own.

## Actions

Each action has an id. The `Action` enum in the code is the whole table: an action, the roles that grant it, and whether
the emergency key may do it. A test compares the enum with this table.

| Action | Routes | Granted by |
| --- | --- | --- |
| `message.read` | `GET /admin/messages` (every form), `/admin/message-counts`, `/admin/messages/{id}`, `/admin/messages/{id}/audit`, `/admin/dlq`, `/admin/work/held`, `/admin/work/stopped`, `/admin/work/lost` | operator |
| `message.body` | `GET /admin/messages/{id}/steps/{n}/body` | message-bodies |
| `message.retry` | `POST /admin/dlq/{id}/retry` | operator |
| `message.cancel` | `POST /admin/dlq/{id}/cancel`, `POST /admin/messages/{id}/cancel` | operator |
| `work.release` | `POST /admin/work/{seq}/release` | operator |
| `work.discard` | `POST /admin/work/{seq}/discard` | operator |
| `deployment.read` | `GET /admin/packages`, `/admin/flows`, `/admin/flows/{flow}/cpu`, `/admin/targets`, `/admin/components`, `/admin/steps`, `/admin/modules` | developer, operator |
| `package.deploy` | `POST /admin/deploy`, with `?check=true` too | developer |
| `package.undeploy` | `DELETE /admin/packages/{name}` | developer |
| `module.call` | `GET`, `POST /admin/modules/{plugin}/{path}` | developer |
| `user.read` | `GET /admin/users`, `GET /admin/users/{name}` | administrator, the key |
| `user.create` | `POST /admin/users` | administrator, the key |
| `roles.change` | `POST /admin/users/{name}/roles` when it adds or removes any role other than administrator | administrator |
| `administrators.change` | the same route when it adds or removes only the administrator role | administrator, the key |
| `user.block` | `POST /admin/users/{name}/block`, `/unblock` | administrator, the key |
| `user.password-reset` | `POST /admin/users/{name}/password-reset` | administrator, the key |
| `session.end` | `POST /admin/users/{name}/sessions/end` | administrator, the key |
| `token.read` | `GET /admin/tokens` | administrator, the key |
| `token.issue` | `POST /admin/users/{name}/tokens`, for a technical account only | administrator |
| `token.revoke` | `POST /admin/tokens/{id}/revoke` | administrator, the key |
| `audit.read` | `GET /admin/audit` | administrator |
| `secret.list` | the secret store's list: names, never a value | developer, secret-administrator |
| `secret.write` | the secret store's create and overwrite | secret-administrator |
| `secret.delete` | the secret store's delete | secret-administrator |
| `self.read` | `GET /admin/me` | every caller, the key included |
| `self.password` | `POST /admin/me/password` | a person, through a session |
| `self.logout` | `POST /admin/me/logout` | a session |
| `self.tokens` | `GET`, `POST /admin/me/tokens`, `POST /admin/me/tokens/{id}/revoke` | a person; issuing only through a session |

`POST /admin/sessions` (the login) is the only `/admin` route with no caller. `/health`, `/live` and `/ready` stay
open, as today. There is no `secret.read`: nobody reads a secret's value back.

A user created with roles (`POST /admin/users {roles}`) is checked for each role as a change of roles would be: the
emergency key may create a user with no role or with the administrator role only.

Invariants, each answered 409 when a request would break it:
- The last unblocked administrator keeps the role and cannot be blocked.
- Nobody blocks themselves.
- An administrator issues tokens only for technical accounts. A person's tokens are their own, so the audit never names a
  person who did not act.

## The decision point

```java
// dev.rail8.runtime.access
enum Role { ADMINISTRATOR, DEVELOPER, OPERATOR, MESSAGE_BODIES, SECRET_ADMINISTRATOR }   // stored and answered by id
enum Action { MESSAGE_READ, MESSAGE_RETRY, …;  String id(); Set<Role> roles(); boolean emergencyKey(); Who who(); }
record PackageRef(Optional<String> name) { static PackageRef ANY; static PackageRef ofFlow(String qualifiedFlow); }
record Caller(OptionalLong user, String name, Kind kind, Set<Role> roles, Via via, boolean firstPassword) {
    boolean may(Action action, PackageRef pkg);                 // the decision point; the package is taken and ignored
    void require(Action action, PackageRef pkg);                // may, else 403 naming the roles that grant it
    Actor acting(Action action, PackageRef pkg, String remote); // require, then the audit's actor: the only way to get one
}
interface Access {   // Postgres behind it; a fake in memory for tests
    Caller authenticate(Optional<String> authorization, String method, String path, String remote);   // 401, 503
    Login login(String name, String password, String remote);                                          // 401, 429, 503
    …   // the password change, the logout, and the administrator's acts on users, roles, sessions and tokens
}
// dev.rail8.core.audit: replaces FailedMessages.Actor and the actor strings of the work queue and of the deploy
record Actor(String name, OptionalLong user, String via, Optional<String> credential, String remote) {}
```

`may` decides in this order:
1. In the release of step 1 only, the shared token and the open loopback API may do every action, as today.
2. The emergency key may do the actions whose `emergencyKey()` is true, and nothing else.
3. A caller with a first password may do `self.read`, `self.password` and `self.logout`, and nothing else.
4. An action of the caller's own (`self.*`) is granted by who the caller is: every caller, a person, or a session.
5. Every other action is granted when the caller holds one of its roles.

**Mounting.** `AdminServer` mounts an `/admin` route only through `admin.get`, `admin.post` and `admin.delete`, each with
the route's path and its `Action`. The route's body takes the checked `Caller`. A route without an action does not
compile, and a test walks the router and fails for any `/admin` route mounted otherwise. The login and the browser guard
are the only exceptions.

**The order of every `/admin` request:**
1. The browser guard: a request with an `Origin` header or a `Sec-Fetch-Site` other than `none` is 403, as today.
2. `POST /admin/sessions` goes to the login.
3. Every other request is authenticated: 401 or 503. An unknown path is authenticated too, so a caller without a
   credential learns nothing of the routes. No body has been read yet.
4. The router: 404, 405.
5. The action at the door: `who.require(action, PackageRef.ANY)`, 403. Still no body has been read, so a refused caller
   never sends a large body.
6. The body: 400, 413.
7. The route's own checks: 400, 404, 409, 422. Before it changes anything, a route that acts on one message or work item
   asks `who.acting(action, PackageRef.ofFlow(flow), remote)` with the flow of what it acts on, and passes the `Actor`
   to the store, which writes the act and its audit row in one transaction.

A deploy knows its package only once it has read the commit. Its check names `PackageRef.ANY` for now; a later scope by
package asks again inside the deploy, once the manifest names the package.

**Errors.** Every refusal has a `code`:
- 401 `{error, code}` with `WWW-Authenticate: Bearer`. The code is `no-credential`, or `unknown-credential` for a
  credential of the wrong form, an unknown id or a wrong secret. Only once the secret matched can it be
  `session-ended`, `token-expired`, `token-revoked` or `blocked`. A refused login is `login-refused`, with one text for
  every reason; the audit holds the reason.
- 403 `{error, code, needs}`: `not-allowed` with `needs` the roles that grant the action, `password-change-required`,
  and `browser` from the guard.
- 429 `{error, code: "too-many-attempts"}` with `Retry-After`: the login, the password change and the issue of a
  personal token.
- 503: the Postgres pool refused, as today; `busy` with `Retry-After: 1` when no place to hash a password is free; a
  required audit row (a use of the emergency key, a view of a body) that could not be written, and then nothing runs.

Plugins' admin services answer these as problems (`application/problem+json`) of the types
`urn:rail8:admin:problem:unauthenticated` and `urn:rail8:admin:problem:forbidden`.

## Credentials

Sessions and tokens have one form:

```
rail8s_<id>_<secret>     a session
rail8t_<id>_<secret>     a token
```

- `<id>` is 16 lowercase hex digits, 64 random bits. It names the credential in lists, in the audit and in logs, and proves
  nothing alone. A token pasted into the revoke dialog is revoked by its id.
- `<secret>` is 43 base64url characters, 256 random bits from `SecureRandom`.
- The fixed prefix lets a secret scanner find a leaked token.

Postgres keeps the fingerprint: SHA-256 of the whole string in UTF-8, 32 bytes. The credential itself is shown once and
never stored.

The check:
1. The string is parsed by position; anything else is 401 `unknown-credential` without a read.
2. The row is read by its id, joined with its user and the user's roles, in one query.
3. The fingerprint is compared with `MessageDigest.isEqual`. An unknown id is compared with a fixed dummy fingerprint, so
   both paths take the same time.
4. Only then are expiry, revocation, the session's idle time and the user's block checked.

A slow hash is not needed: a fingerprint of 256 random bits gives nobody anything to guess from. A session's
`last_seen_at` is written at most once a minute and a token's `last_used_at` at most once an hour.

## Passwords

- **Hash.** Argon2id (RFC 9106) from Bouncy Castle `org.bouncycastle:bcprov-jdk18on` (MIT licence), class
  `Argon2BytesGenerator`: memory 19,456 KiB, 2 passes, parallelism 1, a 16-byte salt and a 32-byte hash. It is stored as
  a PHC string, `$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`. A login whose stored parameters differ from the current
  ones hashes the password again, so the parameters can change without a migration.
- **Cost.** A node hashes at most 2 passwords at once, with no queue: logins use at most 2 cores and 38 MiB. A third login
  at the same moment is 503 `busy` with `Retry-After: 1`. An unknown name, a technical account and a blocked user still
  cost one hash of a fixed dummy, so the time does not tell them apart.
- **Rules.** A password is 12 to 128 characters after NFC normalisation, must not contain the user's name and must differ
  from the current one. A login's body is at most 1 KiB. There are no rules of composition.

### The limit of login attempts

Each name, as typed and lowercased, has one row in `login_limits`, unknown names too, so a refusal does not tell which
names exist. Before any hash, one statement claims the attempt:

```sql
insert into login_limits as l (name, failures, last_failure_at) values ($1, 1, $2)
on conflict (name) do update set failures = least(l.failures + 1, 1000000), last_failure_at = $2
  where l.failures < 5
     or l.last_failure_at + least(power(2, least(l.failures, 14) - 5), 300) * interval '1 second' <= $2
returning failures
```

- No row back is 429 with `Retry-After`: no hash and no count.
- The first 5 failures are free. After them, each attempt waits 1, 2, 4 and so on, up to 300 seconds, after the last
  failure: about 300 guesses per name per day.
- The upsert takes the row's lock, so it holds across the nodes of the pool: 50 attempts at once get 5 hashes.
- A success deletes the row; so does a password reset by an administrator.
- `$2` is the node's injected clock, as for every instant in these tables; the SQL never reads `now()`.
- The password change and the issue of a personal token claim the same row, so a stolen session cannot guess the
  current password faster than a login.
- The table holds at most 100,000 names. Beyond that, an unknown name is refused without a row; it cannot succeed anyway.

### The first password

- An administrator never types a password for someone else. Creating a person, and resetting a password, answers once
  with a first password that the node generates: 20 characters of lowercase base32, 100 random bits. It is valid for
  7 days; after that the login is refused and an administrator resets it again.
- A login with a first password gives a session that may only read itself, change the password and log out. Anything
  else is 403 `password-change-required`.
- `POST /admin/me/password {current, next}` changes it. The same session goes on without the restriction, and every other
  session of the user ends.

## The session in the UI

**What carries it.** Between the browser and the UI server, a cookie: `__Host-rail8-session` with `HttpOnly; Secure;
SameSite=Strict; Path=/` when the UI server is served over HTTPS, and `rail8-session` with the same attributes but
without `Secure` on `http://localhost` in development. Between the UI server and the node, `Authorization: Bearer
rail8s_…`. The UI server keeps no state and holds no secret of its own, so several UI servers, and their restarts, keep
every user logged in. The node sees no browser header, so it still refuses every browser.

**The UI server's own routes**, each under today's rule (`Sec-Fetch-Site: same-origin` and an `Origin` from the fixed
list):
- `POST /session {name, password}` sends the login to the node's `POST /admin/sessions`, sets the cookie, and answers
  what `GET /admin/me` answers, without the session's secret.
- `POST /session/end` sends the logout and clears the cookie.
- `GET /session` answers `GET /admin/me`.

The `/admin` proxy refuses `/admin/sessions` (403), so no page script ever sees a session's secret. It passes JSON bodies
of POST requests up to 64 KiB, which the users' routes, the password change and a cancel's reason need, and passes
`Retry-After` back. On a 401 from the node it clears the cookie.

`GET /admin/me` answers `{name, kind, roles, actions, via, firstPassword, expiresAt}`. `actions` lists the actions the
caller may do, so the UI hides or disables what the caller may not do, saying which role it needs, and holds no table
of roles of its own.

**How long it lives.** 12 hours from the login, and 2 hours without a request. A user has at most 10 sessions; the
eleventh login ends the oldest.

**How it ends:**
- the logout;
- a block of the user, in the same transaction; the user's tokens are refused while the block lasts;
- a password reset by an administrator: every session of the user;
- the user's own password change: every other session of the user;
- an administrator's `session.end`: every session of that user;
- 12 hours, or 2 hours idle.

A change of roles ends nothing. Roles are read on every request, so a role gained or lost holds at the next request, on
every node.

When the node answers 401 because the session ended, the UI shows the login over the open tabs and then sends the
refused request once more. That is safe because the node did nothing for a 401.

**The development server.** One function, `bearerOf(request, env)`, gives the credential for every call to the node: the
session's cookie, else the author's personal token from `RAIL8_TOKEN`, else, in the release of the first step only, the
shared `RAIL8_ADMIN_TOKEN`. The proxy, the git half and the mapping half all use it, so a deploy from the UI names the
logged-in user. With `RAIL8_TOKEN` set, the UI shows no login: every call is the author's.

A UI server outside development, when it comes, does the same and holds no token.

## Tokens

- **Personal.** A person issues their own tokens for the command line (`self.tokens`). A token carries its user's roles as
  they are at each request. Issuing one needs a session, not a token, and asks the password again, so a stolen session
  or token cannot mint a lasting credential.
- **Technical.** An administrator creates a technical account, gives it roles, and issues its tokens (`token.issue`).
- **Form.** A name (`^[a-z0-9][a-z0-9-]{0,63}$`, unique among the user's live tokens) and an expiry of 1 to 365 days,
  90 by default. Every token expires.
- **Shown once.** The 201 that issues it carries the token, `{id, name, expiresAt, token}`. Postgres keeps only its
  fingerprint.
- **Revoked at once.** The user revokes their own; an administrator and the emergency key revoke any. The next request
  with it is 401 on every node.
- **Expiring soon.** `GET /admin/tokens?expiringWithin=P14D` lists the live tokens that expire within 14 days; the UI shows
  them to an administrator.
- **Limits.** At most 20 live tokens per user.

The command line sends `Authorization: Bearer $RAIL8_TOKEN`, or the emergency key from `RAIL8_ADMIN_TOKEN` when
`RAIL8_TOKEN` is not set, which is how `rail8 user add` makes the first administrator. CI holds the token of a technical
account, such as `ci-deploy` with the developer role, in `RAIL8_TOKEN`; `rail8 deploy` does not change.

## The emergency key

`RAIL8_ADMIN_TOKEN` on the node is the emergency key. It is unset by default. It creates the first administrator and
rescues an installation whose administrators are locked out.

- **What it may do:** `self.read`, `user.read`, `user.create` with no role or with the administrator role only,
  `administrators.change`, `user.block`, `user.password-reset`, `session.end`, `token.read` and `token.revoke`.
- **What it may never do:** deploy, undeploy, read messages or bodies, retry, cancel, call a module, read the audit, touch
  a secret, change any role but administrator, or issue a token. A token it issued could deploy, so every way from the
  key to a deploy goes through a named administrator's login.
- **Its form.** At least 32 characters, and not of the form of a session or a token; otherwise the node does not start.
  It is compared as `MessageDigest.isEqual(sha256(presented), sha256(key))`.
- **Its audit.** Every request with it, refused or not, first writes an `emergency-key.use` row with the method, the path,
  the remote address and whether it was allowed. When that row cannot be written, the answer is 503 and nothing runs. Its
  acts are audited with the actor `emergency-key`.
- **Its notices.** While it is set, the node logs a WARNING at its start and at every use, and `GET /admin/me` of an
  administrator carries `emergencyKey: true`, which the UI shows as a notice to unset it.

The first administrator, with the key in the environment of the command line:

```sh
rail8 user add alice --role administrator      # prints alice's first password, once
```

Alice logs in, chooses her password, gives herself the roles she needs and creates the other users. The key is then
unset and the node restarted.

## The audit

One `audit` table, two views:
- at a message, `GET /admin/messages/{id}/audit` (operator): who retried it, cancelled it or viewed its body;
- the Audit screen, `GET /admin/audit?user&action&from&to&after&limit` (administrator), newest first, at most 500 a page
  and 50 by default.

**Who.** `actor` is the user's name; names never change and users are never deleted, so it keeps its meaning. Next to it,
`user_id`, `via` (how the caller proved who it is) and `credential` (the session's or the token's id, never its secret).
The remote address stays in the detail, as today. For a call through the UI server it is the UI server's address.

| Credential | `actor` | `user_id` | `via` |
| --- | --- | --- | --- |
| a session | the user's name | the user's id | `session` |
| a token | the user's name | the user's id | `token` |
| the emergency key | `emergency-key` | null | `emergency-key` |
| the shared token, in the first step's release only | the `X-Rail8-Actor` header, `unknown` without one | null | `shared-token` |
| no credential on a loopback bind, in the first step's release only | the same | null | `local` |
| rows written before the first step | as they were | null | `declared` |

**A deploy** carries two names: `actor`, the user who deployed, and `detail.author`, the commit's author as git holds it
(`Name <email>`), which the node reads from the commit itself, not from a header. The command line, CI and the UI
therefore leave the same record.

**New entries:** `login`, `login.refused`, `logout`, `password.change`, `user.create`, `user.block`, `user.unblock`,
`user.password-reset`, `user.roles` (with `{added, removed, self}`, so assigning oneself the message bodies role is
marked), `session.end`, `token.issue`, `token.revoke`, `emergency-key.use` and `message.body.view`. A release of a held
item (`work.release`) and an undeploy (`undeploy`) are audited too, beside the deploy, the discard, the retry and the
cancel that already are.

**A view of a body** is written before the body is sent. When that row cannot be written, the answer is 503.

**Refused logins are limited in number.** A name writes a `login.refused` row for the first failure of a run, then at most
one an hour, carrying the count of failures since. Attempts refused with 429 write nothing. The whole installation writes
at most 1,000 `login.refused` rows an hour. The row `*` of `login_limits` counts them: `failures` is the count and
`last_failure_at` the start of the hour. Beyond the 1,000, a refusal is counted there and not written.

**Retention.** `RAIL8_AUDIT_MAX_AGE`, ISO-8601, from P30D to P3660D, P365D by default. The node's retention job deletes
older audit rows, and with them ended sessions, tokens expired or revoked longer ago than that, and `login_limits` rows
without a failure for 24 hours, in its batches.

## Tables

Step 1 adds `V29__users.sql`, step 2 `V30__tokens.sql`.

```sql
create table users (
  id bigint generated always as identity primary key,
  name text not null unique check (name ~ '^[a-z][a-z0-9._-]{1,63}$'),  -- never changes; users are blocked, not deleted
  kind text not null check (kind in ('person', 'technical')),
  source text not null check (source = 'rail8'),         -- 'oidc:<provider>' comes with sign-in through OIDC
  subject text,                                          -- the provider's subject then; null for 'rail8'
  password text,                                         -- the PHC string; null for a technical account
  first_password boolean not null default false,
  password_set_at timestamptz,
  created_at timestamptz not null, created_by text not null,
  blocked_at timestamptz,
  check (kind = 'person' or password is null),
  unique (source, subject));
create table role_assignments (
  user_id bigint not null references users (id),
  role text not null check (role in ('administrator', 'developer', 'operator', 'message-bodies', 'secret-administrator')),
  scope text not null check (scope = 'installation'),    -- the whole installation, the only scope for now
  granted_at timestamptz not null, granted_by text not null,
  primary key (user_id, role, scope));
create table sessions (
  id text primary key check (id ~ '^[0-9a-f]{16}$'),
  user_id bigint not null references users (id),
  fingerprint bytea not null check (octet_length(fingerprint) = 32),
  first_password boolean not null,
  created_at timestamptz not null, last_seen_at timestamptz not null, expires_at timestamptz not null);
create index sessions_user on sessions (user_id);
create table login_limits (
  name text primary key,                                 -- lowercased; '*' counts the installation's refused-login rows
  failures int not null, last_failure_at timestamptz not null, audited_at timestamptz);
alter table audit add column user_id bigint, add column credential text,
  add column via text not null default 'declared'
    check (via in ('declared', 'session', 'token', 'emergency-key', 'shared-token', 'local'));
alter table audit alter column via drop default;        -- from now on every writer names it
create index audit_at on audit (at);
create index audit_user_at on audit (user_id, at) where user_id is not null;
create index audit_subject_at on audit (subject, at);

-- V30__tokens.sql
create table tokens (
  id text primary key check (id ~ '^[0-9a-f]{16}$'),
  user_id bigint not null references users (id),
  name text not null check (name ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
  fingerprint bytea not null check (octet_length(fingerprint) = 32),
  created_at timestamptz not null, created_by text not null,
  expires_at timestamptz not null check (expires_at > created_at and expires_at <= created_at + interval '365 days'),
  last_used_at timestamptz, revoked_at timestamptz, revoked_by text);
create unique index tokens_live_name on tokens (user_id, name) where revoked_at is null;
create index tokens_expiring on tokens (expires_at) where revoked_at is null;
```

A session ends by deleting its row. At most 10,000 users; one more is 409.

## New routes of the admin API

| Route | Answer |
| --- | --- |
| `POST /admin/sessions {name, password}` | 201 `{session, expiresAt, idleSeconds, me}`; 401 `login-refused`, one text for every failure; 429; 503 `busy` |
| `GET /admin/me` | `{name, kind, roles, actions, via, firstPassword, expiresAt, emergencyKey}` |
| `POST /admin/me/password {current, next}` | 204; 401 for a wrong current password; 400 for a password the rules refuse; 429 |
| `POST /admin/me/logout` | 204 |
| `GET /admin/me/tokens`; `POST /admin/me/tokens {name, days, password}`; `POST /admin/me/tokens/{id}/revoke` | the list; 201 `{id, name, expiresAt, token}`; 204 |
| `GET /admin/users`; `GET /admin/users/{name}` | the users with their kind, roles, block, live sessions and tokens (never a secret) |
| `POST /admin/users {name, kind, roles}` | 201 `{name, firstPassword}` for a person, `{name}` for a technical account; 409 for a name taken |
| `POST /admin/users/{name}/roles {roles}` | the whole set of roles: 200 `{name, roles}` |
| `POST /admin/users/{name}/block`, `/unblock`, `/password-reset`, `/sessions/end` | 200; `/password-reset` answers `{firstPassword}` |
| `POST /admin/users/{name}/tokens {name, days}` | 201 `{id, name, expiresAt, token}`; 422 for a person |
| `GET /admin/tokens?expiringWithin=…`; `POST /admin/tokens/{id}/revoke` | the live tokens; 204 |
| `GET /admin/audit?…`; `GET /admin/messages/{id}/audit` | the two views of the audit |

Bodies of these routes are at most 4 KiB.

## Plugins' admin services

Every `/admin/modules/{plugin}/{path}` is `module.call`, granted by the developer role. `ModuleCalls` asks it after the
authentication and before its own 415, 413 and 503, so a refused caller never holds one of a plugin's places or sends
its 16 MiB. `AdminService` and `AdminRequest` do not change, and a plugin does not learn who called. A plugin that one
day needs another role, or the caller's name, is a change of the public api with a design of its own.

## The package of a message in monitoring

Every message in monitoring carries its package's name, as checked against the code: the list of recent messages, the
counts, a message's timeline, the DLQ, the held, stopped and lost work items and the CPU by step all carry `flow`, the
qualified id `<package>/<flow>`. A package's name matches `^[a-z][a-z0-9-]*$`, so the package is the part before the
first slash, and `PackageRef.ofFlow` takes it. One answer carries bare ids: `GET /admin/messages?key=…`. Step 3 adds
each message's `flow` to its items.

## The transition

**The release of step 1: both ways work.**
- Users, sessions and roles exist, and the admin API enforces the roles of every session.
- `RAIL8_ADMIN_TOKEN` is still the shared token: it may do every action, and the audit takes its actor from
  `X-Rail8-Actor`, with `via` `shared-token`. Without a token, an API on a loopback bind is still open, with `via` `local`.
- The command line, CI and the compose stacks do not change.
- The development server sends the session's cookie when there is one, else the shared token.
- The first administrator is made with `rail8 user add` and `RAIL8_ADMIN_TOKEN`, which in this release is still the shared
  token and serves as the emergency key too.

**The release of step 2: the shared token is restricted.**
- Named tokens exist. The command line reads `RAIL8_TOKEN`.
- `X-Rail8-Actor` is not read any more.
- `RAIL8_ADMIN_TOKEN` is the emergency key and nothing more.
- No request is open any more. Every `/admin` request needs a credential, and the admin API's rule that a bind other than
  loopback needs a token goes, since there is no open API left to protect.
- The development server sends the session's cookie, else `RAIL8_TOKEN`.
- In the same change, this repository's CI, the compose stacks, the quickstart and the UI's end-to-end scenarios move to
  named tokens. A compose stack makes its first administrator and a technical account `compose-deploy` with the developer
  role through the admin API, with the key, before its `deploy` runs.

The two steps follow each other closely: until step 2, the shared token can do everything.

## Limits

These are accepted:
- Sensitive data can be in an error's text and in headers, which an operator sees without the message bodies role.
- A developer can deploy any package of the installation.
- The audit does not protect against a person with access to Postgres.
- Until named tokens exist, the shared token can do everything, so the first step and the tokens follow each other
  closely.
- A session and a token are bearer credentials. Whoever holds one acts as its user until it ends: a session for at most
  12 hours, a token until its expiry or its revoke. A session is not bound to the UI server.
- The admin port speaks plain HTTP, and credentials cross it as they are. Keep the admin port on loopback or on a private
  network between the UI server, the command line and the nodes.
- Someone who knows a name can delay that user's login by up to 5 minutes at a time by failing it. The user's tokens still
  work, and an administrator's reset clears the delay.
- When Postgres is down, every admin request is 503, the emergency key's included.
- A script injected into the UI acts as the logged-in user for as long as the page is open. `HttpOnly` keeps the secret
  from it, not the power.
- Cookies are not kept apart by port. On `localhost` in development, another local server can receive the session's
  cookie; prefer `RAIL8_TOKEN` there.
- An administrator can give themselves any role. The audit is the only brake.
- Reads are not audited, except a view of a body and every use of the emergency key.

## Against an attacker

| What an attacker tries | What stands against it |
| --- | --- |
| an emergency key left set, or a short one | unset by default, at least 32 characters, no deploy, bodies, messages or tokens, every use in the audit before it acts, a WARNING at every use and a notice in the UI |
| the open loopback API through a flow that calls `127.0.0.1:8081` | it goes with step 2: every request needs a credential |
| guessing one user's password | 5 free failures, then a wait up to 300 s, claimed before the hash, the same on every node |
| spraying a few common passwords over many names | 12 characters at least, first passwords generated by the node, 2 hashes at once per node |
| telling names apart by time or by answer | a dummy hash and a dummy fingerprint, one 401 text for every refused login, a row for unknown names too |
| filling the audit with refused logins under random names | one row per name per hour, 1,000 an hour for the installation |
| a stolen session minting a lasting token | issuing a personal token needs a session and the password again, counted on the limit |
| a leaked token in a CI log | a prefix that scanners find, revoke by its id, an expiry of a year at most |
| a page of another site calling the UI server | `SameSite=Strict`, `Sec-Fetch-Site: same-origin` and the list of origins, as today |
| a page script reading the session | `HttpOnly`; the proxy refuses `/admin/sessions`; the UI server's login answers no secret |
| a request that skips the check | a route mounts only with an action; the audit's actor exists only after a check |
| an administrator who quietly reads bodies | the role must be assigned, and a self-assignment is marked in the audit |

## Tests

- One contract suite, `AccessContract`, for `PgAccess` (Testcontainers) and `MemoryAccess`, as a component and its mock
  pass one suite. Two `PgAccess` over one Postgres stand for two nodes: a revoke, a block and the login limit hold across
  them.
- A fake clock moves time: a session idle for 2 hours and one second is 401 `session-ended`; the waits of the login
  limit, the 7 days of a first password and a token's expiry the same. No test sleeps.
- A fake password hash is instant and counts its calls, which proves the dummy hash for unknown names. A variant that
  blocks on a latch holds both places, so the third login is 503 at once.
- `Access.fixed(caller)` authenticates every request as one caller, so the existing route tests state who calls.
- A test walks the router: every `/admin` route is mounted with an action, and the `Action` enum matches this
  document's table. A table test calls every route as each role, as the emergency key and as a first-password session,
  and expects 403 or not by the table.
- The UI server: `bearerOf` and `forward` stay pure functions tested on headers; the session routes take the node's
  client as an argument, as the git half's client does.

## Out

- Teams, custom roles, prefixes, a screen of scopes, and filtering results by scope.
- Sign-in through OIDC: step 5, with a design of its own. `source` and `subject` wait for it.
- A second factor, e-mail, lists of common passwords.
- Binding a session to the UI server that made it, and limits per client address: the node sees the UI server's
  address, not the browser's.
- A cache of credentials or roles.
- Auditing reads other than a body's.

## Steps

1. **Login in the UI.** `class:C`. The first step the owner sees.
   - The tables of `V29`: users, role assignments, sessions, the login limit, and the new columns of the audit.
   - `Access`, `Caller`, the `Action` enum and the mounting of every route with its action.
   - The login with its limit, the first password and its change, the session with its idle time and its end.
   - `rail8 user add` with `RAIL8_ADMIN_TOKEN`, which makes the first administrator.
   - The retention of the audit, the ended sessions and the login limit's rows.
   - The UI: the login, `/admin/me`, and the Users screen where an administrator creates users, assigns roles, blocks a
     user, resets a password and ends a user's sessions. `bearerOf` in the development server.
   - The audit carries the real name of a session's user, and a deploy carries the commit's author. The release and
     the undeploy are audited.
   - The command line and CI stay on the shared token.
2. **Named tokens.** `class:C`.
   - `V30` and the personal and technical tokens: issue, list, revoke, the warning of their expiry.
   - The technical account, the end of `X-Rail8-Actor` and of the open loopback API, and the shared token restricted
     to the emergency key.
   - `RAIL8_TOKEN` for the command line and for the personal token in the UI's development server.
   - This repository's CI, compose stacks, quickstart and end-to-end scenarios on named tokens.
3. **Message bodies.** `class:B`.
   - The message bodies role enforced on the body route, and the record of every body view.
   - The audit view at a message.
   - The `flow` of each item of `GET /admin/messages?key=…`.
4. **The Audit screen.** `class:B`. Filtered by user, action and time.
5. **Sign-in through OIDC** with one provider, roles assigned by hand. Labelled `later`; it has a design of its own.

Deploying a commit from the UI counts on the developer role and on the two names in the audit: the user who deployed
and the commit's author.
