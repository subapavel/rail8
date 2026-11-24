# Users, roles and the permission check

This document is the agreed design of who uses Rail8 and what they may do:
- the user model and the five roles;
- the one decision point that every admin route asks, and the list of actions;
- password storage, the limit of login attempts and the first password;
- the session of a user logged in to the UI, and the tokens of the command line and of machines;
- the emergency key, the audit, the tables in Postgres, and encryption on the admin port;
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
- The node authenticates every admin request from Postgres and caches nothing. The check is made when a request is
  admitted. A revoke, a block, a logout or a role taken away holds for every request admitted after it commits, on
  every node of the pool. A request admitted before then runs to its end, within its route's own limits.
- A token never changes an account. Acts on users, roles, sessions and other users' tokens need a person logged in, or
  the emergency key.
- The node still answers no browser. The UI server carries a browser's session to the node as a bearer credential.
- Every credential is random, is handed out once, and is stored only as its fingerprint.
- A password or a credential crosses a network only encrypted, unless an operator states that the network is protected
  another way.
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
in the audit, marked as one's own. Only a person may hold the administrator role.

## Actions

Each action has an id. The `Action` enum in the code is the whole table: an action, the roles that grant it, whether it
needs a person logged in, and whether the emergency key may do it. A test compares the enum with this table.

| Action | Routes | Granted by |
| --- | --- | --- |
| `message.read` | `GET /admin/messages` (every form), `/admin/message-counts`, `/admin/messages/{id}`, `/admin/messages/{id}/audit`, `/admin/dlq`, `/admin/work/held`, `/admin/work/stopped`, `/admin/work/lost` | operator |
| `message.body` | `GET /admin/messages/{id}/steps/{n}/body` | message-bodies |
| `message.retry` | `POST /admin/dlq/{id}/retry` | operator |
| `message.cancel` | `POST /admin/dlq/{id}/cancel`, `POST /admin/messages/{id}/cancel` | operator |
| `work.release` | `POST /admin/work/{seq}/release` | operator |
| `work.discard` | `POST /admin/work/{seq}/discard` | operator |
| `deployment.read` | `GET /admin/packages`, `/admin/pool/packages`, `/admin/flows`, `/admin/flows/{flow}/cpu`, `/admin/targets`, `/admin/components`, `/admin/steps`, `/admin/modules` | developer, operator |
| `package.deploy` | `POST /admin/deploy`, the check (`?check=true`) and the deploy alike | developer |
| `package.undeploy` | `DELETE /admin/packages/{name}` | developer |
| `module.call` | `GET`, `POST /admin/modules/{plugin}/{path}` | developer |
| `git.read` | `GET /admin/git` | developer, operator, administrator |
| `user.read` | `GET /admin/users`, `GET /admin/users/{name}` | administrator, the key |
| `user.create` | `POST /admin/users` | administrator logged in, the key |
| `roles.change` | `POST /admin/users/{name}/roles`, for each role other than administrator | administrator logged in |
| `administrators.change` | the same route, for the administrator role | administrator logged in, the key |
| `user.block` | `POST /admin/users/{name}/block`, `/unblock` | administrator logged in, the key |
| `user.password-reset` | `POST /admin/users/{name}/password-reset` | administrator logged in, the key |
| `session.end` | `POST /admin/users/{name}/sessions/end` | administrator logged in, the key |
| `token.read` | `GET /admin/tokens` | administrator, the key |
| `token.issue` | `POST /admin/users/{name}/tokens`, for a technical account only | administrator logged in |
| `token.revoke` | `POST /admin/tokens/{id}/revoke` | administrator logged in, the key |
| `token.ceiling` | `POST /admin/tokens/ceiling` | administrator logged in |
| `audit.read` | `GET /admin/audit` | administrator |
| `secret.list` | the secret store's list: names, never a value | developer, secret-administrator |
| `secret.write` | the secret store's create and overwrite | secret-administrator |
| `secret.delete` | the secret store's delete | secret-administrator |
| `self.read` | `GET /admin/me` | every caller, the key included |
| `self.password` | `POST /admin/me/password` | a person logged in |
| `self.logout` | `POST /admin/me/logout` | a session |
| `self.tokens` | `GET /admin/me/tokens`, `POST /admin/me/tokens/{id}/revoke` | a person |
| `self.token-issue` | `POST /admin/me/tokens` | a person logged in |

"Logged in" means a session, never a token. A token of an administrator may do the reads (`user.read`, `token.read` and
`audit.read`), but an action that needs a session is 403 `session-required`.

The actions that exist before step 1 are `message.read`, `message.body`, `message.retry`, `message.cancel`,
`work.release`, `work.discard`, `deployment.read`, `package.deploy`, `package.undeploy` and `module.call`. The transition
names them. The bridge also keeps `git.read`, which came later: a read that holds no secret, which the UI's development server
reads with the shared token.

`POST /admin/sessions` (the login) is the only `/admin` route with no caller. `/health`, `/live` and `/ready` stay
open, as today. There is no `secret.read`: nobody reads a secret's value back.

**Roles change by difference.** `POST /admin/users/{name}/roles {add, remove}`:
- At the door, before the body is read, the caller must be allowed `roles.change` or `administrators.change`.
- Each role is then checked by its own action.
- The change is applied under the lock of the user's row.
- The audit names what actually changed. A role already held, or already absent, is not a change.
- Two administrators who edit one user at once do not revert each other.

A user created with roles (`POST /admin/users {name, kind, roles}`) is checked for each role as an `add`. The emergency
key may create a person with no role or with the administrator role only, and a technical account with no role.

Invariants, each answered 409 when a request would break it:
- At least one unblocked person holds the administrator role.
- A technical account never holds the administrator role.
- Nobody blocks themselves.
- An administrator issues tokens only for technical accounts. A person's tokens are their own, so the audit never names a
  person who did not act.

**Locks.** Every change to an account takes its `users` row `for update` first. A change that can lower the number of
administrators also takes one advisory lock for the whole pool first. Such a change is the removal of the administrator
role, or a block of an administrator. So two nodes cannot each remove the administrator they count on the other.

## The decision point

```java
// dev.rail8.runtime.access
enum Role { ADMINISTRATOR, DEVELOPER, OPERATOR, MESSAGE_BODIES, SECRET_ADMINISTRATOR }   // stored and answered by id
enum Action { MESSAGE_READ, MESSAGE_RETRY, …;
    String id(); Set<Role> roles(); boolean needsSession(); boolean emergencyKey(); boolean beforeStepOne(); Who who(); }
record PackageRef(Optional<String> name) { static PackageRef ANY; static PackageRef ofFlow(String qualifiedFlow); }
record Caller(OptionalLong user, String name, Kind kind, Set<Role> roles, Via via, Optional<String> credential,
              boolean firstPassword) {
    boolean may(Action action, PackageRef pkg);                 // the decision point; the package is taken and ignored
    void require(Action action, PackageRef pkg);                // may, else 403 naming what grants it
    Actor acting(Action action, PackageRef pkg, String remote); // require, then the audit's actor: the only way to get one
}
interface Access {   // Postgres behind it; a fake in memory for tests
    Caller authenticate(Optional<String> authorization, String method, String path, String remote);   // 401, 503
    Login login(String name, String password, Optional<String> device, String remote);                // 401, 429, 503
    …   // the password change, the logout, and the administrator's acts on users, roles, sessions and tokens
}
// dev.rail8.core.audit: replaces FailedMessages.Actor and the actor strings of the work queue and of the deploy
record Actor(String name, OptionalLong user, String via, Optional<String> credential, String remote) {}
```

`credential` is the id of the session or the token, never its secret. `acting` copies it into the `Actor`.

`may` decides in this order:
1. During the bridge, while the marker in Postgres says so:
   - the shared token may do the actions that exist before step 1 and the emergency key's actions;
   - a request without a credential on a loopback bind may do the actions that exist before step 1.
   Once the marker is closed, both are 401.
2. The emergency key may do the actions whose `emergencyKey()` is true, and nothing else.
3. A caller with a first password may do `self.read`, `self.password` and `self.logout`, and nothing else.
4. A token may not do an action that needs a session.
5. An action of the caller's own (`self.*`) is granted by who the caller is: every caller, a person, or a session.
6. Every other action is granted when the caller holds one of its roles.

**Mounting.** `AdminServer` mounts an `/admin` route only through `admin.get`, `admin.post` and `admin.delete`. Each
names the route's path and its action. Only the change of roles names two actions, either of which lets a caller
through the door. The route's body takes the checked `Caller`. A route without an action does not compile, and a test
walks the router and fails for any `/admin` route mounted otherwise. The login and the browser guard are the only
exceptions.

**The order of every `/admin` request:**
1. The browser guard: a request with an `Origin` header or a `Sec-Fetch-Site` other than `none` is 403, as today.
2. `POST /admin/sessions` goes to the login.
3. Every other request is authenticated: 401 or 503. An unknown path is authenticated too, so a caller without a
   credential learns nothing of the routes. No body has been read yet.
4. The router: 404, 405.
5. The action at the door: `who.require(action, PackageRef.ANY)`, 403. Still no body has been read, so a refused caller
   never sends a large body.
6. The body: 400, 413.
7. The route's own checks: 400, 404, 409, 422. Before a route that acts on one message or work item changes anything,
   it asks `who.acting(action, PackageRef.ofFlow(flow), remote)` with the flow it acts on. It passes the `Actor` to the
   store, which writes the act and its audit row in one transaction.

A deploy knows its package only once it has read the commit. Its check names `PackageRef.ANY` for now. A later scope by
package asks again inside the deploy, once the manifest names the package.

**Errors.** Every refusal has a `code`:
- 401 `{error, code}`, with `WWW-Authenticate: Bearer`. The codes:
  - `no-credential`.
  - `unknown-credential`: a credential of the wrong form, an unknown id or a wrong secret.
  - Only once the secret matched: `session-ended`, `token-expired`, `token-revoked` or `blocked`.
  - `login-refused`, for a refused login, with one text for every reason. The audit holds the reason.
- 403 `{error, code, needs}`. The codes:
  - `not-allowed`, with `needs` the roles that grant the action.
  - `wrong-password`, for a wrong current password asked again by the password change or the issue of a personal token.
    The session goes on.
  - `session-required`, for a token.
  - `password-change-required`.
  - `browser`, from the guard.
- 429 `{error, code: "too-many-attempts"}` with `Retry-After`: the login, the password change and the issue of a
  personal token.
- 503:
  - the Postgres pool refused, as today;
  - `busy` with `Retry-After: 1`, when no place to hash a password is free;
  - a required audit row could not be written (a use of the emergency key, a view of a body, a deploy), and then
    nothing runs.

Plugins' admin services answer these as problems (`application/problem+json`) of the types
`urn:rail8:admin:problem:unauthenticated` and `urn:rail8:admin:problem:forbidden`.

## Credentials

Every credential has one form:

```
rail8s_<id>_<secret>     a session
rail8t_<id>_<secret>     a token
rail8d_<id>_<secret>     a device: it selects the row of the login limit, and proves nothing else
rail8k_<secret>          the emergency key
```

- `<id>` is 16 lowercase hex digits, 64 random bits. It names the credential in lists, in the audit and in logs, and proves
  nothing alone. A token pasted into the revoke dialog is revoked by its id.
- `<secret>` is 43 base64url characters, 256 random bits from `SecureRandom`.
- The fixed prefix lets a secret scanner find a leaked credential.

Postgres keeps the fingerprint: SHA-256 of the whole string in UTF-8, 32 bytes. The credential itself is shown once and
never stored.

The check:
1. The string is parsed by position. Anything else is 401 `unknown-credential`, without a read.
2. The row is read by its id, joined with its user and the user's roles, in one query.
3. The fingerprint is compared with `MessageDigest.isEqual`. An unknown id is compared with a fixed dummy fingerprint, so
   both paths take the same time.
4. Only then are expiry, revocation, the session's idle time and the user's block checked.

A slow hash is not needed: a fingerprint of 256 random bits gives nobody anything to guess from. A session's
`last_seen_at` is written at most once a minute and a token's `last_used_at` at most once an hour.

**The account's generation.** Each user has a `generation`. A block, a password change, a password reset and the end of
the user's sessions each raise it. A credential is made only while the generation is unchanged and the user is not
blocked: a session, a personal token, a device. This is checked in the transaction that inserts the credential, under
the user's row taken `for share`, and `for update` for a personal token, whose user may hold at most 20, and for a login
that issues a device, whose user may hold at most 10. A login on one node therefore cannot recreate access that a reset
on another node has just ended.

## Passwords

- **Hash.** Argon2id (RFC 9106) from Bouncy Castle `org.bouncycastle:bcprov-jdk18on` (MIT licence), class
  `Argon2BytesGenerator`:
  - memory 19,456 KiB, 2 passes, parallelism 1;
  - a 16-byte salt and a 32-byte hash;
  - stored as a PHC string, `$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`.
  A login whose stored parameters differ from the current ones hashes the password again, so the parameters can change
  without a migration.
- **Cost.** A node hashes at most 2 passwords at once, with no queue. Logins use at most 2 cores and 38 MiB. Among
  logins, only one that presents a valid device credential takes the second place; the acts of a session or the key take
  it too: a password change, a personal token's issue, a new person and a reset. So a flood of logins from unknown
  browsers cannot keep a user's known browser out, nor an administrator's reset. A login that finds no place it may take is 503 `busy` with `Retry-After: 1`. An unknown name, a technical account and a blocked user still
  cost one hash of a fixed dummy, so the time does not tell them apart.
- **Rules.** A password is 12 to 128 characters after NFC normalisation, must not contain the user's name and must differ
  from the current one. A login's body is at most 1 KiB. There are no rules of composition.

### The limit of login attempts

A login goes in this order:
1. It reads the user, if one has the name, with its generation, and the device credential if the login presents one,
   in one statement.
2. It takes a place to hash that it may take, or answers 503 `busy`, counting nothing.
3. It picks the row of `login_limits` to count on:
   - the name's row, `(name, '')`. The name is taken as typed and lowercased, and an unknown name has a row too, so a
     refusal does not tell which names exist;
   - the device's row, `(name, <device id>)`, when the login presents a device credential that is valid for that user.
4. It claims the attempt, in one statement:

   ```sql
   insert into login_limits as l (name, device, failures, last_failure_at) values ($1, $2, 1, $3)
   on conflict (name, device) do update
     set failures = least(l.failures + 1, 1000000), last_failure_at = greatest(l.last_failure_at, $3)
     where l.failures < 5
        or l.last_failure_at + least(power(2, least(l.failures, 14) - 5), 300) * interval '1 second' <= $3
   returning failures
   ```

   No row back is 429 with `Retry-After`. Nothing is hashed or counted.
5. It hashes, and then answers.

- The first 5 failures are free. After them, each attempt waits 1, 2, 4 and so on, up to 300 seconds, after the last
  failure: about 300 guesses per row per day.
- The upsert takes the row's lock, so the count holds across the nodes of the pool: 50 attempts at once get 5 hashes.
- `$3` is the node's injected clock, as for every instant in these tables; the SQL never reads `now()`.
- `greatest` keeps a node whose clock is behind from shortening a wait.
- A success forgives only the failures up to its own claim: `failures = greatest(failures - claimed, 0)`, with `claimed`
  the number its claim returned. Failures counted after it, on any node, stand.
- A password reset by an administrator deletes every row of the name.
- The password change and the issue of a personal token claim the name's row, so a stolen session cannot guess the
  current password faster than a login.
- The table holds at most 100,000 names. Beyond that, an unknown name is refused without a row; it cannot succeed anyway.
  The count is read before the insert, so the logins in flight can pass the cap by at most their number.

**Devices.** A browser that logged in as a user keeps a device credential. A login that presents one is counted on that
device's own row. So someone who keeps failing a known name keeps the name's row waiting, but not the user's own browser.
- The node issues one to a full login, past the first password, that asks for it. The UI server's login asks; the
  command line's does not.
- It is valid for 90 days from its issue. Every full login that asks is issued a new one, and the one it presented ends,
  with its row of the limit. So a browser's device lives 90 days from its last login there.
- When two logins present one device at once, the first replacement wins. The other gets its session and no device,
  and ends no other device.
- A user has at most 10. The eleventh ends the oldest, and an issue ends every dead one.
- It holds only while the user's generation is the one it was issued at, so a block, a password change or reset, and
  the end of the user's sessions end them all.
- Its row has its own 5 free failures and the same waits, so a stolen device buys a bounded number of guesses.
- Anything that is not a valid device of the user (malformed, unknown, a wrong secret, another user's, ended or expired)
  counts as none, silently: the name's row and the shared place.
- It is never a factor of the login: the password decides alone.

### The first password

- An administrator never types a password for someone else. Creating a person, and resetting a password, answers once
  with a first password that the node generates: 20 characters of lowercase base32, 100 random bits. It is valid for
  7 days; after that the login is refused and an administrator resets it again.
- A login with a first password gives a session that may only read itself, change the password and log out. Anything
  else is 403 `password-change-required`.
- `POST /admin/me/password {current, next}` changes it. The same session goes on without the restriction. Every other
  session of the user ends, and the generation is raised.

## The session in the UI

**What carries it.** Between the browser and the UI server, a cookie:
- `__Host-rail8-session` with `HttpOnly; Secure; SameSite=Strict; Path=/`, when the UI server is served over HTTPS;
- `rail8-session` with the same attributes but without `Secure`, on `http://localhost` in development.

Between the UI server and the node, `Authorization: Bearer rail8s_…`. The UI server keeps no state and holds no secret
of its own, so several UI servers, and their restarts, keep every user logged in. The node sees no browser header, so
it still refuses every browser.

The device credential is a second cookie of the same kind, `__Host-rail8-device` or `rail8-device`, for 90 days. The UI
server puts it in the login's body, `""` when the browser holds none, and sets it only from a login's answer that
carries a new one. It never clears it. No page ever sees it.

**Two modes.** The UI server runs in one of two modes, chosen at its start and never mixed:
- **Token mode**, when it holds a token: `RAIL8_TOKEN`, or the shared token during the bridge. There is no login and one
  user: every call to the node is the token's, and the server listens on loopback only.
- **Session mode**, otherwise. The proxy, the git half and the mapping half send every call to the node with the
  caller's own session from the cookie. A request without a session is answered 401 by the UI server and is sent
  nowhere. There is no fallback to a token. After a logout, `GET /session` is 401.

The development server listens on loopback in both modes for now, and refuses to start on another address.

**The UI server's own routes**, each under today's rule (`Sec-Fetch-Site: same-origin` and an `Origin` from the fixed
list):
- `POST /session {name, password}` sends the login to the node's `POST /admin/sessions` with the device credential, and
  sets the cookies. It answers what `GET /admin/me` answers, with the session's id and without its secret.
- `POST /session/end` sends the logout and clears the session's cookie.
- `GET /session` answers `GET /admin/me`, with the session's id.

The `/admin` proxy refuses `/admin/sessions` (403), so no page script ever sees a session's secret. It passes JSON
bodies of POST requests up to 64 KiB, which the users' routes, the password change and a cancel's reason need. It
passes `Retry-After` back. It clears the session's cookie when the node answers 401 `unknown-credential`,
`session-ended` or `blocked`, and on no other answer.

**A request names its session.** The page keeps the id of the session it was shown, in memory only. Every request it
sends through the proxy, the git half or the mapping half names that id in the header `Rail8-Session`, reads included.
Every request other than `GET` must name it. The UI server answers 409 `session-changed` when the cookie holds another
session or none, or when a request names another id. The page then reads `GET /session`: when it names the same user,
the page takes the new id and sends the request again once, and a second 409 reloads the page; when it names another
user, the page reloads. So a tab shown to one user never reads or writes as another.

`GET /admin/me` answers `{name, kind, roles, actions, refused, via, firstPassword, expiresAt, emergencyKey}`. `actions`
lists the actions the caller may do. `refused` gives each other action with why: `{code, needs}`, the code
`not-allowed`, `session-required` or `password-change-required` and the roles that may do it, as the node would refuse
it. The UI disables what the caller may not do, saying why from `refused`, and holds no table of roles of its own.

**A deploy from the UI.** The check and the deploy itself are both `package.deploy`, and both pass the node's rule that
a deployed node takes only a commit reachable from its `RAIL8_GIT_REF`. In session mode, the git half sends the
session and no `X-Rail8-Actor`. The commit's author comes from the commit.

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

When the node answers 401 because the session ended, the UI shows the login over the open tabs, and from then on sends
nothing new. It holds each refused request, and each new one, with its method, headers and body: at most 100 requests and
32 MiB of their bodies, beyond which a request gets its 401. The login is sent once every request of the ended session
has answered, or after 10 s. Then:
- when the same user logged in, each held request is sent again once; after a first password, only once it is changed;
- when another user logged in, the page reloads, and no held request is sent, so the new user decides for themselves.

That is safe because the node did nothing for a 401.

A UI server outside development, when it comes, runs in session mode only and holds no token.

## Tokens

- **Personal.** A person issues their own tokens for the command line (`self.token-issue`). A token carries its user's roles as
  they are at each request, but never changes an account. Issuing one needs a session and asks the password again, so a
  stolen session or token cannot mint a lasting credential.
- **Technical.** An administrator, logged in, creates a technical account, gives it roles, and issues its tokens
  (`token.issue`).
- **Form.** A name, `^[a-z0-9][a-z0-9-]{0,63}$`, unique among the user's unrevoked tokens. A new token that takes the name
  of the user's expired token revokes it in the same transaction.
- **Expiry.** Every token expires: 90 days by default and 365 at most, for a person's tokens and a technical account's
  alike.
- **The ceiling.** An administrator may lower the ceiling for the whole installation (`token.ceiling`), to 1 to 365 days,
  and raise it again up to 365, never beyond. A lower ceiling holds for live tokens too: a token older than the ceiling
  is 401 `token-expired`. The default is 90 days, or the ceiling when it is lower. The change is in the audit.
- **Shown once.** The 201 that issues it carries the token, `{id, name, expiresAt, token}`. Postgres keeps only its
  fingerprint.
- **Revoked at once.** The user revokes their own; an administrator and the emergency key revoke any. The next request
  with it is 401 on every node.
- **Expiring soon.** `GET /admin/tokens?expiringWithin=P14D` lists the live tokens that expire within 14 days; the UI shows
  them to an administrator.
- **Limits.** At most 20 live tokens per user.

The command line sends `Authorization: Bearer $RAIL8_TOKEN`. Only `rail8 user` and `rail8 bootstrap` send
`RAIL8_ADMIN_TOKEN`, which is how `rail8 user add` makes the first administrator. No other command ever sends a value of
the emergency key's form. During the bridge, another command without `RAIL8_TOKEN` sends a shared token from
`RAIL8_ADMIN_TOKEN`, as today. CI holds the token of a technical account in `RAIL8_TOKEN`, such as `ci-deploy` with the
developer role. `rail8 deploy` does not change.

## The emergency key

`RAIL8_ADMIN_TOKEN` on the node is the emergency key. It is unset by default. It creates the first administrator and
rescues an installation whose administrators are locked out.

- **What it may do:**
  - `self.read`, `user.read`, `token.read`;
  - `user.create`, of a person with no role or with the administrator role, or of a technical account with no role;
  - `administrators.change`, `user.block`, `user.password-reset`, `session.end` and `token.revoke`.
- **What it may never do:** deploy, undeploy, read messages or bodies, retry, cancel, call a module, read the audit, touch
  a secret, change any role but administrator, or issue a token. It acts on users only. Any path from it to a deploy or
  to a body goes through an account it creates, promotes or resets, and through a login to that account, all in the
  audit.
- **Its form.** `rail8k_` and 43 base64url characters, printed by `rail8 key new`. From step 1, a value of this form is
  the emergency key and nothing more, even during the bridge. During the bridge, a value of any other form is the shared
  token. After the cut, the node does not start with a value of another form. The node compares the key as
  `MessageDigest.isEqual(sha256(presented), sha256(key))`.
- **Its audit.** Every request with it, refused or not, first writes an `emergency-key.use` row with the method, the path,
  the remote address and whether it was allowed. When that row cannot be written, the answer is 503 and nothing runs. Its
  acts are audited with the actor `emergency-key`.
- **Its notices.** While it is set, the node logs a WARNING at its start and at every use. `GET /admin/me` of an
  administrator carries `emergencyKey: true`, which the UI shows as a notice to unset it.

The first administrator, with the key in the environment of the command line:

```sh
rail8 user add alice --role administrator      # prints alice's first password, once
```

Alice logs in, chooses her password, gives herself the roles she needs and creates the other users. The key is then
unset and the node restarted.

**A stack that starts itself**, such as a compose stack or CI, runs `rail8 bootstrap --admin <name> --deploy-account
<name> --token-file <path>` with a key that `rail8 key new` generated for that run. Using only the admin API, it:
1. creates the administrator with the key;
2. logs in with the first password and sets a new one that it generates and prints once;
3. in that session, creates the technical account with the developer role, issues its token and writes it to the file.

No password or key is a fixed value in a file of the repository.

## The audit

One `audit` table, two views:
- at a message, `GET /admin/messages/{id}/audit` (operator): who retried it, cancelled it or viewed its body;
- the Audit screen, `GET /admin/audit?user&action&from&to&after&limit` (administrator), newest first, at most 500 a page
  and 50 by default.

The audit can always be taken out of the platform:
- A program reads it through `GET /admin/audit`, filtered by user, action and time, page by page.
- The Audit screen exports the filtered list as a CSV file (UTF-8, every column), with every page of it, up to 100,000
  rows. A longer list asks for a narrower time.

**Who.** `actor` is the user's name; names never change and users are never deleted, so it keeps its meaning. Next to it:
- `user_id`;
- `via`, how the caller proved who it is;
- `credential`, the session's or the token's id, never its secret.

The remote address stays in the detail, as today. For a call through the UI server it is the UI server's address.

| Credential | `actor` | `user_id` | `via` |
| --- | --- | --- | --- |
| a session | the user's name | the user's id | `session` |
| a token | the user's name | the user's id | `token` |
| the emergency key | `emergency-key` | null | `emergency-key` |
| the shared token, during the bridge only | the `X-Rail8-Actor` header, `unknown` without one | null | `shared-token` |
| no credential on a loopback bind, during the bridge only | the same | null | `local` |
| rows written before step 1, and by a node older than it | as they were | null | `declared` |

**A deploy** carries two names:
- `actor`, the user who deployed;
- `detail.author`, the commit's author as git holds it (`Name <email>`), which the node reads from the commit itself,
  not from a header.

The command line, CI and the UI therefore leave the same record.

A deploy that changes the pool writes its audit row as one more statement in the transaction that saves its record
(`PoolLock.save`). An undeploy does the same in the transaction that deletes it (`PoolLock.delete`). When that row
cannot be written, the record is not written either, and the deploy rolls back as after any other failed save. A
refused deploy changes nothing, and its row is written after the fact, as today.

**New entries:** `login`, `login.refused`, `logout`, `password.change`, `user.create`, `user.block`, `user.unblock`,
`user.password-reset`, `user.roles`, `session.end`, `token.issue`, `token.revoke`, `emergency-key.use` and
`message.body.view`, and `token.ceiling` with `{from, to, shortened}`, `shortened` the unrevoked tokens whose expiry it
moved earlier, expired by the node's clock or not.
- `user.roles` carries `{added, removed, self}`, so assigning oneself the message bodies role is marked.
- A release of a held item (`work.release`) and an undeploy (`undeploy`) are audited too. They join the deploy, the
  discard, the retry and the cancel, which already are.

**A view of a body** is written before any byte of the body is sent. When that row cannot be written, the answer is 503.
During the bridge, a view with the shared token or on the open loopback API is written too, with its `via`.

**Refused logins are limited in number:**
- A row of `login_limits` writes a `login.refused` row for the first failure of a run, then at most one an hour, carrying
  the count of failures since.
- Attempts refused with 429 write nothing.
- The whole installation writes at most 1,000 `login.refused` rows an hour. The row `('*', '*')` of `login_limits`
  counts them: `failures` is the count and `last_failure_at` the start of the hour. No login lands on that row, because a
  device's id is 16 hex digits. Beyond the 1,000, a refusal is counted there and not written.

**Retention.** `RAIL8_AUDIT_MAX_AGE`, ISO-8601, from P30D to P3660D, P365D by default. It may not be shorter than
`RAIL8_RETENTION_MAX_AGE`, or the node does not start: a message's status reads its cancel from the audit, so the audit
outlives the messages. The node's retention job deletes, in its batches:
- audit rows older than that;
- ended sessions, devices and tokens that expired or were revoked longer ago than that;
- `login_limits` rows without a failure for 24 hours.

## Encryption on the admin port

- The admin listener takes a certificate and its key, `RAIL8_ADMIN_TLS_CERT` and `RAIL8_ADMIN_TLS_KEY` (PEM files),
  loaded as the node's planned HTTPS on its HTTP port loads its own. They are read at the start; a new certificate
  comes with a rolling restart.
- The command line and the UI server refuse to send a password or a credential to an `http://` address other than
  loopback, unless `RAIL8_ADMIN_TLS=off`. They trust the system's certificates, or the one in `RAIL8_ADMIN_CA`.
- On a bind other than loopback without a certificate:
  - during the bridge, the node starts with a WARNING and refuses logins, sessions and named tokens there, unless
    `RAIL8_ADMIN_TLS=off`;
  - after the cut, the node does not start, unless `RAIL8_ADMIN_TLS=off`.
- `RAIL8_ADMIN_TLS=off` states that the network between the UI server, the command line and the nodes is protected
  another way, and the node logs a WARNING at its start.
- The health probes and the compose stacks' health checks follow the listener: HTTPS when it has a certificate.

## Tables

Each step adds its migration under the next free number: the users (1a), the devices (1f), the tokens (2a) and the cut
(2d).

```sql
-- the users (1a)
create table users (
  id bigint generated always as identity primary key,
  name text not null unique check (name ~ '^[a-z][a-z0-9._-]{1,63}$'),  -- never changes; users are blocked, not deleted
  kind text not null check (kind in ('person', 'technical')),
  source text not null check (source = 'rail8'),         -- 'oidc:<provider>' comes with sign-in through OIDC
  subject text,                                          -- the provider's subject then; null for 'rail8'
  password text,                                         -- the PHC string; null for a technical account
  first_password boolean not null default false,
  password_set_at timestamptz,
  generation bigint not null default 0,                  -- raised by a block, a password change or reset, sessions ended
  created_at timestamptz not null, created_by text not null,
  blocked_at timestamptz,
  check (kind = 'person' or password is null),
  unique (source, subject));
create table role_assignments (
  user_id bigint not null references users (id),
  role text not null check (role in ('administrator', 'developer', 'operator', 'message-bodies', 'secret-administrator')),
  scope text not null check (scope = 'installation'),    -- the whole installation, the only scope for now
  granted_at timestamptz not null, granted_by text not null,
  granted_via text not null check (granted_via in ('session', 'emergency-key', 'shared-token')),
  primary key (user_id, role, scope));
create table sessions (
  id text primary key check (id ~ '^[0-9a-f]{16}$'),
  user_id bigint not null references users (id),
  fingerprint bytea not null check (octet_length(fingerprint) = 32),
  first_password boolean not null,
  created_at timestamptz not null, last_seen_at timestamptz not null, expires_at timestamptz not null);
create index sessions_user on sessions (user_id);
create table login_limits (
  name text not null,                                    -- lowercased as typed
  device text not null default '',                       -- '' for the name's row, a device's id, or '*' for ('*', '*'),
                                                         -- which counts the installation's refused-login rows
  failures int not null, last_failure_at timestamptz not null, audited_at timestamptz,
  primary key (name, device));
create table admin_bridge (                              -- one row: open until the cut
  only_row boolean primary key default true check (only_row),
  open boolean not null);
insert into admin_bridge values (true, true);
alter table audit add column user_id bigint, add column credential text,
  add column via text not null default 'declared'        -- the default stays until the cut, for the writers of older nodes
    check (via in ('declared', 'session', 'token', 'emergency-key', 'shared-token', 'local'));
create index audit_at on audit (at);
create index audit_user_at on audit (user_id, at) where user_id is not null;
create index audit_subject_at on audit (subject, at);

-- the devices (1f)
create table devices (
  id text primary key check (id ~ '^[0-9a-f]{16}$'),
  user_id bigint not null references users (id),
  fingerprint bytea not null check (octet_length(fingerprint) = 32),
  generation bigint not null,                            -- the user's at issue; another one ends the device
  created_at timestamptz not null, expires_at timestamptz not null);
create index devices_user on devices (user_id);

-- the tokens (2a)
create table tokens (
  id text primary key check (id ~ '^[0-9a-f]{16}$'),
  user_id bigint not null references users (id),
  name text not null check (name ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
  fingerprint bytea not null check (octet_length(fingerprint) = 32),
  created_at timestamptz not null, created_by text not null,
  expires_at timestamptz not null check (expires_at > created_at and expires_at <= created_at + interval '8760 hours'),
  last_used_at timestamptz, revoked_at timestamptz, revoked_by text);
create unique index tokens_live_name on tokens (user_id, name) where revoked_at is null;
create index tokens_expiring on tokens (expires_at) where revoked_at is null;
create table token_ceiling (                             -- one row: the installation's longest token, in days
  only_row boolean primary key default true check (only_row),
  max_days int not null check (max_days between 1 and 365));
insert into token_ceiling values (true, 365);

-- the cut (2d)
update admin_bridge set open = false;
alter table audit alter column via drop default;
```

A session ends by deleting its row. At most 10,000 users; one more is 409.

## New routes of the admin API

| Route | Answer |
| --- | --- |
| `POST /admin/sessions {name, password, device?}` | 201 `{session, expiresAt, idleSeconds, device?, me}`; 401 `login-refused`, one text for every failure; 429; 503 `busy` |
| `GET /admin/me` | `{name, kind, roles, actions, refused, via, firstPassword, expiresAt, emergencyKey}` |
| `POST /admin/me/password {current, next}` | 204; 403 `wrong-password`; 400 for a password the rules refuse; 429 |
| `POST /admin/me/logout` | 204 |
| `GET /admin/me/tokens`; `POST /admin/me/tokens {name, days, password}`; `POST /admin/me/tokens/{id}/revoke` | `{tokens, maxDays, defaultDays}`; 201 `{id, name, expiresAt, token}`, 403 `wrong-password`; 204 |
| `GET /admin/users`; `GET /admin/users/{name}` | the users with their kind, roles and who granted them, block, live sessions and tokens (never a secret) |
| `POST /admin/users {name, kind, roles}` | 201 `{name, firstPassword}` for a person, `{name}` for a technical account; 409 for a name taken |
| `POST /admin/users/{name}/roles {add, remove}` | 200 `{name, roles, added, removed}` |
| `POST /admin/users/{name}/block`, `/unblock`, `/password-reset`, `/sessions/end` | 200; `/password-reset` answers `{firstPassword}` |
| `POST /admin/users/{name}/tokens {name, days}` | 201 `{id, name, expiresAt, token}`; 409 for a person |
| `POST /admin/tokens/ceiling {maxDays}` | 200 `{maxDays, defaultDays}`; 400 beyond 1 to 365 |
| `GET /admin/tokens?expiringWithin=…`; `POST /admin/tokens/{id}/revoke` | `{tokens, maxDays, defaultDays}`, the live tokens and the ceiling; 204 |
| `GET /admin/audit?…`; `GET /admin/messages/{id}/audit` | the two views of the audit |

Bodies of these routes are at most 4 KiB. A login's `device` is the device credential the browser holds, or an empty
string when it holds none; the answer carries `device` when the node issued a new one.

## Plugins' admin services

Every `/admin/modules/{plugin}/{path}` is `module.call`, granted by the developer role. `ModuleCalls` asks it after the
authentication and before its own 415, 413 and 503, so a refused caller never holds one of a plugin's places or sends
its 16 MiB. `AdminService` and `AdminRequest` do not change, and a plugin does not learn who called. A plugin that one
day needs another role, or the caller's name, is a change of the public api with a design of its own.

## The package of a message in monitoring

Every message in monitoring carries its package's name, as checked against the code. These answers carry `flow`, the
qualified id `<package>/<flow>`:
- the list of recent messages and the counts;
- a message's timeline;
- the DLQ;
- the held, stopped and lost work items;
- the CPU by step;
- the searches by key and by step, `GET /admin/messages?key=…` and `GET /admin/messages?flow=…&step=…`, whose items are
  `{id, flow}`.

A package's name matches `^[a-z][a-z0-9-]*$`, so the package is the part before the first slash, and `PackageRef.ofFlow`
takes it.

## The transition

**The bridge** lasts from step 1 to the cut. Both ways work:
- Users, sessions and roles exist, and the admin API enforces the roles of every session, the message bodies role
  included.
- `RAIL8_ADMIN_TOKEN` of the emergency key's form is the key alone. A value of any other form is the shared token, as
  today:
  - It may do the actions that exist before step 1, and the emergency key's actions. Those are audited as the key's,
    except `self.read`.
  - The audit takes its actor from `X-Rail8-Actor`, with `via` `shared-token`.
- Without a token, an API on a loopback bind may do the actions that exist before step 1, with `via` `local`. Without a
  token there is no key, so a user is made only once the node has one.
- A role assignment records who granted it and how (`granted_via`). The Users screen shows it.
- Each node reads the marker `admin_bridge` with every authentication that would use the bridge's powers.
- Step 1 is a rolling upgrade. The audit's `via` keeps its default, so the writers of an older node go on working.
- The command line, CI and the compose stacks do not change until step 2.
- The development server runs in token mode with the shared token, or in session mode without one.
- The first administrator is made with `rail8 user add` and `RAIL8_ADMIN_TOKEN`: a key from `rail8 key new`, or a
  shared token, which during the bridge serves as the key too.

**The cut** ends the bridge. It is the last change of step 2, after every caller of this repository moved to named
tokens:
- Its migration closes the marker. Once it has run, every node of step 1 answers 401 to the shared token and to every
  request without a credential, so the open loopback API is gone.
- It drops the default of the audit's `via`, so a node older than step 1 fails every audited write. The upgrade note
  says to stop such nodes before the cut.
- `X-Rail8-Actor` is not read any more.
- `RAIL8_ADMIN_TOKEN` must have the emergency key's form, or the node does not start. The value that was the shared token
  cannot carry over: it has another form.
- The role assignments granted through the shared token are listed for an administrator to review.
- The rule that a bind other than loopback needs a token gives way to the rule of encryption: a certificate, or
  `RAIL8_ADMIN_TLS=off`.
- The development server runs in token mode with `RAIL8_TOKEN`, or in session mode.

The two steps follow each other closely: until the cut, the shared token can do everything that exists today.

## Limits

These are accepted:
- Sensitive data can be in an error's text, in headers, in a message's properties and keys, and in the lines its steps
  logged. An operator sees all of them without the message bodies role, and custom code can fill them from the body.
- A developer can deploy any package of the installation.
- The audit does not protect against a person with access to Postgres.
- Until the cut, the shared token can do everything that exists today, so the first step and the tokens follow each
  other closely.
- A session and a token are bearer credentials. Whoever holds one acts as its user until it ends: a session for at most
  12 hours, a token until its expiry or its revoke. A session is not bound to the UI server that made it, so a stolen one
  works at the node directly for whoever reaches the admin port.
- `RAIL8_ADMIN_TLS=off` puts passwords and credentials on the network as they are. It is for a network that is protected
  another way.
- The emergency key can reset the password of a user who holds the developer or the message bodies role, and then log
  in as that user. Every such path passes a login and is in the audit, and the user finds their password no longer
  works.
- Someone who knows a name can hold off its login from every browser that has no device credential of that user, a new
  browser included, for as long as they keep failing. The user's known browsers, their tokens and an administrator's
  reset are not held off.
- Up to 100,000 names are limited; the logins in flight can pass that cap by their number.
- A request admitted before a revoke, a block or a role taken away runs to its end.
- A flood of logins from browsers without a device credential keeps one place to hash busy on every node. Logins from
  new browsers are then 503 `busy` for as long as it lasts. Known browsers, sessions and tokens already held still work.
- An act of a session or the key holds the second place for its hash: a password change, a personal token's issue, a
  new person or a reset. A password change and a personal token's issue claim the name's row, so someone failing the
  name holds them off with 429.
- An answer lost after the node replaced a device leaves the browser holding the retired one. That happens when the
  browser closes during the login, or when the UI server's 10 s pass while the node still commits. Its next login counts
  on the name's row, as a new browser's does, and gets a new device once it succeeds.
- A copy of a device ends only at the next full login from the browser it was taken from. A login from the command line
  or from another browser does not end it; until then the copy keeps its own row's guesses and its 90 days.
- Whoever holds a copy of a device and keeps failing on it holds off the real browser holding the same device, until
  that user logs in fully another way.
- A flood presenting a valid stolen device takes the second place for its 5 free hashes, and then for one claim per
  attempt.
- Each node judges a device's expiry by its own clock, as it judges a token's, so a node behind accepts a device for as
  long as its clock lags.
- After a first password, and after the user's own password change, the browser is protected only from its next full
  login.
- A browser shared by two users keeps the device of the last one who logged in fully there. The other's device row
  stays, and they come back through their name's row.
- Two tabs of a new browser, with no device yet, logging in at once can make two devices.
- A node older than step 1 that is still running after the cut keeps the old shared token's powers. Its writes of a
  retry, a cancel and a discard fail, but a deploy only logs a WARNING when its audit row fails. The upgrade note says to
  stop such nodes before the cut; nothing in Postgres can stop them.
- No script manages users: a token never changes an account.
- When Postgres is down, every admin request is 503, the emergency key's included.
- A script injected into the UI acts as the logged-in user for as long as the page is open. `HttpOnly` keeps the secret
  from it, not the power.
- Cookies are not kept apart by port. On `localhost` in development, another local server can receive the session's
  cookie; prefer token mode there.
- An administrator can give themselves any role. The audit is the only brake.
- Across browser tabs nothing coordinates the cookie, because the UI server keeps no state. A late 401 of the node
  answered in another tab can clear a cookie set by a newer login in this one. The next request is then 401 and the
  login shows again; nothing is written as another user.
- Local writes are not authorized by the session. `/files` and the git commit are kept only by the loopback listener,
  and the commit also by the cookie's form and `Rail8-Session`. Whoever reaches the loopback port can write the working
  folder directly anyway. A UI server outside development must authorize local writes by the session before it exists.
- Reads are not audited, except a view of a body and every use of the emergency key.
- A new certificate comes with a rolling restart.

## Against an attacker

| What an attacker tries | What stands against it |
| --- | --- |
| an emergency key left set, or a weak one | unset by default, 256 random bits in a fixed form, no deploy, bodies, messages or tokens, every use in the audit before it acts, a WARNING at every use and a notice in the UI |
| the old shared token, reused as the key | the key's form differs, and the node refuses any other |
| a node left on the bridge's powers after the cut | the marker in Postgres, read at every such authentication |
| the open loopback API through a flow that calls `127.0.0.1:8081` | it ends at the cut: every request needs a credential |
| guessing one user's password | 5 free failures, then a wait up to 300 s, claimed before the hash, the same on every node |
| keeping a known user out | the device's own row and a place to hash kept for known browsers; a busy node counts nothing |
| spraying a few common passwords over many names | 12 characters at least, first passwords generated by the node, 2 hashes at once per node |
| telling names apart by time or by answer | a dummy hash and a dummy fingerprint, one 401 text for every refused login, a row for unknown names too |
| filling the audit with refused logins under random names | one row per name per hour, 1,000 an hour for the installation |
| a login that lands after a reset on another node | the account's generation, checked under the user's row as the session is inserted |
| removing the last administrator from two nodes at once | the pool's advisory lock, and only a person counts |
| a stolen session minting a lasting token | issuing a personal token needs a session and the password again, counted on the limit |
| a leaked token granting roles or making users | a token never changes an account; a technical account cannot be an administrator |
| a leaked token in a CI log | a prefix that scanners find, revoke by its id, an expiry of a year at most |
| reading passwords and credentials on the wire | TLS on the admin port; clients refuse plain HTTP off loopback |
| a tab left open writing as the next user | every write names its session; 409 `session-changed` |
| a page of another site calling the UI server | `SameSite=Strict`, `Sec-Fetch-Site: same-origin` and the list of origins, as today |
| a page script reading the session | `HttpOnly`; the proxy refuses `/admin/sessions`; the UI server's login answers no secret |
| a request that skips the check | a route mounts only with an action; the audit's actor exists only after a check |
| an administrator who quietly reads bodies | the role must be assigned, and a self-assignment is marked in the audit |
| a deploy with no record of who made it | its audit row commits with its record, or the deploy rolls back |

## Tests

- One contract suite, `AccessContract`, runs for `PgAccess` (Testcontainers) and `MemoryAccess`, as a component and its
  mock pass one suite. Two `PgAccess` over one Postgres stand for two nodes, each with its own fake clock, set apart.
  Across them:
  - a revoke, a block and a logout are 401 at the other's next request;
  - a role taken away is 403 there;
  - the login limit holds;
  - a login held on a latch while the other resets the password makes no session;
  - two removals of the last administrators leave one;
  - a success does not forgive a failure counted after its claim;
  - a node whose clock is behind does not shorten a wait;
  - the 100,000-name cap holds with logins in flight.
- A fake clock moves time. A session idle for 2 hours and one second is 401 `session-ended`. The waits of the login
  limit, the 7 days of a first password, a device's 90 days and a token's expiry are tested the same way. No test
  sleeps.
- A fake password hash is instant and counts its calls, which proves the dummy hash for unknown names. A variant blocks
  on a latch. With the shared place held, a login without a valid device credential is 503 at once with nothing
  counted, while one with a valid device credential and a reset still hash.
- `Access.fixed(caller)` authenticates every request as one caller, so the existing route tests state who calls.
- A test walks the router: every `/admin` route is mounted with an action, and the `Action` enum matches this
  document's table. A table test calls every route as each role, through a session and through a token, as the
  emergency key, as the shared token and the open loopback API with the marker open and closed, and as a
  first-password session. It expects 403 or not by the table.
- An ArchUnit rule: outside tests, only `Caller.acting` constructs an `Actor`.
- A deploy whose audit row fails to write leaves no record and runs what ran before.
- The UI server:
  - `forward` and the choice of the credential for each mode are pure functions, tested on headers;
  - in each mode, a request without its credential is answered without a call to the node;
  - a write naming another session is 409;
  - the session routes take the node's client as an argument, as the git half's client does.
- The command line and the UI server refuse `http://` off loopback without `RAIL8_ADMIN_TLS=off`.

## Out

- Teams, custom roles, prefixes, a screen of scopes, and filtering results by scope.
- Sign-in through OIDC: step 5, with a design of its own. `source` and `subject` wait for it.
- A second factor, e-mail, lists of common passwords.
- Binding a session to the UI server that made it, and limits per client address: the node sees the UI server's
  address, not the browser's.
- Managing users with a token.
- A cache of credentials or roles.
- Auditing reads other than a body's.
- Loading a new certificate without a restart.

## Steps

Each step is one issue, built and merged on its own. Step 1 and step 2 are groups.

**Step 1, the bridge opens.**
- **1a. Users, sessions and the check at the node.** `class:C`.
  - The users' migration, with the marker and the audit's new columns.
  - `Access`, `Caller` and the `Action` enum, and every route mounted with its action.
  - The login with its limit by name, the first password and its change, and the session with its idle time and its end.
  - The account's generation, the roles changed by difference, and the locks.
  - The message bodies role and the record of every view.
  - `rail8 user add` with `RAIL8_ADMIN_TOKEN`, which makes the first administrator, and `rail8 key new`.
  - The retention of the audit, the ended sessions and the login limit's rows.
  - The audit carries the real name of a session's user. The release is audited.
- **1b. Encryption on the admin port.** `class:C`. The certificate on the admin listener, the rule for clients and the
  rule for a node during the bridge. It is independent of 1a and may come first.
- **1c. The deploy's audit with its record.** `class:B`. The rows of a deploy and an undeploy written in the
  transactions of `PoolLock.save` and `PoolLock.delete`, with the user who deployed and the commit's author.
- **1d. The UI server's session.** `class:C`. Token mode, with `RAIL8_TOKEN` or the shared token, and session mode,
  the cookie, the `/session` routes, `Rail8-Session` and its 409, and the login screen.
- **1e. The Users screen.** `class:B`. An administrator creates users, changes roles, blocks a user, resets a password
  and ends a user's sessions, and sees who granted each role. It is the first step the owner sees.
- **1f. Devices.** `class:C`. The device credential, its migration and its row of the login limit, before the cut.

**Step 2, named tokens and the cut.**
- **2a. Named tokens.** `class:C`. The tokens' migration, personal and technical tokens: issue, list, revoke, the warning
  of their expiry, and the installation's ceiling. A token never changes an account. The shared token still works.
- **2b. Tokens at the command line.** `class:B`. `RAIL8_TOKEN` in the command line, and `rail8 bootstrap`.
- **2e. The UI's screens of tokens.** `class:B`, after 1d and 1e. A person's own tokens, a technical account's on the
  Users screen, the ceiling, and the tokens that expire within 14 days.
- **2c. This repository's callers on named tokens.** `class:A`. CI, the compose stacks, the quickstart and the
  end-to-end scenarios: a key generated for each run, `rail8 bootstrap`, and `RAIL8_TOKEN`. Each stack runs on one host's
  own network, so its nodes and its clients set `RAIL8_ADMIN_TLS=off`.
- **2d. The cut.** `class:C`, merged last, after 2c and 2e, so `main` stays green. The marker closed, the key's form enforced,
  `X-Rail8-Actor` and the open loopback API gone, the default of `via` dropped, the rule of encryption at the node's
  start, and the list of grants through the shared token.

**3. The audit at a message.** `class:B`. The audit view at a message, and `{id, flow}` in both searches.

**4. The Audit screen.** `class:B`. Filtered by user, action and time, and its export of the filtered list as a file.

**5. Sign-in through OIDC** with one provider, roles assigned by hand. `class:C`, labelled `later`; it has a design of its
own.

Deploying a commit from the UI counts on the developer role and on the two names in the audit: the user who deployed
and the commit's author.
