# Packages and git in the UI

The UI is where an author manages the packages of the working folder through git. It covers:

- the packages and their artifacts;
- what changed since the last commit;
- a commit;
- the commit a node runs, and a deploy of a commit;
- the pins of the dependencies;
- push and pull.

In the terms of SAP Integration Suite, these are the package view with its artifacts, "save as version" and "deploy",
with git underneath instead of the tenant's own store.

This document is the agreed design. `docs/DEPLOY-FROM-GIT.md` is the node's half, which deploys a commit of a package
repository. This document is the UI's half. It is built in the steps listed at the end, and no step reworks what an
earlier one built.

## Rules

- The UI edits files in a working folder on the author's machine and never changes a running node by itself. A deploy
  is the node's own deploy of a commit, `POST /admin/deploy`, sent on the author's request.
- Each package is a clone of its own repository: a folder directly in the working folder, named as its repository. The
  name of the folder is what a deploy and a pin name.
- The UI runs the author's own git binary. A commit from the UI is therefore the commit a terminal would make, with the
  same identity, hooks, signing and credentials.
- The UI never loses the author's work. It runs a short, fixed list of git commands. None of them drops a change, moves
  a branch backwards or rewrites history. Everything else is left to a terminal and to the git host.
- Every write states what the author saw. When that no longer holds, the write is refused and changes nothing. What is
  committed is what the author reviewed, and what is deployed is the commit named, as 40 hex digits.
- A flow's text is never shown. Each kind of artifact is compared by what it means:
  - a flow as a picture of its steps;
  - a mapping by its target fields;
  - an endpoint by its settings.
- The git half runs only in the development server, on the author's machine. Editing on a shared server, with writes
  to git, waits for sign-in.

## What the author sees

### The Packages screen

The Packages screen is the first screen of the Design group. It is a ProTable with one row per package of the working
folder, sorted by repository. Above the table it names the node it reads, the host of `RAIL8_ADMIN_URL`. Each row
shows:

- the repository;
- the package's name and version;
- the branch;
- the last commit: its subject, short id and age;
- ahead and behind its upstream, as of the last fetch;
- the number of changed files;
- the deployed state on that node: Deployed, Deployed with changes since, Behind *n*, Ahead *n*, Other commit, Failed,
  or Not running on *node*.

**New package** sits above the table.

Some folders are not packages:

- one with no clone of its own;
- a worktree;
- one without `package.yaml` at the root of its clone.

These are listed below the table, each with the reason. Their files stay in the lists of flows, endpoints and
mappings.

### A package's tab

A row opens the package in a tab of its own, `package:<repository>`.

The header shows the package's name and version, the branch, the last commit, ahead and behind, and the deployed state.
Its actions are Commit…, Deploy…, Fetch, Pull and Push. Each action is disabled, with the reason, when its route
would refuse it.

The tab has four tabs of its own:

- **Changes.** Every file of the package that differs from the last commit: added, modified, deleted or renamed. Each
  file shows its kind and its readable change ("Readable changes"). Commit… starts here.
- **Artifacts.** The package's flows, endpoints, mappings and tests, and the other files they use.
  - They are listed by kind, each with its id, whether it changed, and, for a file, the artifacts that use it.
  - A row opens the artifact in the tab its own screen opens.
- **History.** The commits of the branch, newest first, 50 to a page. The commit the node runs is marked.
  - Each commit has **Deploy this commit…**.
  - Choosing a commit shows the files it changed against its first parent, each with its readable change.
- **Dependencies.** Each dependency's name, version, pin and state ("Dependencies"), with **Lock**.

The tab reads its package again when it is shown, when the window gets the focus, and from the answer of every write.

### The lists of flows, endpoints and mappings

The Flows, Endpoints and Mappings screens stay as they are: lists across the whole working folder, so that an artifact
is found fast without knowing its package. Each list gains a Package column and a filter on it. The package is the
first segment of the file's path, and choosing it opens the package's tab.

## Which folders are packages

A package is a folder directly in the real `RAIL8_WORKSPACE` that meets all of these:

- Its name is a repository name: one segment of at most 100 letters, digits, `.`, `_` and `-`. It is not `.` or `..`,
  and it starts with neither `.` nor `-`.
- Its `.git` is a folder. A worktree or a submodule, whose `.git` is a file, is not supported, as on the node.
- `git rev-parse --show-toplevel` answers the folder itself. A folder inside another repository is therefore never
  taken for a package.
- It holds `package.yaml` at its root, where `rail8 lock` looks for a dependency. A package at another folder of its
  repository, which a node's `RAIL8_GIT_PATH` can name, is listed among the folders that are no package.

Changes, artifacts and commits cover the package's folder only. A clone of more than 1000 changed paths is listed, but
its lists are cut there and say so. At most 100 packages are listed.

## How git runs

The git half runs the git binary, version 2.30 or later, found on `PATH` and checked when the development server
starts. When git is missing or older, the Packages screen says so and every route answers 503.

**Why the git binary.** The author's repository is shaped by their own git: config levels, `includeIf` identities,
credential helpers, the ssh agent, hooks, signing, attributes, LFS and `safe.directory`. With the binary, all of it
works unchanged. The alternatives fall short:

- isomorphic-git (MIT) runs no hooks and no credential helpers, and has no ssh.
- libgit2 and nodegit are licensed GPL with a linking exception, which is outside the allowed licences.
- JGit, which the node uses, needs a JVM in the development server.

The binary is the author's tool, not a dependency Rail8 ships. The UI is as safe as the author's terminal, and no
safer: git and the author's hooks run with the author's environment, as they do there.

Every git process, and `rail8 lock`, is started through one runner:

- **No shell.** It uses `spawn` with an argument array, `--no-pager` and `-C <the clone's real path>`.
- **User values stay out of options.** A path comes after `--`, a revision after `--end-of-options`, and a commit
  message on stdin.
- **The environment** is the development server's, with every `GIT_*` and `RAIL8_*` variable removed, then:
  - `GIT_TERMINAL_PROMPT=0`;
  - `GCM_INTERACTIVE=never`;
  - `SSH_ASKPASS_REQUIRE=never`;
  - `GIT_LITERAL_PATHSPECS=1`, so a `*` in a file name is no pattern;
  - `LC_ALL=C`;
  - for reads, `GIT_OPTIONAL_LOCKS=0`, so a read never blocks the author's own git in a terminal.

  An exported `GIT_SSH_COMMAND` or `GIT_SSH` is removed with the rest. The way to set one is `core.sshCommand` in git
  config, which the UI reads like any other setting. The admin token is never passed on.
- **No terminal prompt.** Each process runs in a session of its own, with no terminal, so a credential, ssh or signing
  prompt in a terminal fails at once. A graphical prompt, such as a signing helper's window, runs until the deadline,
  and the answer then says that a helper may have been waiting for input.
- **Machine formats only.** Only these formats are parsed: `status --porcelain=v2 -z`, `log -z --format`,
  `diff-tree -z`, `diff -z --name-status`, `push --porcelain`, `rev-list --count` and `cat-file --batch`.
- **Finite in time and size.** Each call has a deadline on the injected timer and caps on both of its output streams,
  enforced while they are read ("Limits"). At its deadline or its cap, its process group gets TERM, and KILL 2 s later.
  The call ends when its process exits: it never waits for a descendant that still holds one of its pipes. Such a
  descendant is an agent the author's tools start on purpose, such as gpg-agent or a credential cache, and it is not
  killed.
- **Nothing outlives the server.** When the development server closes or restarts, the half kills every process it
  started, the same way.
- **One formatter for everything it says.** Every text that reaches an answer, from git, a hook or the node, passes
  through one formatter. It replaces the user information in a URL (`https://user:secret@host`) with `***`, removes the
  admin token's value wherever it appears, and keeps at most 40 lines of 200 characters. A hook's words come back this
  way, because they tell the author how to fix a refused commit, as in a terminal. The development server logs nothing
  git, a hook or the node says, and never asks for, holds or passes on git credentials.

### The commands it runs

The git half runs only these commands:

- **Reads:**
  - `status`, `log`, `cat-file`, `ls-files`, `ls-tree`, `rev-parse`, `rev-list`, `merge-base` and `var`;
  - `hash-object`, without `-w`;
  - `diff-tree`;
  - `diff --name-status --no-ext-diff --no-textconv`.
- **Writes:**
  - `init` (a new package);
  - `add`, of the untracked files a commit takes;
  - `rm --cached`, only of the paths that `add` added, when the commit fails;
  - `commit --only`;
  - `fetch`;
  - `merge --ff-only --no-overwrite-ignore`, of the commit the fetch left in the upstream;
  - `push`, of the checked commit to the upstream branch.

It never runs:

- `reset`, `checkout`, `switch`, `restore`, `clean` or `stash`;
- `rebase`, or a merge other than a fast-forward;
- `cherry-pick` or `revert`;
- `branch`, `tag`, `remote` or `update-ref`;
- `config` writes, `gc` or submodule commands;
- `--force`, `--no-verify` or `--amend`.

`rail8 lock` writes `package.lock` and nothing else.

### One write at a time

Writes run one at a time across the whole git half. Reads run beside them. A write refuses at once when the clone is
busy:

- when a merge, rebase, cherry-pick, revert or bisect is in progress;
- when a path is in conflict;
- when another git holds `index.lock`.

At most 4 processes run at once. At most 8 writes and 32 reads wait, and one more is refused with 503.

### The working folder's lock

The editors' saves and the git writes share one lock of the working folder, in `ui/server/workingFolder.ts`.

- **Who takes it.** Every save of the flow screen, the endpoint form and the mapping studio, and every git write that
  reads or writes the files of a clone: commit, pull, lock and new package. Fetch and push touch no file and do not
  take it.
- **The save's recheck.** Under the lock, each save compares its `If-Match` with the bytes on disk once more, just
  before its rename. The flow save does this already. The endpoint save and the mapping save gain it, the mapping save
  by comparing the bytes it read for its check.

So no save lands between a commit's check and its end, and no pull lands between a save's check and its rename.

## The routes

The routes live in the `server.ts` of a new UI module, `ui/src/packages/`, under `/git`.

Every route takes only the UI's own pages, by the same rule as the other routes of the development server
(`Sec-Fetch-Site: same-origin`, and an `Origin` from the fixed list). A foreign page is refused with 403.

Every write:

- is a `POST` with a JSON body of at most 1 MiB;
- is refused with 403 under `RAIL8_READ_ONLY=true`, except a deploy check;
- answers, besides its own result, the package's state after it, as `GET /git/packages/{repository}` answers it, so
  that the screen redraws from one answer.

A known success stays a success. When the state cannot be read after a write that succeeded, the answer is the write's
success with `package: null` and the reason.

| Route | Answer |
| --- | --- |
| `GET /git/packages` | `{configured, writable, node, packages: PackageRow[], others: {folder, reason}[], truncated}` |
| `GET /git/packages/{repository}` | `PackageState` |
| `GET /git/packages/{repository}/file?rev=&path=` | One version of one file of the package: `{text}`, or `{binary: true, size}`, with its `ETag` |
| `GET /git/packages/{repository}/changes?from=&to=` | `{changes: Change[], truncated}`: the files that differ between two revisions |
| `GET /git/packages/{repository}/artifacts?rev=` | `{artifacts: Artifact[], truncated}`: the package's artifacts at a revision |
| `GET /git/packages/{repository}/log?before=&limit=` | `{items: Commit[], next}`: the first-parent history of `HEAD`, or of `before`, 50 to a page by default and at most 100 |
| `POST /git/packages` `{repository, name}` | 201 `PackageState`: a new package ("New package") |
| `POST /git/packages/{repository}/commit` `{message, branch, head, paths}` | `{commit, output, package}` ("Commit") |
| `POST /git/packages/{repository}/deploy` `{commit, check, running}` | `{deploy, running, package}` ("Deploy") |
| `POST /git/packages/{repository}/lock` `{manifestTag, lockTag, dependencies}` | `{pins, package}` ("Dependencies") |
| `POST /git/packages/{repository}/fetch` `{}` | `{package}` ("Remote") |
| `POST /git/packages/{repository}/pull` `{branch, head, upstream}` | `{head, changed, package}` ("Remote") |
| `POST /git/packages/{repository}/push` `{branch, head, upstream}` | `{pushed: {ref, from, to}, package}` ("Remote") |

The types:

```ts
/** One package of the working folder, as the Packages screen lists it. */
interface PackageRow {
  repository: string;                     // the clone's folder name: what a deploy and a pin name
  package: { name: string; version: string } | null;
  problem?: string;                       // why package.yaml could not be read
  branch: string | null;                  // null: a detached HEAD
  head: Commit | null;                    // null: no commit yet
  upstream: { name: string; ahead: number; behind: number; fetchedAt: string | null } | null;
  busy: null | 'merge' | 'rebase' | 'cherry-pick' | 'revert' | 'bisect' | 'conflict';
  changed: number;                        // paths of the package's folder that differ from HEAD, untracked ones included
  deployed: Deployed;
}
interface Commit { id: string; parents: string[]; subject: string; author: string; at: string; deployed?: true }

/** One package, as its tab shows it. */
interface PackageState extends PackageRow {
  identity: { name: string; email: string } | null;  // git's author identity in this clone; null: a commit is refused
  changes: Change[];                      // against HEAD, sorted by path
  artifacts: Artifact[];                  // on disk, sorted by path
  dependencies: Dependency[];             // sorted by name
  manifestTag: string | null;             // the tag of package.yaml on disk
  lockTag: string | null;                 // the tag of package.lock on disk; null when there is none
  truncated: boolean;
}
interface Change {
  path: string;                           // relative to the working folder, with '/', as the editors' routes name it
  kind: Kind;
  status: 'added' | 'modified' | 'deleted' | 'renamed';
  renamedFrom?: string;
  index: 'clean' | 'staged' | 'differs';  // the index holds HEAD's version, the version on disk, or neither
  tag: string | null;                     // the git half's tag of the bytes on disk; null when deleted or not on disk
  large?: true;                           // over the read limit: listed, not compared
}
type Kind = 'flow' | 'endpoint' | 'mapping' | 'test' | 'package' | 'lock' | 'folder' | 'file';
interface Artifact { path: string; kind: Kind; id?: string; change?: Change['status']; usedBy?: string[] }
```

A revision (`rev`, `from`, `to`) is `work`, the files on disk, or a commit of 40 hex digits that the clone holds. It
is never a name such as `HEAD`: the browser takes the ids from the package's state, so every review is pinned to a
commit. `from` is a commit, and `to` is `work` or a commit.

A path is read up to 1 MiB. A path with a `.git` segment is 400. A path outside the package's folder, or a revision the
clone lacks, is 404. A path whose real path leaves the package's real folder is 403.

A change's tag is the git half's own: the `ETag` of the raw bytes on disk (`etagOf`). It is never compared with an
editor's tag. The mapping studio, for one, tags the canonical bytes of a mapping.

A file's kind comes from its top-level `kind` key for YAML, or from its name for `*.mapping.json`, `package.yaml`,
`package.lock` and `folder.yaml`. Every other file is `file`. Its `usedBy` lists the artifacts that name its path from
the package's root.

Every refusal is `{error, problems?, output?}`, with the status:

| Status | When |
| --- | --- |
| 400 | A malformed body, repository name, path or revision. A path with a `.git` segment. A message that is blank, over 8 KiB or holds a NUL. More than 1000 paths. |
| 403 | A foreign page. Any write under `RAIL8_READ_ONLY=true` except a deploy check. A path that leaves the package's real folder. |
| 404 | No working folder, no such package, a path outside it, or a revision the clone lacks. |
| 409 | The clone is busy. A detached HEAD for a commit, pull or push. A chosen path whose index `differs`. No upstream for a fetch, pull or push. A pull that is not a fast-forward, or that would overwrite a change or an ignored file. A push the remote rejects. A new package over a folder that exists. |
| 412 | What the write states differs from what is there now: `branch`, `head` or `upstream`; a path's `tag`; `manifestTag` or `lockTag`; a dependency's `head`; or the commit the node runs. Nothing was done. |
| 413 | A body over its limit. |
| 422 | No git identity. Nothing to commit. A hook refused the commit (its `output`). The problems `rail8 lock` printed. A package name the schema refuses. |
| node's | The node's own 400, 403, 409, 422, 500 or 503 for a deploy, passed on with its `error` and `problems`. Its 500 is a redeploy that rolled back. |
| 500 | git or `rail8 lock` wrote more than its output limit, and was stopped. |
| 502 | The node does not answer, or refuses `RAIL8_ADMIN_TOKEN` (its 401). |
| 503 | git, or the `rail8` command for a lock, is missing. Too many calls are waiting. |
| 504 | git or `rail8 lock` took longer than its limit. For a deploy: the node gave no answer in time, and the outcome is unknown. |

## State against git

A package's state comes from these reads:

- **The status.** One `git status --porcelain=v2 --branch -z --untracked-files=all -- <package folder>` gives:
  - the branch and `HEAD`;
  - the upstream, with ahead and behind;
  - every changed path, and for each, whether its index holds `HEAD`'s version (`clean`), the version on disk
    (`staged`), or neither (`differs`).
- **The last commit's subject.** `log -1`.
- **An operation left open.** The state files git leaves in `.git`: `MERGE_HEAD`, `rebase-merge`, `rebase-apply`,
  `CHERRY_PICK_HEAD`, `REVERT_HEAD` and `BISECT_LOG`.
- **The last fetch.** `fetchedAt` is the time `FETCH_HEAD` was written. The git half never fetches by itself.
- **A change's tag.** The git half's tag of the bytes on disk.

The temporary files of a save (`.<name>.<uuid>.tmp`) are never listed.

Between two commits, `…/changes` reads `diff-tree -r -z -M`. Between a commit and `work`, it reads
`diff -z --name-status -M <commit>`, and the untracked paths of the status.

### Readable changes

A change is shown by comparing two versions of the file, read through `…/file` at two pinned revisions:

- since the last commit: `HEAD`'s id and `work`;
- since what runs: the deployed commit and `work`;
- a commit in History: its first parent and the commit itself.

The last two take their list of files from `…/changes`.

The `work` side's `ETag` must equal the listed change's tag. When it differs, the file changed after the list was read.
The view then reads the package again and draws nothing from the stale pair. A file marked `large` reads "too large
to compare".

The comparison runs in the browser, in the module that owns the kind ("The shell's part"). Each comparison is a pure
function with a file of cases.

**A flow.** Both versions are read as values, with the flow screen's reader, so comments, quoting and key order never
count. When the values are equal and the bytes are not, the change reads "Only comments or formatting changed."

- *Steps are matched by name.* Each list of steps (the flow's own, and each process's) is matched by the step's
  effective name: its `name`, else its type, else the process it calls.
  - Names are unique in each list, as the node requires.
  - A saved file may still break that rule. The steps of that list that share a name are then matched in their order,
    and the change says so.
  - Processes are matched by name, and endpoints by id.
- *What it reports:*
  - a step added, removed or moved (out of order among its matched neighbours, found by the longest common subsequence
    of names);
  - a renamed step, as one removed and one added: the message history keeps the old name too;
  - every changed setting of a matched step, leaf by leaf, as `key.path: before → after`, each value on one line of at
    most 200 characters, with `secret://` names as written;
  - the flow's own settings (`from`, `delivery`, `recording`, `limits`, `partitionBy` and the rest), the same way.
- *How it is drawn:*
  - the newer version as the flow screen's picture, read-only;
  - added steps marked with the success token, and changed or moved ones with the warning token;
  - the removed steps listed beside the picture, each with the step it followed;
  - the list of changes under the picture, where choosing a line selects its step;
  - an added flow drawn all as added, and a deleted one as the older version's picture, marked deleted;
  - a version that cannot be drawn shows its diagnostics.
- *Its context.* Each side is drawn with the context of its own revision:
  - the endpoints and flows of its package, read at that revision through `…/artifacts` and `…/file`;
  - the files of other packages as they are on disk.

  An endpoint renamed since a commit is therefore drawn under the name it had in that commit.

**A mapping.**

- *The signature.* The source and target structures: their bundles, their roots, and "structure changed" when a
  fingerprint differs.
- *Target fields.* One row for each target field added, removed or changed. Each side is written as one formula, from
  its result box down: functions with their inputs and parameters, source fields with their contexts, and UDFs by
  name, for example `concat(/Orders/Order/Id, "-")`.
  - Boxes that are only numbered or placed differently are no change.
  - A change of layout alone is counted once: "boxes moved in *n* target fields".
- *UDFs.* Inputs and parameters as fields. Their source is code, so it is shown as a line diff.
- *The description.*

The mapping change is a ProTable of target field, before, after and a status tag.

**An endpoint.**

- Its scheme and direction first.
- Then `config`, leaf by leaf, under the field titles of its component's settings description where the node gives
  them, else under the key paths.
- Then each environment's overlay.

**`package.yaml`, `package.lock` and `folder.yaml`.** Their fields, and the dependencies as a table of name, version
and pin, before and after.

**Any other file.** A line diff (jsdiff, BSD-3-Clause), at most 5000 lines a side. A binary file shows its two sizes.

## Commit

The commit dialog opens from Changes. It lists every change of the package, with:

- a message, required;
- "Committing as *name* <*email*>", the identity git gives in this clone (`git var GIT_AUTHOR_IDENT`).

Every change is ticked, except:

- **A path an open tab holds with unsaved changes.** It is unticked, says "unsaved in an open tab", and offers
  **Show tab**. The author may still tick it, to commit the version saved on disk.
- **A path whose index `differs`.** It is unticked and cannot be ticked. It says "staged in a terminal with other
  content: commit or unstage it there".
- **A path marked `large`.** It is unticked and says "too large to compare". The author may tick it.

The dialog's buttons are **Commit**, and **Commit and deploy**, which goes on to the deploy dialog for the new commit.

`POST …/commit` takes `{message, branch, head, paths: [{path, tag}]}`. `tag` is the tag of the version the author
reviewed.

1. **Refusals.** It is refused when:
   - the clone is busy, or `HEAD` is detached;
   - git has no identity (422, naming `git config user.name` and `git config user.email`);
   - a path is not among the package's changes;
   - a path's index `differs` (409): `commit --only` would replace what the author staged.
2. **What the author saw.** Under the working folder's lock, `branch` and `head` must still be the branch and `HEAD`,
   and each path's `tag` must still be the tag of its bytes on disk (`null` for a deleted file). When any differs, the
   answer is 412 and nothing is done.
3. **The commit.** `git add -- <the chosen untracked paths>`, then `git commit --only -F - -- <paths>`, with the
   message on stdin.
   - Only the chosen paths change. A renamed file is chosen with both its paths.
   - Whatever the author staged on other paths stays staged.
   - The author's hooks and signing run, as in a terminal.
   - When the commit fails, the paths this request added are taken out of the index again (`rm --cached`), and the
     hook's words come back as `output`. A path the author had staged stays staged.
4. **The read-back.** Every path the new commit changes against its parent is read back. Two things are reported, and
   neither is undone:
   - a path that was not chosen, which a hook staged;
   - a chosen path whose blob is not the `hash-object` of its bytes as they were checked, which a hook rewrote.

   Its parent is compared with `head`. A commit made in a terminal in between is reported too: it is the author's own,
   and nothing is lost.

The branch moves by `git commit` itself. Making that move conditional on the reviewed tip would need `commit-tree` and
`update-ref`, which skip the author's hooks.

**Before sign-in, the author is whoever git says on this machine**, exactly as for a commit made in a terminal. The UI
never writes git config and takes no identity typed into it.

**Open tabs.** A commit changes no file, so every open tab keeps its working copy, and its `ETag` stays valid. A tab's
unsaved changes stay in the tab, uncommitted, until it saves them and they are committed later.

## Deployed state

### Today: the node at RAIL8_ADMIN_URL

The git half reads `GET /admin/packages` and `GET /admin/components` from the node at `RAIL8_ADMIN_URL`, with
`RAIL8_ADMIN_TOKEN`, as the mapping half does. `/admin/packages` gives what that node runs, and `/admin/components`
gives its environment. Every relation names that node, the host of `RAIL8_ADMIN_URL`. It says what that node runs and
nothing of the pool's other nodes.

The node's entry for the package is matched by `repository`. Its commit is related to `HEAD` with
`cat-file -e <commit>^{commit}`, `merge-base --is-ancestor` in both directions, and `rev-list --count`:

```ts
type Deployed =
  | { node: 'unreachable'; host: string; error: string }
  | { node: 'answered'; host: string; relation: 'none' }
  | { node: 'answered'; host: string; relation: 'deployed' | 'behind' | 'ahead' | 'other'; commit: string;
      commits?: number; state: 'RUNNING' | 'FAILED'; problems: string[]; deployedAt: string };
```

| Relation | Meaning | Reads |
| --- | --- | --- |
| `deployed` | The node runs `HEAD`. | "Deployed", or "Deployed, with changes since" while files differ from `HEAD` |
| `behind` | The node runs an ancestor of `HEAD`, `commits` back. | "Behind *n*" |
| `ahead` | `HEAD` is an ancestor of what runs, `commits` back. | "Ahead *n*": pull |
| `other` | The commit is not in `HEAD`'s history, or the clone lacks it. | "Other commit" |
| `none` | The node runs nothing of this repository. It may never have run it, or it was undeployed. | "Not running on *node*" |

An entry listed `FAILED` reads "Failed", with its problems. Uncommitted changes are never deployed, and the tab says
so while there are any.

### Every node of the pool

A pool's nodes follow its records ("Several nodes" in `docs/DEPLOY-FROM-GIT.md`), but no admin route says what each
node runs. A node keeps that only in memory, and shows it in `/health` as `BEHIND`. Showing it per node takes a change
on the node. That change is a step of its own, designed first, and the owner sees its design before it is built:

- Each node writes, in Postgres, a row per package it runs: the repository, the commit, its state, and why it is
  behind. It writes the row when it installs or undeploys a package, and when a tick fails to install one.
- `GET /admin/pool/packages` answers those rows for every node whose lease holds.
- Its design also weighs an expected pool generation on a deploy, for when a shared server lets several authors deploy
  at once.

The deployed state then gains one entry per node, with the same relations. Until then, it shows the node at
`RAIL8_ADMIN_URL`, and names it.

## Deploy

The deploy dialog opens from the header, from **Commit and deploy**, or from **Deploy this commit…** in History. It
always checks first:

1. **Check.** `POST …/deploy {commit, check: true}`, with `commit` as 40 hex digits from the package's state.
   - The git half checks that the clone holds it, with `rev-parse --verify --end-of-options <commit>^{commit}`.
   - It reads `/admin/packages`, and answers as `running` the commit the node runs for this repository, or `null`.
   - It sends `POST /admin/deploy?check=true` with `{repository, commit}`, the token, and `X-Rail8-Actor` set to the
     identity, `Name <email>`, at most 128 characters, or `ui` when git has none.
2. **The answer.** The dialog shows the added, swapped and removed flows, or every problem. Problems end the dialog,
   and nothing has changed on the node.
3. **Deploy.** The author confirms. The browser sends `{commit, check: false, running}`, with the commit and `running`
   the check answered.
   - Just before it applies, the git half reads `/admin/packages` again. When the node no longer runs `running`, the
     answer is 412 and nothing is sent.
   - Otherwise it sends the same request without `check`, and the dialog shows the node's answer: what was actually
     added, swapped and removed.

The check first is the screen's rule. The node's own order is the guarantee: every problem of a deploy is answered
before anything stops. A pool runs one deploy at a time, so the window between the second read and the deploy is the
only one left.

In a deployed environment, the node takes only a commit reachable from its `RAIL8_GIT_REF` on the git host. The check
fetches the commit, proves that, and refuses it before anything changes.

**The node's client.** The git half calls the node with a client of its own in the development server, which sends the
body, the token and `X-Rail8-Actor`. It never goes through the `/admin` proxy, which passes no body.

**A lost answer.** A deploy the node does not answer in time is 504, "outcome unknown". The dialog then reads what the
node runs before it offers to try again.

**Going back.** Going back is a deploy of an earlier commit, from History, through the same dialog. The working
folder is not touched. Afterwards the package reads "Behind *n*", which is what it is.

Undeploy stays with the command line.

## Dependencies

The Dependencies tab reads `package.yaml` and `package.lock` on disk. For each dependency it shows:

```ts
interface Dependency {
  name: string; version: string;                                   // from package.yaml
  pin: { repository: string; commit: string; content: string } | null;  // from package.lock; null: not pinned
  head: string | null;                                             // the HEAD of the clone pin.repository names; null: missing
  clone: 'pinned' | 'newer' | 'changed' | 'missing';               // that clone, against the pin
  running?: { commit: string; content: string };                  // what the node resolved for it, when it runs the package
}
```

| State | Meaning |
| --- | --- |
| `pinned` | The clone's `HEAD` is the pinned commit. |
| `newer` | `HEAD` is a descendant of the pin. |
| `changed` | The clone's files differ from its `HEAD`, so a lock would refuse it. |
| `missing` | No clone of that name is in the working folder. |

**Lock** is `POST …/lock {manifestTag, lockTag, dependencies: [{repository, head}]}`: the tags of `package.yaml` and
`package.lock`, and the `HEAD` of each dependency clone, as the author saw them.

- It runs the Rail8 command line, `RAIL8_CLI`, by default the `bin/rail8` of the Rail8 checkout the UI runs from, as
  `lock <package folder> --deps <working folder>`. It goes through the same runner, with its own limits, under the
  working folder's lock.
- Any of the tags or heads that differs is 412, checked before the command runs.
- Its `problem:` lines come back as a 422.
- Its pin lines come back as `pins`.
- `rail8 lock` writes `package.lock` through a temporary file and a rename, so a lock stopped at its deadline leaves the
  old file whole. That change to the command line is built with this step.

The new `package.lock` then shows among the changes, compared by its pins, and is committed like any other change.
The content digest stays one implementation, the node's, and is never written again in TypeScript. The command line
needs JDK 25 and the built CLI jar, which the quickstart already requires. When it is missing, Lock says how to build
it.

## Remote

- **Fetch** is `git fetch -- <the branch's remote>`. Ahead and behind are as of the last fetch, and the screen shows its
  time.
- **Pull** takes `{branch, head, upstream}`. It is a fetch, then, under the working folder's lock, a fast-forward to
  what the fetch brought.
  - `branch`, `head` and `upstream` must still be what they were.
  - It resolves `@{upstream}` once, to a commit id, and runs `git merge --ff-only --no-overwrite-ignore
    --end-of-options <id>`.
  - The server refuses it when the branch has diverged, or when git says a local change or an ignored file would be
    overwritten.
  - The screen refuses it while a tab of the package holds unsaved changes.
  - Afterwards, the clean tabs of the files it changed are reloaded ("The shell's part").
- **Push** takes `{branch, head, upstream}`. It is `git push --porcelain -- <remote> <head>:refs/heads/<upstream
  branch>`: the commit it checked, never the name `HEAD`. It never forces.
  - `branch`, `head` and `upstream` must still be what they were.
  - Without an upstream, it is refused, naming `git push -u` for a terminal.
  - When the remote rejects it (a protected branch, a push that is not a fast-forward), the answer is a 409 with git's
    lines.
- **Credentials** are the author's credential helper or ssh agent. No terminal prompt can open, so a helper that must
  ask in a terminal fails and says so.

The UI leaves these to the git host:

- creating the remote repository;
- branch protection;
- review and the merge of pull requests;
- release branches, and moving the branch a pool's `RAIL8_GIT_REF` names;
- tags and releases;
- who may push.

It leaves these to a terminal:

- clones, branches, merges other than a fast-forward, rebases and conflicts;
- stash, reset, discard and amend;
- remotes and submodules.

## New package

`POST /git/packages {repository, name}` makes a new package in the working folder, under the working folder's lock:

1. **The names.** The repository name follows the rule above, and the package name follows `package.yaml`'s schema
   (`^[a-z][a-z0-9-]*$`, at most 64 characters).
2. **The files.** In a hidden temporary folder of the working folder, it writes:
   - `package.yaml`: the name, version `0.1.0`, no dependencies;
   - `package.lock`, as `rail8 lock` writes it with no dependency;
   - `.gitattributes`, holding `* -text`.
3. **The repository.** In that folder it runs `git init -b main`, `git add -A` and a first commit, "Create package
   *name*". The commit is refused, like any other commit, without a git identity.
4. **Into place.** The folder is renamed to the repository's name. The rename is refused (409) when that name exists.
   A failure at any point deletes the temporary folder, which is the server's own, and nothing else.

It adds no remote. In the local run, the node reads the working folder as its base, so the new package can be
deployed at once. A deployed environment needs the repository on the git host first.

## The shell's part

Modules still import no other module. The shell gains two contracts, as files directly in `ui/src/`:

- **Artifact kinds.** `UiModule` gains an optional `artifacts: ArtifactKind[]`, collected by `main.tsx` into a context
  the packages module reads. A kind registered twice is refused at the start, as two modules in one place are. Each
  module registers the kinds it owns: the endpoints module registers `flow` and `endpoint`, and the mapping module
  registers `mapping`. Every other kind is drawn by the packages module as a line diff or a field table.

  ```ts
  /** One version of a file, with the files of its package at the same revision. */
  interface Side {
    rev: string;                                       // 'work' or a commit of 40 hex digits
    text: string;
    artifacts(): Promise<Artifact[]>;                  // the package's artifacts at rev
    read(path: string): Promise<string | null>;        // a file of the package at rev; null: none there
  }
  interface ArtifactKind {
    kind: 'flow' | 'endpoint' | 'mapping';
    /** The tab the module's own list opens for the file at `path`. */
    open(path: string): Screen;
    /** The readable change between two versions of the file at `path`; null: no such version on that side. */
    Change: ComponentType<{ path: string; before: Side | null; after: Side | null }>;
  }
  ```

- **Working files.** An editor registers the file its tab holds, whether it has unsaved changes, and how to read it
  again from disk, with `useWorkingFile(path, dirty, reload)`. `useWorkingFiles()` answers the open files with their
  state. `reload(paths)` reloads the tabs of those files: it reads each tab's dirty state just before, and leaves a
  dirty tab as it is. Opening a tab that is already open only selects it, so a reload is the only way a tab shows the
  new file. The commit dialog, the pull and the shell's own question for unsaved changes read this one registry. It
  holds no tags.

## Limits

| Limit | Value |
| --- | --- |
| Packages listed | 100 |
| Changed paths of one package, of one `…/changes`, and paths of one commit | 1000 |
| The walk of a package's artifacts, on disk or at a revision | the working folder's: 1000 files, depth 16 |
| One version of one file, read | 1 MiB |
| A write's body | 1 MiB |
| A commit message | 8 KiB |
| History | 50 commits a page, at most 100 |
| git processes at once | 4 |
| Calls waiting | 8 writes, 32 reads |
| A git read | 10 s, 4 MiB on each stream |
| A commit, with its hooks | 60 s, 1 MiB on each stream |
| Fetch, pull, push | 120 s each, 1 MiB on each stream |
| `rail8 lock` | 120 s, 1 MiB on each stream |
| From TERM to KILL | 2 s |
| The node, for a read | 10 s, 8 MiB |
| The node, for a deploy or a check | 5 min, the wait of `rail8 deploy` |
| What git, a hook or the node says, in an answer | 40 lines of 200 characters |
| A line diff | 5000 lines a side |

## Out

- Editing on a shared server, and writes to git there, until sign-in exists. A server for the UI outside development
  answers the reads and refuses every write, as `RAIL8_READ_ONLY=true` does.
- Branches, merges other than a fast-forward, rebases, conflicts, stash, reset, discard, amend, and anything that
  rewrites history.
- Cloning, remotes, submodules and worktrees.
- A package at a folder other than its clone's root.
- Choosing hunks, and the index as such. The author chooses whole files.
- Undeploy, which stays with the command line.
- Settings of the git host.

## Tests

- **The runner.** Its suite runs on a fake process for:
  - every argument vector, with user values only after `--`, after `--end-of-options` or on stdin;
  - the environment, with inherited `GIT_*` and `RAIL8_*` removed;
  - output caps on both streams;
  - the queue's limits;
  - the formatter's redaction of `https://user:secret@` and of the token's value.

  It also runs on real child processes, with the deadline on the fake timer, never a sleep:
  - a child that never exits, stopped at its deadline;
  - a child that floods both streams;
  - a child whose descendant keeps a pipe open;
  - a server shut down while a child runs.
- **The git half.** `PackagesApi` (`list`, `get`, `file`, `changes`, `artifacts`, `log`, `create`, `commit`, `deploy`,
  `lock`, `fetch`, `pull`, `push`) has two implementations:
  - `httpPackagesApi`, over the routes, with real git in temporary folders, a bare `file://` upstream, a fake node
    (`/admin/packages`, `/admin/components`, `/admin/deploy` with scripted answers) and a fake lock;
  - `fakePackagesApi`, in memory: commits as maps of path to bytes, the files on disk, the index, an upstream, busy
    states, the identity and a fake node.

  One contract suite runs on both, seeded through one builder, and asserts only through the API. Its cases:
  - **The list.** A folder that is no package, a worktree, a folder inside another repository, and a package that is
    not at its clone's root.
  - **Reads.**
    - Changes after an edit, an add, a delete and a rename, with each `index` state.
    - The changes between two commits.
    - A file and the artifacts at `work` and at a commit.
    - `HEAD` refused as a revision.
    - `.git/config` refused as a path, and a link that leaves the package.
  - **Deployed state.** Each relation.
  - **Commit.**
    - A commit of chosen paths that leaves other staged paths staged.
    - A chosen path staged with other content (409).
    - A stale `branch`, `head` or `tag` (412).
    - A hook that refuses, with only the request's own additions taken out of the index.
    - No identity.
    - The read-back after a hook rewrote a file, and after a hook staged another path.
    - A save that waits for a commit and is then refused by its recheck.
    - A detached HEAD, a merge in progress and a conflict (409).
  - **Deploy.**
    - A check, then a deploy, sending 40 hex digits and the actor.
    - A deploy refused by the node, its problems passed on.
    - A deploy refused because the node's commit changed since the check (412).
    - A deploy the node does not answer (504).
    - The node refusing the token (502).
  - **Lock.** A lock, and a lock refused because a dependency's `HEAD` moved (412).
  - **Remote.**
    - A fast-forward pull, a diverged one, one that would overwrite a change, and one that would overwrite an ignored
      file.
    - A pull or a push after the branch was switched at the same commit (412).
    - A rejected push, and a push without an upstream.
  - **New package.** A new package, and one over an existing folder.
  - **Refusals everywhere.** Every write under `RAIL8_READ_ONLY=true`, and every route for a foreign page.
  - **Hostile values.** `-x`, `:(glob)*` and `..` as a path, a revision or a name.
- **The readable changes.** Each comparison (`flowChanges`, `mappingChanges`, `endpointChanges` and the package
  files') has a file of cases, among them:
  - a step added, removed, moved and renamed;
  - steps with the same name in one list, matched in order;
  - a setting changed;
  - a change of formatting only;
  - an endpoint renamed since the older side;
  - a field remapped, and boxes moved;
  - a UDF changed;
  - an overlay changed.
- **The screens.**
  - Browser tests run on `fakePackagesApi`.
  - A pull runs while a second window saves a file of the package.
  - One `@stack` scenario edits a flow, commits it, deploys the commit to the quickstart's node, and sends a message
    through the new flow.

## What each step builds

1. **The Packages screen, read-only.** It builds:
   - the runner, with its real-child tests and its shutdown;
   - the rule for which folders are packages;
   - `GET /git/packages`;
   - the deployed state from the node at `RAIL8_ADMIN_URL`;
   - the fake and the contract suite.

   The owner sees each package with its branch, last commit, changed files, ahead and behind, and whether the node runs
   it.
2. **A package's tab.** It builds:
   - `GET /git/packages/{repository}`, `…/file`, `…/changes`, `…/artifacts` and `…/log`, with the git half's tags;
   - the Artifacts, Changes and History tabs;
   - the shell's artifact kinds, opening the existing editors;
   - the Package column of the three lists.

   Until its readable change is built, a change of a flow, an endpoint or a mapping reads only "changed". Any other
   file has its line diff.
3. **The readable change of a flow.** `flowChanges` and the marked picture, with the context of each side's revision,
   in the endpoints module.
4. **The readable changes of an endpoint and a mapping.** In their modules.
5. **The working folder's lock.** The lock in `ui/server/workingFolder.ts`, and the `If-Match` recheck under it in the
   saves of the flow screen, the endpoint form and the mapping studio.
6. **Commit.** It builds:
   - `…/commit`, the dialog, the identity and the read-back;
   - the shell's working files, fed by the flow screen, the endpoint form and the mapping studio.
7. **Deploy and going back.** It builds:
   - `…/deploy` with the node's own client, and the dialog with its check;
   - **Commit and deploy**, and **Deploy this commit…** in History;
   - the `@stack` scenario.

   The common case then works end to end: edit, commit, deploy.
8. **Dependencies and Lock.** The Dependencies tab, `…/lock` through `RAIL8_CLI`, and the write of `package.lock`
   through a temporary file and a rename in `rail8 lock`.
9. **Remote.** Fetch, the fast-forward pull with the clean tabs reloaded, push, and the two-window test.
10. **New package.** `POST /git/packages` and its dialog.
11. **What each node runs, on the node.** The rows of what each node runs and `GET /admin/pool/packages`. It is designed
    first and depends on nothing in the UI, so its design may start at any time.
12. **Every node in the UI.** One entry per node in the deployed state.
