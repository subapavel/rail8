# Deployment from git

A node runs packages as one commit of each package's git repository holds them. A deploy names a
package repository and a commit, and the record of the deployed package names both. No package archive
is uploaded, and none is written to or read from S3.

This document is the agreed design. Today a package reaches a node as a zip uploaded through the admin
API (`PUT /admin/packages/{name}`, `rail8 deploy`), and the node keeps each archive in S3 or a local
folder, under its SHA-256. All of that goes. Undeploy, the redeploy of only the flows that changed, the
rollback of a failed start and the package checks stay.

It is built in seven stages; none reworks what an earlier one built ("What each stage builds", at the
end).

## Rules

- The UI edits files in a working folder and changes a running node only through the node's own deploy
  of a commit, sent at the author's request. The only way into operation is a commit in git.
- Every package has a git repository of its own. The unit of a deploy is one commit of one package
  repository.
- A node pool reads its package repositories under one base, set in the node's configuration. A deploy
  names a repository by its name under that base, never by a URI, so whoever holds the admin token
  cannot point a node at another host.
- A dependency is pinned by its repository and commit, and its files come from that commit.
- Every problem of a deploy is answered before anything stops.
- The bytes that count are git's: a package's content digest is computed over the files its commit
  holds, as committed.
- Git's credentials are sealed in the secret store as `rail8.git`, imported once from the node's
  environment: never in git, in a URI, in a log or in an answer.
- The git cache on a node is not state. Deleting it loses nothing; the node fetches again.

## Package repositories

A pool's package repositories live under one base: a group on GitLab, an organisation on GitHub, a
project in Azure DevOps, or a folder of repositories in a local run. A repository is named by one path
segment under the base (`orders`, `common`): the name `git clone` gives its folder. Its URI is the base
followed by that name.

The package lies at `RAIL8_GIT_PATH` in each repository, the repository's root by default. That folder
holds `package.yaml` and `package.lock`, and every other file of the package, in subfolders as today's
package rules allow. A subfolder may carry a `folder.yaml` with a namespace, as it does today: a namespace of SAP
PO carries over as a folder. A package is known by the name in its manifest, which may differ from its
repository's name. On a pool, a package name belongs to one repository: a deploy of a package of the
same name from another repository is refused until the first is undeployed.

| Setting | What it is |
| --- | --- |
| `RAIL8_GIT_BASE` | The URI the pool's repositories live under, such as `https://gitlab.example.com/integration/`. `https://` in a deployed environment; `file://` also in `dev` and `ci`. A URI with user information (`https://user:token@host/…`) is refused at the node's start, and the refusal does not repeat the URI. Without this setting the node refuses every deploy and lists every recorded package `FAILED`. |
| `RAIL8_GIT_PATH` | The package's folder in each repository, relative, with no `..`; the root by default. |
| `RAIL8_GIT_REF` | A branch or tag name, such as `refs/heads/release`, the same in every repository. Required in a deployed environment that sets a base, optional in `dev` and `ci`. When it is set, every commit a deploy fetches must be reachable from it in its repository. |
| `RAIL8_GIT_USER`, `RAIL8_GIT_TOKEN` | The user name and a read-only access token for every repository under an `https://` base; both or neither, each also as a file (`_FILE`). They are read once, by the import into a secret store that was never written, as the credentials secret `rail8.git`, and never again. `rail8.git` is sent to an `https://` base only; on a `file://` base it is ignored with one WARNING. |
| `RAIL8_GIT_TIMEOUT` | The longest the fetches of one deploy, or of one package's restore, may take together; 60 s by default, 1 s to 10 min. It runs on the node's injected timer. |
| `RAIL8_GIT_MAX_BYTES` | The largest the node's git cache may grow; 1 GiB by default, 1 MiB to 64 GiB. |
| `RAIL8_GIT_CHECK_EVERY` | The least time between the end of one check of the credentials and the start of the next; 15 min by default, 1 min to 24 h. It runs on the node's injected timer. |
| `RAIL8_GIT_CHECK_TIMEOUT` | The longest one check of the credentials may take; 10 s by default, 1 s to 5 min. |

The node keeps a bare repository per package repository as its cache, under `<dataDir>/git`, read and
written with JGit (pure Java, Eclipse Distribution Licence 1.0, a BSD-3-Clause licence); the node image
needs no git binary. A fetch brings the full history of what it fetches, never a shallow copy, and a
source whose history is shallow is refused as such. The cache keeps a reference to every commit a
record or a pin names, so a force-push on the server or a cleanup of the cache never drops one. Its
size is checked before a fetch and while the fetch receives. When it is full, the node first deletes
the cached repositories that no record and no pin names; a fetch that would still pass the limit stops
and names the setting.

A commit is fetched by fetching the pool's ref, or every branch and tag when no ref is set, and then
looked up: a server need not serve a commit by its id. `PackageRepositories` lives in the runtime, and
JGit stays out of `api` and `core`.

A commit stays deployable and restorable only while its repository holds it. The pool's ref is a
protected branch: nobody force-pushes it or deletes it, and a deployed commit is never rewritten out of
its history.

### The token rules

- `RAIL8_GIT_BASE` carries no user information. The user and the token are the secret store's
  `rail8.git`, which the node holds in memory. It is sent only as the `https` credentials of the base's
  host, and never written into the cache's configuration. The base stays in the node's environment, so
  whoever may change `rail8.git` cannot point the node at another host.
- The git cache reads `rail8.git` once for each git operation (a fetch, a check), at its first request
  the host asks credentials for, after the check of that request's scheme, host and port. Each operation
  has a transport of its own, and nothing shared between two operations holds the pair. So an
  overwritten token is sent from the next operation on, without a restart, and a long fetch keeps its
  token to its end. Without `rail8.git` the cache sends no credentials; an unreadable `rail8.git` sends
  none and fails the operation as `the secret store's rail8.git is unreadable`. The variables never take
  over.
- The read-only scope of the token is enforced at the git host.
- A redirect is followed only to the same scheme and host. A redirect anywhere else is refused, and the
  credentials are never sent there.
- Every git failure passes through one formatter. It names the repository by its name, the commit or
  ref, and one reason from a fixed list: not found, not reachable from the pool's ref, history
  incomplete, credentials refused, `rail8.git` unreadable, timed out, cache full, the transport failed.
  A deploy refused for `rail8.git` unreadable carries the code `git-credentials-refused`. It never passes on the
  transport's own message or its causes. Answers, logs, audit rows and the listing of a `FAILED`
  package carry only the formatter's text.
- Every fetch has the finite timeout, and the cache the finite size, above.

### The check of the credentials

A git host tells nobody when a token expires, and a token that expired was found only when a deploy
failed, or when a node that restarted with an empty git cache came up without its packages. So a node
checks its credentials on its own:

- At its start, and then `RAIL8_GIT_CHECK_EVERY` after each check ended, on its injected timer, the node
  lists the refs of one repository it runs: the first package by name that it lists from git. It
  sends the pool's credentials, fetches no object, writes nothing into the cache and holds none of its
  locks, within `RAIL8_GIT_CHECK_TIMEOUT`. Two checks never run at once. A node that runs no package
  from git asks nothing.
- The outcome is one of three:
  - `WORKING`: the host listed the refs, whether or not they hold the pool's ref.
  - `REFUSED`: the credentials were refused, or the repository was not found, as a host such as
    GitHub answers for a private repository the token no longer reaches.
  - `UNREACHABLE`: the check timed out, or the transport failed.

  The reason is the formatter's text, with `RAIL8_GIT_CHECK_TIMEOUT` named for a timeout, so no token
  and no transport message reaches an answer or a log. A change of state is logged once: a WARNING for
  `REFUSED` and `UNREACHABLE`, INFO when the credentials work again.
- `/health` shows `git: {credentials, checkedAt}`, outside its checks, never the repository or the
  reason. `GET /admin/git` (`git.read`: developer, operator, administrator) answers
  `{credentials, repository, reason, checkedAt}`, every field null before the first check. Refused
  credentials make the node no less healthy for traffic: messages keep flowing, and only a deploy and
  a restore with a cold cache are at risk.
- The UI shows a warning on Home and on the Packages screen while the credentials are `REFUSED`. It
  says what stops working: a deploy, and a node that restarts with an empty git cache.
- One repository is checked. A token scoped per repository that lost another one is seen at a deploy
  of that one.
- A change of `rail8.git` (an overwrite, a create or a delete) brings one extra check on every node, at
  the later of now and 1 minute after the node's last check ended. Changes before that check starts are
  coalesced into it, and a check that runs is not interrupted. The regular checks go on
  `RAIL8_GIT_CHECK_EVERY` after the end of the last one. So overwrites in a loop cannot make the nodes
  hammer the git host.

### Entering the token

The secret administrator enters the pool's git credentials in the secret store as the credentials
secret `rail8.git`, through `/admin/secrets`, here with the token of an account that holds the secret
administrator's role (`secret.write`):

```sh
auth="Authorization: Bearer $RAIL8_TOKEN"
# the first time
curl -X POST localhost:8081/admin/secrets -H "$auth" -H 'Content-Type: application/json' \
  -d '{"name": "rail8.git", "kind": "credentials", "user": "rail8-reader", "password": "<the token>"}'
# a new token: every node fetches with it from its next git operation on, without a restart
curl -X POST localhost:8081/admin/secrets/rail8.git -H "$auth" -H 'Content-Type: application/json' \
  -d '{"kind": "credentials", "user": "rail8-reader", "password": "<the new token>"}'
```

A token typed on a command line stays in the shell's history: give it from a file
(`-d @rail8-git.json`) where that matters.

- A blank user or token is refused as 422 `invalid`, naming the field and never the value.
- `RAIL8_GIT_USER` and `RAIL8_GIT_TOKEN` are read only once: by the first node that joins a secret store
  that was never written, which imports them as `rail8.git`. Set later, they are not read, and the node
  logs one WARNING naming them, never their values.
- `GET /admin/secrets` lists `rail8.git` with its platform use when it is set. When it is not set, it is
  among the names that a use names and the store does not hold.
- Its delete needs the confirmation (`DELETE /admin/secrets/rail8.git?confirm=rail8.git`). After it the
  node sends no credentials; the variables do not take over, and the token is entered again in the
  store.

## What a commit holds

- The package's folder holds regular files only: a symbolic link or a submodule is refused. A package
  holds at most 10 000 files and at most `RAIL8_MAX_PACKAGE_BYTES` in all, checked from the commit's
  tree before anything is written.
- The content digest is computed as today, over the files the commit holds under the package's folder,
  with the bytes git stores. Untracked files and `.git` never count.
- A repository of packages carries a `.gitattributes` holding `* -text`, so git never changes line
  endings and a working folder holds the committed bytes. The rule is not checked: what counts is that
  the working folder holds the committed bytes, which Rail8's own repository gets from
  `* text=auto eol=lf`.
- `.git` at the root of a package's folder is not part of the package. `Package.load`,
  `Package.content` and the copies `rail8 test` makes all skip it, so a package at the root of its
  clone validates and tests as one in any folder does.

## Dependencies

`package.yaml` names each dependency at an exact version, as today. `package.lock` pins each direct
dependency by its name, its version, its content digest, its repository and its commit. The lock's
schema stays `schemaVersion: 1`, with `repository` and `commit` required in each entry; a lock without
them is refused, naming `rail8 lock`.

- `rail8 lock` finds a dependency among the working folder's package folders by its manifest's name, as
  today. It takes the repository from the name of the dependency's git working tree, the commit from its
  `HEAD`, and the content from that commit. A dependency whose files differ from its `HEAD` is refused:
  commit it first. The command line reads no pool settings, so a dependency must lie at the root of
  its working tree; one that does not is refused.
- A deploy resolves each dependency of the closure from the repository and commit its lock pins, never
  from a version found by name. A directory the node keeps with that content is used as it is;
  otherwise the commit is fetched, under the same rule of reachability as the package's own, written,
  and its content checked against the pin.
- A dependency's files come from its pinned commit, whatever version of it the pool runs, so packages
  deploy in any order. A dependency need not be deployed for its files to be used; it must be deployed
  only for calls to its flows, which go to the flows that run, as today.
- `rail8 validate` and `rail8 test` read a dependency's files from its pinned commit in its working
  tree's repository, as the node will, and the package itself from its working folder.
- The command line reads git only through the same seam as the node ("The git seam"), with the
  folder of candidate packages as a `file://` base and a cache in a temporary folder it deletes. The
  check that a dependency's files differ from its `HEAD` lives in the command line: it compares the
  content of the folder, without `.git`, with the content of `HEAD`'s folder written by the seam.

## A deploy

`POST /admin/deploy` with the body `{"repository": "<name>", "commit": "<40 hex>", "expect": "<40 hex>" | "none" | "any"}`:
`expect` is the state the deploy expects to replace ("The expected state of a deploy"). In `dev` and `ci`
the commit may also be a ref (`HEAD`, `main`, `refs/tags/v1`), resolved in the repository at the fetch.
With `?check=true` the node answers what the deploy would do and every problem; nothing that runs
changes and no record is written, though the fetch fills the cache and the folders it wrote are deleted.
The actor is the request's `X-Rail8-Actor` header, as for every audited act.

`rail8 deploy <repository> <commit|ref> --expect <commit|none|any>` sends it and prints the answer;
`rail8 deploy <repository> <commit|ref> --check` checks.

The order:

1. **Refusals before any fetch.** A bad body, a deploy without `expect`, a bad repository name or commit
   id, or a ref in a deployed environment (400); a closing node (503); another deploy or undeploy running
   on any node of the pool (409, "Several nodes" below).
2. **Fetch.** The commit is fetched within the timeout and the cache's size. When `RAIL8_GIT_REF` is
   set, the ref is fetched too, its tip resolved once, and the commit proved an ancestor of that tip
   (422 otherwise; history the cache lacks is refused as incomplete, never as unapproved).
3. **Write.** The package's folder is written under `<dataDir>/.incoming` first. Its manifest's name
   is checked against the package-name pattern before it names a directory; then the folder moves to
   `<dataDir>/packages/<name>/<content hex>/`, unless that directory is one the node keeps and its
   files are intact. A package of the same name recorded from another repository is refused (409). Then
   the deploy's `expect` is compared with that package's record, and the deploy refused (412) when the
   pool holds another state ("The expected state of a deploy").
4. **Dependencies.** Every pin of the closure is resolved, fetched and written as "Dependencies" says.
5. **Check.** The package is checked whole with today's checks: schemas, components, endpoints,
   dependencies, listeners and calls between flows. Every problem is one line of one answer (422), and nothing has stopped. A check ends here.
6. **Apply.** Today's swap: the changed and removed flows stop, the added and changed ones start, the
   record is written (the commit point), and a failed start rolls back.
7. **Audit.** One audit row: the actor, `deploy`, the repository, the commit, the commit's author as
   git holds it (`Name <email>`), the `expect` the deploy sent, `any` included, and the outcome; an applied deploy adds
   `replaces`, what it replaced. A deploy that writes its record writes the row in
   the record's transaction: when the row cannot be written, neither is the record, and the deploy
   rolls back as after any other failed save. A refused deploy writes its row after the refusal.

The answer is today's deploy answer, with the repository and the commit in place of the revision and
the archive, and `replaces`: the commit of the record the deploy replaced, or `none`. The check's answer
names `replaces` too.

A listener or a node-local address that moves from one package to another takes two deploys: first
the one that removes it, then the one that adds it.

**Undeploy** stays `DELETE /admin/packages/{name}`. Its audit row, `undeploy` with the subject
`package/<name>` and the package's version, repository and commit, is written in the transaction that
deletes the record: when the row cannot be written, nothing is undeployed. A package whose at-least-once flows hold items is
undeployed all the same: its items run on the flows' drains ("Retiring" below). Today's refusal of a package another record pins goes: a pin brings
its own files from its own commit, so a dependent runs on after its dependency is undeployed. **Rollback** is a
deploy of an earlier commit of the same repository.

## The record

One row per package in `deployments`: the name, the version, the repository's name, the commit, the
content digest, `deployed_at`, and the pins of its dependencies (name, version, content digest,
repository, commit). A running flow names the commit it was built from: a flow a redeploy kept,
because its files did not change, keeps the commit it was built from while the record names the newer
one.

The migration drops the archive's SHA-256 and the free-form revision, and deletes the rows that named
an archive: those exist only in development stacks, which deploy again from git.

## Restore at a node's start

At its start, before its admin port opens, a node installs each record:

- The record's commit, and every pin's, is read from the cache first, so a warm cache restores with
  git unreachable. A commit the cache lacks is fetched. Reachability is not checked at a restore: the
  commit was approved when it was deployed, and a ref that moved since does not stop a node from
  starting.
- The package's folder is written and its content checked against the record; each pin's folder is
  written from the pin's repository and commit and checked against the pin. The package is installed
  as today.
- A package that cannot be fetched, or whose content differs from its record (a node configured with
  another base or path), is listed `FAILED` with the reason; the node starts, and the others run. Once a
  fetch fails because the base's host cannot be reached or timed out, the restore tries no further
  fetch: every package still needing one is listed `FAILED` at once, so the admin port is not held
  back for a timeout per record.
  Until following is built, the next start is the next try.

## Several nodes

- **One deploy at a time in a pool.** A deploy or an undeploy holds a Postgres advisory lock for its
  whole length, on a connection of its own that no other work uses, taken without waiting; a second one
  on any node of the pool is 409. A node that dies frees the lock with its connection.
- **No stale plan.** At its end the deploy raises the pool's generation, a number in Postgres. A node
  that takes the lock while the generation is ahead of the one it last reconciled reconciles first,
  then plans.
- **Following.** Every node reads the generation on its injected timer and, when it moved, reconciles
  what it runs to the records: it installs a record whose content differs from what it runs, as a
  restore does, and undeploys a package that has no record. It writes no record, only its own rows of
  what it runs ("What each node runs"). At every tick it
  tries again a package it lists `FAILED` because its commit could not be fetched. A node that cannot
  reconcile is `DEGRADED`, naming the packages, and tries again at the next tick.
- **Retiring.** A removed flow retires only when no node holds its work. A flow the pool no longer runs
  at-least-once (an undeploy, or a redeploy that removes it or changes its delivery) is marked in the
  table `retiring`, in the transaction that writes or deletes the record, with a copy of the record
  it ran under. The work queue admits a message for a flow only while a record runs it at-least-once,
  a mark retires it, or the node runs it before its record is written; a node that still runs the
  flow until its next tick therefore writes into the drain, and a write after the mark ended is
  refused with nothing kept. Each node, once it stops the flow, runs its drain: the flow built again
  from its directory, with no consumer, running what the work queue holds of it. Each tick ends the
  mark of a flow with no work row left, under that flow's advisory lock, which every admitted write
  holds until it commits; a drain stops at the tick after that once its queue holds nothing of the
  flow, and the package's directory goes then. A drain takes as long as its items do. A node that dies
  while a flow drains leaves its items in the work queue; a restarted node builds no drain yet, so they
  wait for one.

## What each node runs

The records say what the pool runs. Each node also writes, in Postgres, what it runs itself, so that any node answers for the
whole pool, a follower that still runs an older commit included.

- **The rows.** `node_packages` holds one row per package a node lists. A row names the node, the epoch of the incarnation
  that wrote it, the package's name, version, repository and commit, `deployed_at`, and the state, `RUNNING` or `FAILED`. Its
  `behind` holds the lines of the node's last refused install of the package's record, and is empty when there is none. A
  `FAILED` package's row names its record's commit, as `GET /admin/packages` does.
- **When a node writes them.** After its restore, and after every deploy, undeploy and tick, whenever what it lists differs
  from the rows it last wrote, or that write failed. So it writes when it installs a package, when it undeploys one, and when
  a tick fails to install one. A node writes only its own rows.
- **How.** The write runs once the node's deploy lock and the pool's lock are both free. It holds a lock of its own, which
  only these writes take, and takes the node's listing at the moment it holds it, so a later write always carries a later
  listing. It is one transaction:
  1. The node's row in `nodes` is locked `FOR KEY SHARE`. It must still carry the node's own epoch, with a lease that was not
     released; otherwise nothing is written. That lock waits for a join, so an old incarnation never writes after a newer
     one joined. It never holds back a renew or a release.
  2. The node's rows, of every epoch, are replaced whole.
  3. The rows of every node whose epoch moved on or whose lease was released are deleted.

  The write takes no pool lock and raises no generation.
- **A write that fails.** A deploy waits for its own write, within the stop deadline, before it answers; nothing else waits
  for one. A write that fails, or does not answer in time, is a WARNING once per reason, and the next tick writes again. It
  never changes the answer of a deploy: the record is the commit point, and it is written by then. A write that did not
  answer in time may still run in Postgres; the node starts no other until it ended, so it never lands after a later one.
- **A restart, and a lease that ended.** A restart joins as a new epoch before it restores, so the rows of its previous
  incarnation drop out of the answer at once. Until its restore writes, the node is listed with no packages: it has not
  started. A node whose lease ended drops out of the answer. Its rows stay, unread, until it joins again, or until a peer
  releases its lease and the next write of any node deletes them.
- **Beside the records.** A record is what the pool runs: one per package, written by a deploy or an undeploy under the
  pool's lock, the commit point. A row is what one node runs, written by that node alone. A row differs from its record
  while its node has not followed yet, at most one tick, and while the node is behind; `behind` then says why.
- **Not state.** Nothing reads the rows to decide anything:
  - a restore and a tick read the records;
  - the work queue admits by the records, the marks and the node's own memory;
  - a drain ends by the marks and the work rows;
  - the expected state of a deploy is compared with the records.

  A lost row, or a lost table, loses no message. The answer is only blind until each node writes again.

`GET /admin/pool/packages` answers the pool's records and what each node runs:

```json
{"records": [{"name": "orders", "version": "1.4.0", "repository": "orders", "commit": "<40 hex>"}],
 "nodes": [{"node": "node-a", "packages": [{"name": "orders", "version": "1.4.0", "repository": "orders",
   "commit": "<40 hex>", "deployedAt": "…", "state": "RUNNING", "behind": []}]}]}
```

- `nodes` lists every node whose lease holds, by the database clock that measures leases, each with the rows of its current
  epoch. A node that joined but has not written yet has no packages.
- The records and the rows are read in one read-only transaction.
- Records and packages are sorted by name, and nodes by id, as `String.compareTo` sorts.
- The answer is not paged. It holds at most 10 000 rows and records together; a pool that holds more is answered 500, naming
  the limit, never a cut answer.
- Every node gives the same answer.

A node's row whose commit differs from its package's record, a row with no record, and a record a node has no row for each
show a node that has not followed yet, or one that is behind. Once a tick failed, `behind` says why. Why a node cannot follow
at all, its records unreadable for one, is a fact about the node, and its `/health` says it.

## The expected state of a deploy

Every deploy names the state it expects to replace, from the UI, the command line and CI alike, so that one author's deploy
never replaces another's, or CI's, unseen. The node refuses a deploy, changing nothing, when the pool holds another state.

- **The body.** `POST /admin/deploy` takes `expect`, a key every deploy must give:
  - the commit the pool's record of the package names, as 40 lower-case hex digits;
  - `none`, when the pool records no such package: the first deploy of a package, to an empty installation or not;
  - `any`, the blind overwrite: the deploy replaces whatever the pool holds, and its audit row records `any`.

  A deploy without `expect` is 400, and so is any other value, `null` and upper case included, and any other key, before
  anything is fetched. A check (`?check=true`) may leave `expect` out, and then compares nothing.
- **What names it.** The commit of the package's record, not the pool's generation:
  - The generation moves with every deploy and undeploy of any package. An unrelated deploy between an author's check and
    their confirmation would refuse it, and the refusal could not say what changed.
  - The commit is what the author saw, and a refusal names the commit the pool holds now.
  - A pool that went from a commit to another and back holds what the author confirmed a change against, and the deploy goes
    ahead.
  - Whatever else the check found, from listeners to calls between flows, the deploy checks again under the pool's lock.
- **`none` and `any`, not `null` and a missing key.** A client that drops nulls, or confuses a null with a missing key,
  cannot turn "expects no record" into "expects nothing", and nothing overwrites blind unless its caller wrote `any`.
- **The commit, or a ref.** The deploy's commit may still be a ref in `dev` and `ci`, as before. A client that must deploy
  exactly the commit it checked sends the 40 hex digits the check answered, as the UI does; a ref resolved again at the
  deploy could have moved since.
- **Where it is compared.** In step 3 of the order: once the commit's `package.yaml` has named the package, and after the
  refusal of a package recorded from another repository. It is compared with that package's record, as the pool's lock read
  it. Only a holder of the lock writes records, so that is still the pool's record at the commit point. The package is the
  one the commit's manifest names: a commit that renames a package from `a` to `b` replaces nothing of `a`, and expects
  `none` for `b`.
- **The refusal.** A different commit, a record where `none` was expected, or none where a commit was, is 412
  `{"error": "…", "replaces": "<40 hex>" | "none"}`:
  - `replaces` is what the pool holds now;
  - the error names that commit, and says so when it is the commit the deploy names, as after a retry of a deploy whose
    answer was lost;
  - nothing has stopped, no record is written, the generation does not move, and the folder written is deleted;
  - the refusal is audited, as every refusal under the pool's lock is.
- **What tells the client.** The answers of the check and of the deploy name `replaces`: the commit of the record the deploy
  replaces, or `none`. It is the pool's record, never the listing of the node that answers. On a node that has not followed
  yet, `GET /admin/packages` can name another commit until its next tick. The check reads it from the records once the
  package is named, without the pool's lock.
  - A client sends the check's `replaces` back unchanged as `expect`.
  - A check that names `expect` compares it with that read, and answers the same 412. Only the deploy's comparison is the
    guarantee.
- **The command line.** `rail8 deploy <repository> <commit|ref> --expect <commit|none|any>` sends what its caller wrote.
  Without `--expect` a deploy is a usage error: it exits 2 and sends nothing. The command line fills in no default, and
  never reads the state for its caller. `--check` needs no `--expect`, and its printed answer names `replaces`, so an
  author at a terminal checks, then deploys with `--expect` set to that `replaces`. A 412 exits 1, as any answer that is
  not 2xx.
- **CI and other pipelines.** A pipeline reads the state first and sends it with the deploy:

  ```sh
  replaces=$(rail8 deploy orders "$commit" --check | jq -r .replaces)
  rail8 deploy orders "$commit" --expect "$replaces"
  ```

  A deploy that lands between the two is refused, and the pipeline fails. A pipeline that starts its own fresh installation
  expects `none`, as this repository's CI and `bin/run-adapter` do. The stack of `deploy/compose` keeps its database
  between starts, so its one-shot deploy reads the state first.
- **Undeploy** takes no expected state yet. Nothing undeploys for an author: the command line has no undeploy, and the UI
  leaves it out. The expected state comes with the first client that does.

## Environments

Environments are not branches. The same commit of a package repository moves from dev to test to prod,
each environment a pool of its own with its own Postgres and its own `RAIL8_GIT_REF`: for example
`main` for test, and for prod a protected `release` branch that a reviewed pull request moves forward
to the tested commit. Protecting that branch in every repository is the git host's work, set once for
the group; [the guide](PROTECTING-THE-RELEASE-BRANCH.md) gives the steps for GitLab, GitHub and Azure DevOps. A package's dependencies reach prod the same way: their pinned commits must be on their own
release branches first. Rail8 checks only that each commit is reachable from the ref.

What differs between environments is already in the files: an endpoint's `environments.<env>.config`
overlays and `secret://` names, read from each node's own environment.

Planned (`docs/ENVIRONMENT-CONFIGURATION.md`): a connection's values and a system's instance name are
entered in each environment, not in files. A deploy checks every connection its flows reach against
the environment, and its record names the generation of the environment's configuration it was
checked against. A deploy still states only the commit it expects; it states nothing of the
configuration, which is activated per object.

## The local run and the quickstart

- The working folder holds a clone of each package repository, named as the repository. The UI saves
  files into it; the developer commits, then runs `rail8 deploy <repository> HEAD --expect <commit>`, the commit
  deployed before, or `none` for the first deploy.
- The quickstart makes its working folder hold the demo as a git repository, `demo`, with the
  `.gitattributes` above, and commits it. Its node mounts the working folder read-only and reads it as
  the base `file:///workspace/`; the cache is in the node's data volume. Its steps commit before every
  deploy.
- A repository in the working folder is a full clone with its `.git` folder; a worktree or a copy
  whose `.git` points outside the mount is not supported.
- `rail8 new-adapter` writes the adapter as a git repository, with the `.gitattributes` above and a
  first commit. `bin/run-adapter` reads the adapter's own repository, with `RAIL8_GIT_PATH` set to its
  package folder, and deploys its `HEAD`. It prints one line when that folder differs from `HEAD`: uncommitted
  changes are not deployed.
- `deploy/compose` and CI mount the Rail8 repository as the repository `rail8` under a `file://` base,
  with `RAIL8_GIT_PATH` set to `examples/demo`. The checkout they mount has the full history: CI's
  checkout sets `fetch-depth: 0`, and a shallow clone or a worktree of Rail8 does not serve.

## `rail8 test` and `rail8 validate`

Both keep working on folders, in process. `Deployments` deploys a folder: the git deploy above calls it
with the package's git origin, and `rail8 test` calls it with none, which is allowed only in `dev` and
`ci` and writes no record. `rail8 test` deploys a copy of each folder that it makes, owns and deletes,
so an edit in the working folder during a run changes nothing in it. No admin route and no command
reaches a deploy without a git origin.

## The git seam

The node and the command line read git only through one interface; a fake of it and the JGit
implementation pass the same contract suite. Blocking: called on a virtual thread.

```java
/** The package repositories of the node pool, read through the node's cache. */
public interface PackageRepositories {
    /** The base the repositories live under, without credentials, and the package's folder in each. */
    URI base();
    String path();

    /** Fetches and answers a commit a deploy names, for the package or a pin: a 40-hex id, or in dev
     *  and ci a ref; proved reachable from the pool's ref when one is set. */
    Commit forDeploy(String repository, String commitOrRef) throws GitFailure;

    /** Answers a commit a record or a pin names, from the cache, fetching only when the cache lacks it;
     *  no reachability check. */
    Commit recorded(String repository, String commit) throws GitFailure;
}

/** The package's folder in one commit of one repository. */
public interface Commit {
    String repository();
    String id();

    /** Writes the package folder's files, as committed, into an empty directory; refuses a folder with
     *  no package.yaml, a symbolic link, a submodule and a tree over the limits. */
    void write(Path dir) throws GitFailure;
}
```

`GitFailure` carries one reason of the formatter's list, and its message is the formatter's.

## What goes away

- `PUT /admin/packages/{name}`; the deploy is `POST /admin/deploy`.
- `ArchiveStore`, `S3ArchiveStore`, `DirArchiveStore`, `PackageArchive` and `ArchiveName`.
- The settings `RAIL8_ARCHIVE_*` and `RAIL8_S3_*`, and the AWS SDK in the runtime, once the bench
  deploys from git too: nothing else uses them. The body tier planned for S3 brings what it needs.
- `deploy/quickstart/deploy.sh`, and the zip code of `rail8 deploy`.

## Limits

| Limit | Value |
| --- | --- |
| The fetches of one deploy or one package's restore | `RAIL8_GIT_TIMEOUT` together, 60 s by default; `rail8 deploy` waits longer than that |
| The git cache | `RAIL8_GIT_MAX_BYTES`, 1 GiB by default |
| The check of the credentials | one at a time, `RAIL8_GIT_CHECK_EVERY` apart (15 min by default), each within `RAIL8_GIT_CHECK_TIMEOUT` (10 s) |
| One package | `RAIL8_MAX_PACKAGE_BYTES` in all, 10 000 files |
| One deploy | the package and at most 100 packages in its dependency closure |
| A repository's name | one segment of at most 100 letters, digits, `.`, `_` and `-`, not `.` or `..` |
| A deploy's body | 4 KiB |
| Why a node's package is behind (`behind`) | 20 lines of at most 500 characters each |
| The answer of `GET /admin/pool/packages` | 10 000 rows and records together |

## Tests

- The contract suite of `PackageRepositories`, run on the fake and on JGit over `file://` repositories
  the test builds: the package folder found at the path, a folder with no `package.yaml`, symbolic links
  and submodules refused, the limits, reachability from the ref, a commit on another branch refused, a
  shallow source refused as incomplete, a ref refused in a deployed environment, a fetch timed out on a
  fake clock, a full cache freed of unnamed repositories, a recorded commit read
  from the cache with the source gone and fetched when the cache lacks it, and the token rules (user
  information refused, the formatter never showing credentials or the transport's message, an
  authentication failure, a redirect to another host refused).
- The check of the credentials, on a fake clock: the three outcomes with the formatter's text over a fake
  git host, no two checks closer than the interval, nothing asked while the node runs no package from
  git, a change of state logged once; over a local HTTPS git host, a token that works writing nothing
  into the cache, a refused token and a missing repository, a host gone and the check's own timeout;
  `/health` and `GET /admin/git` showing each outcome; a node whose repository vanished answering
  `REFUSED` while it stays up and keeps serving messages. A browser test shows the UI's warning.
- `rail8 lock`: a dependency pinned by repository and commit; a dependency with uncommitted changes
  refused; a dependency not at the root of its working tree refused; a lock without repository or
  commit refused at a deploy. `rail8 validate` and `rail8 test` on a package at the root of its clone.
- Deploys over JGit `file://` repositories, with a fake clock: a first deploy; a redeploy that swaps only
  the changed flows; a dependency fetched from its pinned commit while another version of it runs; a
  rollback by an earlier commit; a package name from a second repository refused; a manifest name that
  is not a package name refused before any directory is made; an undeploy, after which a restart does
  not install the package; an undeploy refused while its queue holds items; a dependent running on after
  its dependency is undeployed; `?check=true` writing no record.
- Restore: from a warm cache with the repositories gone; with a commit fetched; a pin restored from its
  own commit while its package runs a newer one; with the server unreachable (`FAILED`, the later
  packages without a fetch each); with another base (`FAILED`).
- Several nodes over one Postgres, on a fake clock: a second deploy refused while one runs; a node
  behind the generation reconciling before it plans; the other node installing and undeploying at its
  next tick; a `FAILED` package restored at a later tick once its repository answers; a removed flow
  that takes no new message on any node and drains before it stops.
- What each node runs, on two nodes and a fake clock. The tests run on an in-memory membership whose leases end on the fake
  clock, and on Postgres with a lease ended by setting `lease_until` in the past:
  - a follower behind after a deploy, then caught up at its tick;
  - a tick that cannot install a record writing why;
  - a node whose lease ended dropping out of the answer;
  - a restarted node listed with no packages until its restore writes;
  - an old incarnation's write changing nothing;
  - the table deleted during a run, and the ticks and the deploys going on;
  - a write of the rows that hangs, and the deploy answering 200 with its record written; an undeploy meanwhile writing
    nothing, and the next tick, once the hung write ended, writing what the node runs.
- The expected state:
  - two deploys that expect the same commit: the first applied, the second 412, with nothing changed;
  - `none` applied on a first deploy, then 412;
  - an undeploy between a check and its deploy, then 412;
  - `any` applied over another commit, its audit row recording `any`;
  - a deploy without `expect`, and `null`, upper case and another key, each 400 before any fetch;
  - the check answering `replaces` with no `expect`, and 412 with a stale `expect`;
  - a retried deploy whose first try landed, 412 saying so;
  - `rail8 deploy --expect`, and a deploy without it a usage error that sends nothing.

## The owner's choices

The owner chose:

- A git repository per package, rather than one repository for many packages.
- The same commit promoted from pool to pool, each gated by its ref, rather than a branch per
  environment.
- A deploy of one commit of one package repository.
- A commit even for the local run.
- A command that names the commit; a node that follows its ref by itself may come later as a timer
  calling the same deploy.
- The token in the secret store (`docs/SECRET-STORE.md`) as the credentials secret `rail8.git`, imported
  once from the variables, which no node reads after. Done.
- Every deploy names the state it expects, from the UI, the command line and CI alike, and is refused when the pool holds
  another: on a shared server one author's deploy must never replace another's, or CI's, unseen. Overwriting blind takes a
  switch the caller writes out, and the audit row records it. A pipeline reads the state first and sends it.

These were decided on the owner's behalf, and stand until the owner says otherwise:

- One base per pool, with repositories named by one segment under it. A name is stored as text, so
  names of several segments (a group within a group) or several bases may come later with no
  migration.
- One read-only token for the base, rather than one per repository.
- A dependency's files come from the commit its lock pins, whatever version of it runs.
- `RAIL8_GIT_REF` names the same branch in every repository of the pool.
- Until sign-in comes, whoever holds the admin token may deploy any package of the base; who may change
  a package and its release branch is the git host's to decide.
- The expected state of a deploy is the commit of the package's record, not the pool's generation, and `none` names no
  record.
- The blind switch is `any`, the value of `expect` in the body and of `--expect` on the command line; the first deploy of
  a package expects `none`.
- An undeploy takes no expected state until a client undeploys for an author.

## What each stage builds

1. **The git seam.** `PackageRepositories` on JGit, its fake and its contract suite, the `RAIL8_GIT_*`
   settings, the token rules and the limits, usable by the node and by the command line. Nothing calls
   it yet.
2. **A deploy from a folder.** `Deployments` deploys a folder instead of a zip; the upload unpacks into
   a folder first, keeping its streamed checks of sizes and paths, and `rail8 test` deploys copies of
   folders in process. Nothing changes for a caller.
3. **A lock that pins commits.** `package.lock` pins each dependency's repository and commit;
   `rail8 lock` fills them in, and `rail8 validate` and `rail8 test` read dependencies from their pinned
   commits; `.git` at a package's root is skipped. The node reads the new lock and goes on resolving by
   content until the next stage.
4. **Deploy and restore from git.** The record and its migration, `POST /admin/deploy` with its order,
   dependencies from their pins, the restore from git, directories keyed by content digest, and
   `PUT /admin/packages/{name}` removed. Every caller in the repository moves in the same change, so
   that each keeps working: `rail8 deploy` sends a repository and a commit; `rail8 new-adapter` writes a
   repository; CI, both compose files, `bin/run-adapter`, the quickstart and the bench (its harness and
   its restore test) deploy from git, as "The local run and the quickstart" says. ARCHITECTURE
   ("Deployment", "The command line", the admin API), QUICKSTART, ADAPTERS and the compose README
   describe it. A deployed environment still refuses deploys.
5. **The archive goes.** What the previous stage left unused is deleted: the archive store and its
   settings (the bench's included), the AWS SDK in the runtime and the bench,
   `deploy/quickstart/deploy.sh` and the zip code of `rail8 deploy`.
6. **Several nodes.** Designed in full first, then built: the pool's lock, the catching up before a
   plan, the generation, following and retiring; a deployed environment then takes deploys of commits
   reachable from its ref, and undeploys. ARCHITECTURE parts 4 and 6 describe it.
7. **What each node runs, and the expected state.** `node_packages` and its write, `GET /admin/pool/packages`, `expect`
   on every deploy and `replaces` on the deploy and its check, and `rail8 deploy --expect`. Every caller in the repository
   moves in the same change: CI, both compose files, `bin/run-adapter`, the quickstart and the bench. ARCHITECTURE parts 3
   and 10 describe them, and the UI's Deploy in `docs/PACKAGES-AND-GIT.md` sends the check's `replaces` as `expect`.
