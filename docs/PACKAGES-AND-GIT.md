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
  is the node's own deploy of a commit, `POST /admin/deploy`, sent on the author's request. Two exceptions are planned,
  each through the admin API: the secret store (`docs/SECRET-STORE.md`) and the configuration of an environment
  (`docs/ENVIRONMENT-CONFIGURATION.md`).
- Each package is a clone of its own repository: a folder directly in the working folder, named as its repository. The
  name of the folder is what a deploy and a pin name.
- The UI runs the author's own git binary. A commit from the UI is therefore the commit a terminal would make, with the
  same identity, hooks, signing and credentials.
- The UI loses the author's work only when the author asks for it, for the files its dialog names first: a discard.
  Every other command it runs keeps every change, never moves a branch backwards and never rewrites history. It runs a
  short, fixed list of git commands. Everything else is left to a terminal and to the git host.
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
folder, sorted by repository. Above the table it names the node it reads, the host of `RAIL8_ADMIN_URL`, and says when
it shows every node of that node's pool too. Each row shows:

- the repository;
- the package's name and version;
- the branch;
- the last commit: its subject, short id and age;
- ahead and behind its upstream, as of the last fetch;
- the number of changed files;
- the deployed state on that node: Deployed, Deployed with changes since, Behind *n*, Ahead *n*, Other commit, Failed,
  or Not running on *node*;
- in a pool, below it, how many of the pool's nodes run which commit, and the nodes behind the pool's record
  ("Every node of the pool");
- "Pinned behind by *n*" when *n* packages that node runs pin a commit of it behind the one it runs ("Used by").

**New package** and **Get a package…** sit above the table.

Some folders are not packages:

- one with no clone of its own;
- a worktree;
- one without `package.yaml` at the root of its clone.

These are listed below the table, each with the reason. Their files stay in the lists of flows, endpoints and
mappings.

### A package's tab

A row opens the package in a tab of its own, `package:<repository>`.

The header shows the package's name and version, the branch, the last commit, ahead and behind, and the deployed state,
with the nodes of the pool in a pool.
Its actions are Commit…, Deploy…, Fetch, Pull and Push. Each action is disabled, with the reason, when its route
would refuse it.

The tab has five tabs of its own:

- **Changes.** Every file of the package that differs from the last commit: added, modified, deleted or renamed. Each
  file shows its kind and its readable change ("Readable changes"). Commit… starts here. Each row has a tick box,
  except a row staged in a terminal with other content; **Discard changes…**, in the card's header, puts the ticked
  files back as they are in the last commit ("Discard").
- **Artifacts.** One tree of the package's folders, folders first, each holding its flows, endpoints, mappings and
  tests, and the other files they use.
  - Each row shows its kind's icon and word, its id or file name, whether it changed, and, for a file, the artifacts
    that use it. A folder whose `folder.yaml` gives a namespace shows it beside its name, and a folder the rules
    refuse (its name, a depth over four, a namespace another folder has) is marked with why.
  - A row opens the artifact in the tab its own screen opens.
  - Above the tree, a filter by kind, one click for only the flows, and by text in names, paths and namespaces; and
    Expand all and Collapse all. The folders closed stay closed while the tab lives.
  - Choosing a folder's name chooses it for **New folder…**, **New flow** and **New mapping**, which write into it.
    With none chosen, New folder writes into the package's root, New flow into `flows/` and New mapping into
    `mappings/`.
  - **Edit folder…** on a folder's row sets its namespace and its description in its `folder.yaml` ("Folders").
  - The tree moves and renames nothing.
- **History.** The commits of the branch, newest first, 50 to a page. The commit the node runs is marked.
  - Each commit has **Deploy this commit…**.
  - Choosing a commit shows the files it changed against its first parent, each with its readable change.
- **Dependencies.** Each dependency's name, version, pin and state ("Dependencies"), with **Lock**.
- **Used by.** Each package the node runs that pins this one, and how its pin relates to what runs and to `HEAD`
  ("Used by").

The tab reads its package again when it is shown, when the window gets the focus, and from the answer of every write.

### Finding an artifact

There is no list of flows, endpoints or mappings across the working folder. An artifact opens from the Artifacts tab of
its package, where New flow and New mapping write new ones, or from Quick open (Ctrl+K), which finds a flow, an endpoint,
a mapping or a test of any package by its name or its path without knowing its package. Quick open opens an artifact
under the breadcrumb Packages › its package, and a test, which has no editor, as its package's tab on Artifacts.

A folder of the working folder that is no package but holds artifacts, such as the mapping corpus or the package folder
of an adapter, is a link in the list of folders that are no package. Its tab shows the tree of the artifacts it holds,
with the New of each kind; with no folder chosen, a New starts where the folder's artifacts of its kind already are.
Quick open finds its artifacts under Packages › the folder.

### Folders

A folder of a package is a namespace when its `folder.yaml` says so. New folder… makes a folder with its name, below
the chosen folder, and writes its `folder.yaml`, so that git keeps the folder. Edit folder… changes the namespace and
the description of a folder, and writes a `folder.yaml` when the folder has none. Both check the package's rules as the
author types, and the git half checks them again: a folder's name starts with a small letter and holds small letters,
digits and `-`, at most 64 characters; a folder lies at most four folders deep; a namespace holds 1 to 1024 characters
and belongs to one folder only. A write of `folder.yaml` keeps its comments and its other keys.

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
- **Every path is literal.** Each path is given as `:(literal)<path>`, so a `*` in a file name is no pattern. It is
  never set through `GIT_LITERAL_PATHSPECS`, which the author's hooks would inherit: a hook's own patterns, such as
  `git diff --cached -- '*.java'`, keep working.
- **The environment** is the development server's, with every `GIT_*` and `RAIL8_*` variable removed, then:
  - `GIT_TERMINAL_PROMPT=0`;
  - `GCM_INTERACTIVE=never`;
  - `SSH_ASKPASS_REQUIRE=never`;
  - for reads only, `LC_ALL=C`, and `GIT_OPTIONAL_LOCKS=0` so that a read never blocks the author's own git in a
    terminal. A write keeps the author's locale for its hooks. The half reads only exit codes and machine formats from
    a write, and shows git's words as they are.

  An exported `GIT_SSH_COMMAND` or `GIT_SSH` is removed with the rest. The way to set one is `core.sshCommand` in git
  config, which the UI reads like any other setting. The admin token is never passed on.
- **No terminal prompt.** Each process runs in a session of its own, with no terminal, so a credential, ssh or signing
  prompt in a terminal fails at once. Fetch, the fetch of a pull, and push run with `-c core.askPass=` before the
  command, so git asks neither `core.askPass` nor `SSH_ASKPASS` for a password, and falls to the terminal prompt that
  `GIT_TERMINAL_PROMPT=0` refuses. A credential helper is not affected. A graphical prompt, such as a signing helper's
  window, runs until the deadline, and the answer then says that a helper may have been waiting for input.
- **Machine formats only.** Only these formats are parsed: `status --porcelain=v2 -z`, `log -z --format`,
  `diff-tree -z`, `diff -z --name-status`, `push --porcelain`, `rev-list --count` and `cat-file --batch`.
- **Finite in time and size.** Each call has a deadline on the injected timer and caps on both of its output streams,
  enforced while they are read ("Limits"). At its deadline or its cap, its process group gets TERM, and KILL 2 s later.
  The call ends when its process exits: it never waits for a descendant that still holds one of its pipes. Such a
  descendant is an agent the author's tools start on purpose, such as gpg-agent or a credential cache, and it is not
  killed.
- **Nothing outlives the server.** When the development server closes or restarts, the half kills every process it
  started, the same way. A clone of Get a package that has not started is refused, and one whose git runs is stopped
  the same way, after which its hidden folder is removed; one whose git had ended goes on into place, and the close
  waits for it ("Get a package"). A commit it stops this way still takes the paths it added out of the index again: that undo
  makes at most six git calls of 10 s and 1 MiB each, is not stopped, and the close waits for it. While a commit is
  under way, SIGINT and SIGHUP are caught, and the server ends with the signal only after the commit or its undo. The
  first signal during an undo prints one line saying it is undoing, and for at most how long; a further signal is
  ignored until the undo ends.
- **One formatter for everything it says.** Every text that reaches an answer, from git, a hook or the node, passes
  through one formatter. It replaces the user information in a URL (`https://user:secret@host`) with `***`, removes the
  admin token's value wherever it appears, and keeps at most 40 lines of 200 characters. A hook's words come back this
  way, because they tell the author how to fix a refused commit, as in a terminal. The development server logs nothing
  git, a hook or the node says, and never asks for, holds or passes on git credentials.

### The commands it runs

The git half runs only these commands:

- **Reads:**
  - `status`, `log`, `cat-file`, `ls-files`, `ls-tree`, `rev-parse`, `rev-list`, `merge-base` and `var`;
  - `for-each-ref` of the branch, for its upstream's remote and the remote's branch;
  - `hash-object`, without `-w`;
  - `diff-tree`;
  - `diff --name-status --no-ext-diff --no-textconv`.
- **Writes:**
  - `init` (a new package);
  - `clone`, of an https or ssh address the rule takes, into a hidden folder of the working folder, without hooks,
    submodules or LFS content (Get a package);
  - `add`, of the untracked files a commit takes;
  - `rm --cached`, only of the paths that `add` added, that `HEAD` lacks and whose index entry is still the blob the
    request checked, when the commit fails or the server closes during it. It leaves the file on disk, and git
    itself refuses it for an entry that is neither `HEAD`'s version nor the one on disk;
  - `commit --only`;
  - `update-index -z --index-info`, of the index entries of the paths a discard names: set to the last commit's, or
    removed;
  - `checkout-index -f -u -z --stdin`, of the paths a discard names that the last commit holds;
  - `fetch`;
  - `merge --ff-only --no-overwrite-ignore`, of the commit the fetch left in the upstream;
  - `push`, of the checked commit to the upstream branch.

It never runs:

- `reset`, `checkout`, `switch`, `restore`, `clean` or `stash`;
- `rebase`, or a merge other than a fast-forward;
- `cherry-pick` or `revert`;
- `branch`, `tag`, `remote` or `update-ref`;
- `config` writes, `gc` or submodule commands;
- `--force`, `--no-verify` or `--amend`, nor anything else that drops the author's own work. A discard drops only the
  paths its request names, through the two plumbing writes above, which take exact paths on stdin and no pathspec.

`rail8 lock` writes `package.lock` and nothing else.

### One write at a time

Writes run one at a time across the whole git half. Reads run beside them. A write refuses at once when the clone is
busy:

- when a merge, rebase, cherry-pick, revert or bisect is in progress;
- when a path is in conflict;
- when another git holds `index.lock`.

At most 4 processes run at once. At most 8 writes and 32 reads wait, and one more is refused with 503.

### The working folder's lock

The editors' saves and the git writes share one lock of the working folder.

- **Where it lives.** `moduleServers` (`ui/server/modules.ts`) makes the lock once and hands it to every half on
  `DevServer`, as `writes`: the working folder's writes, which admit each write and hand it the lock. It cannot be a
  module-level value of `ui/server/workingFolder.ts`, because each half's `server.ts` is loaded on its own and holds
  its own copy of what it imports. It lasts as long as the development server's process, so the halves made again on
  a restart get the same lock. `replaceFile`, `writeNew` and `createFile` take the lock as an argument.
- **Who takes it.** Every save of the flow screen, the endpoint form and the mapping studio, and every git write that
  reads or writes the files of a clone: commit, discard, pull, lock and new package. A discard holds it from its first
  read to its read-back. Fetch and push touch no file and do not
  take it. A clone of Get a package holds one admission for its whole run, and the lock only for the move into place.
- **The save's recheck.** Under the lock, each save compares its `If-Match` with the bytes on disk once more, just
  before its rename. The mapping save compares the bytes it read for its check. Each save also resolves its path
  again. A file that is gone, or a path that now leads to another file, has changed: 412, and 409 `mapping-changed` in
  the mapping studio.
- **The queue.** At most 9 writes are under way: the one that holds the lock and 8 that wait, first in, first out. One
  more is refused with 503 before its body is read. A write counts from its arrival to its end, whatever it waits for:
  the save before it in the same editor, a mapping save's calls to the node, or the lock. A save holds the lock only
  for its write. A git write holds it from its check to its end, and a pull fetches before its check.

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
| `POST /git/packages` `{repository, name}` | 201 `{repository, package}`, with `problem` when `package` is null ("New package") |
| `POST /git/clone` `{address}` | 201 `{repository, package}`, with `reason` or `problem` when `package` is null ("Get a package") |
| `POST /git/packages/{repository}/commit` `{message, branch, head, paths}` | `{commit, output, package}` ("Commit") |
| `POST /git/packages/{repository}/discard` `{head, paths}` | `{discarded, left, package}`, with `problem` ("Discard") |
| `POST /git/packages/{repository}/folder` `{path, tag, namespace, description}` | `{package}`, with `problem` ("Folders"): makes the folder `path` and its missing parents, and writes its `folder.yaml`; `tag` is null for a new `folder.yaml`, else the tag of the one it replaces; a null `namespace` or `description` leaves the key out |
| `POST /git/packages/{repository}/deploy` `{commit, check, expect}` | `{deploy, replaces, package}` ("Deploy") |
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
interface Commit {
  id: string; parents: string[]; subject: string; author: string;
  at: string;                             // the author date, always as ISO 8601 with its offset: UTC is +00:00, never Z
  deployed?: true;
}

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
interface Artifact {
  path: string; kind: Kind; id?: string; change?: Change['status']; usedBy?: string[];
  namespace?: string; description?: string; // for a folder.yaml: its folder's namespace and description
}
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
| 403 | A foreign page. Any write under `RAIL8_READ_ONLY=true` except a deploy check. A path that leaves the package's real folder, or for a discard leads into `.git`. |
| 404 | No working folder, no such package, a path outside it, or a revision the clone lacks. |
| 409 | The clone is busy. A detached HEAD for a commit, pull or push. A chosen path whose index `differs`. A path a discard does not handle, or where a discard would make a file and a folder of the same name meet ("Discard"). No upstream for a fetch, pull or push. A pull that is not a fast-forward, or that would overwrite a change or an ignored file. A push the remote rejects. A lock while a dependency has several candidates. A new package over a folder that exists. A file where a folder's path needs a folder. |
| 412 | What the write states differs from what is there now: `branch`, `head` or `upstream`; a path's `tag` or, for a discard, its `index`, or a path no longer among the changes; `manifestTag` or `lockTag`; a dependency's `folder` or `head`; a `folder.yaml` that is there for a new one, or not the one its `tag` names; or the commit the node runs. Nothing was done. |
| 413 | A body over its limit. |
| 422 | No git identity. Nothing to commit. Nothing to discard. A hook refused the commit (its `output`). A fetch or a push git failed, as when it could not sign in (its `output`). The problems `rail8 lock` printed. A package name the schema refuses. A folder's name, depth or namespace the rules refuse, or a `folder.yaml` at the package's root. |
| node's | The node's own 400, 403, 409, 422, 500 or 503 for a deploy, passed on with its `error` and `problems`. Its 500 is a failure on the node: of git, of the deploy record's write (or a write whose outcome is unknown), or of a start, which was rolled back. The package's state read after it shows what runs. |
| 500 | git or `rail8 lock` wrote more than its output limit, and was stopped. A discard's index write that failed while no other git held `index.lock`, with git's words; a discard whose read-back failed after it wrote. |
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
   - a path is no longer among the package's changes (412, as for a stale tag: the dialog reads the state again);
   - a renamed file is named without the path it was renamed from, or with that path as not on disk while it is on disk
     again (412). The author reviewed a rename between two paths, and git's rename detection can pair the new path
     with another old one meanwhile;
   - a path's index `differs` (409): `commit --only` would replace what the author staged. The 412s of the paths and
     their tags come first.
2. **What the author saw.** Under the working folder's lock, `branch` and `head` must still be the branch and `HEAD`,
   and each path's `tag` must still be the tag of its bytes on disk (`null` for a deleted file). When any differs, the
   answer is 412 and nothing is done.
3. **The commit.** `git add -- <the chosen untracked paths>`, then `git commit --only -F - -- <paths>`, with the
   message on stdin.
   - Only the chosen paths change. A renamed file is chosen with both its paths: the dialog names the old one with
     tag `null`, or with its own tag when its own row is ticked.
   - Whatever the author staged on other paths stays staged.
   - The author's hooks and signing run, as in a terminal.
   - When the commit fails, or the development server closes during it, the paths this request added that `HEAD`
     lacks are taken out of the index again (`rm --cached`), and the hook's words come back as `output` (422). A path
     leaves only while its index entry is the blob the half hashed under the lock before the add, through the path's
     clean filters (`hash-object`); a symbolic link's blob is the text of its target, as git stores it. A path the author had staged stays staged, as does one another git staged with
     other content meanwhile; the error names it. git refuses the whole removal when a path's bytes on disk changed
     since the add, as when a hook rewrote a new file before it refused; the half then hashes the paths again and
     takes out, in a second removal, those whose bytes did not change. A path that stays staged this way is named in
     the error with a command that unstages it in a terminal: `git restore --staged -- <paths>`, or
     `git rm --cached -f -- <paths>` on a branch with no commit yet. Until then it reads `differs`, and the UI cannot
     commit it. git's own words are added to `output`.
   - The error says whether `HEAD` moved meanwhile. Git may then have committed before it was stopped, as in a
     post-commit hook, and the error never says that nothing was committed. It stays an error: a terminal's commit in
     the same moment looks the same, so the half cannot prove the commit is its own.
4. **The read-back.** `git commit` names the commit it made on stdout, in its summary line
   `[<branch> <id>] <subject>`, with a marker such as `(root-commit)` in the author's language between the two on a
   first commit; the hooks print on stderr. The new commit is read back only when `HEAD` is that commit. When `HEAD`
   has moved on at once to another, as when a terminal commits or amends right after, or git named no commit,
   `commit` is null and `problem` names both. When only the later reads fail, `problem` names the commit's short id.
   It stays an answer of 200: git committed.

   Every path the new commit changes against its parent is read back, and the chosen files are hashed again. Three
   things are reported, and none is undone:
   - `staged`: a path that was not chosen, which a hook staged;
   - `rewritten`: a chosen path whose blob is not the `hash-object` of its bytes as they were checked, which a hook
     rewrote;
   - `changedOnDisk`: a chosen path whose bytes on disk after the commit are not those the commit holds, which a hook
     changed without staging. That is a new change, not a different commit.

   Its parent is compared with `head`. A commit made in a terminal in between is reported too: it is the author's own,
   and nothing is lost.

   A hook runs on a temporary index, so what it stages enters the commit but not the author's index. Afterwards each
   path it touched reads as staged in a terminal with other content (`differs`) until the author commits or unstages
   it there.

The answer is `{commit, output, package}`, with `problem` when part of it could not be read:

```ts
interface Committed extends Commit {
  staged: string[];                       // sorted
  rewritten: string[];                    // sorted
  changedOnDisk: string[];                // sorted
}
interface CommitAnswer {
  commit: Committed | null;               // null: git committed, but the commit could not be read back
  output: string;                         // what git and the hooks said
  package: PackageState | null;           // null: the state could not be read after the commit
  problem?: string;                       // why commit or package is null
}
```

When Commit is pressed, the dialog reads the open tabs again. A hidden tab can get unsaved changes of its own while the
dialog is open, as a mapping refreshed on opening does. A ticked path that got them is unticked, a note says so, and
nothing is committed until Commit is pressed again.

After the commit, the dialog has the open tabs of the `staged`, `rewritten` and `changedOnDisk` paths read their files
again, those without unsaved changes. So do the tabs of the paths it did not send whose bytes a hook changed: it
compares the tags of the package's state after the commit with those it showed, and a path whose tag changed, or that
only one of the two lists, is read again. A commit with none of them, and no commit in between, closes the dialog; any
other stays in it, with `output`, to say what happened. A refusal reads the package again.

The branch moves by `git commit` itself. Making that move conditional on the reviewed tip would need `commit-tree` and
`update-ref`, which skip the author's hooks.

**Before sign-in, the author is whoever git says on this machine**, exactly as for a commit made in a terminal. The UI
never writes git config and takes no identity typed into it.

**Open tabs.** A commit changes no file, so every open tab keeps its working copy, and its `ETag` stays valid. A tab's
unsaved changes stay in the tab, uncommitted, until it saves them and they are committed later.

## Discard

**Discard changes…**, in the header of the Changes card, puts the files the author ticked back as they are in the last
commit, in the index and on disk. It is disabled, with the reason, on a read-only server, on a busy clone, and while
no file is ticked. Nothing is ticked at first, the select-all box ticks every row it may, and a row staged in a
terminal with other content (`differs`) cannot be ticked.

**The dialog** lists every file the discard changes, a rename as one file on two lines, and what happens to each:

- a modified file "gets the last commit's version";
- an added file "is deleted: the last commit lacks it";
- a deleted file "comes back from the last commit";
- a renamed file is deleted, and the path it was renamed from comes back, or gets the last commit's version when it is
  on disk again;
- a staged change adds "and unstaged", and a file too large to compare "too large to compare: you have not seen its
  change".

Above the list, each listed file an open tab holds with unsaved edits has an alert with **Show tab**: "The tab keeps
its unsaved edits. When it saves them after the discard, the editor finds the file changed on disk, unless the discard
left its bytes as they were."

One file is confirmed with a tick box, "Discard this change. It cannot be brought back."; more than one by typing their
count, "Type *n* to discard *n* files. They cannot be brought back." The danger button, **Discard *n* files**, stays
disabled until then. Focus starts on Cancel, and Enter sends nothing. The confirmation is dropped whenever a listed
file, what happens to it, its tag or an unsaved-tab alert changes, as when the tab reads the package again on focus.

When Discard is pressed, the dialog reads the open tabs again, both paths of a rename included. A listed file that got
unsaved edits meanwhile is named at the top, the confirmation is dropped, and nothing is sent. After any answer, the
open tabs without unsaved edits of the listed files read them again; a removed file's tab then reads "could not be
read". A discard that left nothing closes the dialog and names what it discarded; one that left files stays open to say
which, and why. A refusal says why, and the tab reads the package again.

**Gone at once.** The UI keeps no copy and offers no undo, and the dialog says so before the author confirms. A raw blob
keeps the bytes but no path, mode or link, git may prune it at once, and writing it back can destroy newer work. A real
way to bring a discard back is a feature of its own. The protection comes before the act: the list of what happens to
each file, the tag and index state the request states, a confirmation that cannot be given by accident, and the
unsaved tabs named first.

`POST …/discard` takes `{head, paths: [{path, tag, index}]}`: `head` as the author saw it (null: no commit yet), and for
each chosen path the tag of its bytes on disk as the author saw them (null: not on disk) and its row's index state,
`clean` or `staged` (null: a renamed change's old path with no row of its own). A renamed change is named with the path
it was renamed from. The paths the body names are exactly the paths the discard may change.

1. **Before the lock.** 403 read-only or a foreign page, 503 when 9 writes are under way, 413 for a body over 1 MiB, 400
   for a malformed body, 404 for a path outside the package, and 422 for no path.
2. **Under the working folder's lock,** from the first read to the read-back, in this order:
   1. 409 when the clone is busy. A detached `HEAD` is allowed: nothing moves.
   2. 412 when `head` is not `HEAD`.
   3. 412 when a path is no longer among the changes, a rename is named without its old path, the old path is named as
      not on disk while it is on disk (the commit's rules), or a path's `index` is not its row's index state now.
   4. 403 when a path, or a folder on its way, leaves the package's real folder or leads into `.git`.
   5. 412 when a tag is not the tag of the bytes on disk now.
   6. 409 for a path the discard does not handle: its index `differs`; it is intent to add (`git add -N`); it is a
      symbolic link on disk, a dangling one included; it is a submodule; its index entry is skip-worktree (sparse
      checkout); or it is another path but for letter case.
   7. 409 when a file and a folder of the same name would meet, since git deletes one to make room for the other: a
      leading folder that is a link, a chosen path that is a folder holding anything, ignored files included, and, for
      a path the last commit holds, an index entry under it or one at a leading folder. The last commit holds a path
      when `ls-tree -r -z <head>` lists it by its exact name.
3. **The index.** `git update-index -z --index-info` sets each chosen path's entry to the last commit's mode and blob, or
   removes it when the last commit lacks the path, in one atomic write. When it fails, nothing has changed: 409 when
   another git holds `index.lock`, else 500 with git's words, never decided from git's words.
4. **The files.** An empty folder at a chosen path the last commit holds is removed with `rmdir`. Then
   `git checkout-index -f -u -z --stdin` writes the chosen paths the last commit holds from the index, in path order,
   through the author's smudge and eol filters, with the index's mode, and a link as a link. No hook runs.
5. **Removed files.** Only when nothing before failed, the half unlinks each chosen path the last commit lacks that is a
   file on disk, after resolving its folder and reading its tag once more. Folders the unlinks empty stay.
6. **The read-back.** `git status` of the chosen paths alone: a path it no longer lists is `discarded`, one it lists is
   `left`, with `problem` saying why. When it fails after the index write, the answer is 500: "the discard ran, but what
   it changed could not be read: …; the package's Changes show it", never that nothing was discarded.

```ts
interface DiscardAnswer {
  discarded: string[];                    // sorted: the chosen paths that now read as in the last commit
  left: string[];                         // sorted: those that do not
  package: PackageState | null;           // null: the state could not be read after it, as when package.yaml was discarded
  problem?: string;                       // why a path is left, or why package is null; git's words
}
```

**What remains is stated, not closed.** A smudge filter is the author's own code: it runs as on any checkout and can
write wherever it likes. A program other than the UI, such as a terminal, can write a chosen file between the tag check
and the write; that write is lost, as the working folder's lock covers the UI's own writes only. A file mode changed
after the author looked is put back unseen: no bytes are lost.

## Deployed state

### The node at RAIL8_ADMIN_URL

The git half reads `GET /admin/packages` and `GET /admin/components` from the node at `RAIL8_ADMIN_URL`, with
`RAIL8_ADMIN_TOKEN`, as the mapping half does. `/admin/packages` gives what that node runs, and `/admin/components`
gives its environment. Every relation names that node, the host of `RAIL8_ADMIN_URL`. The pool's other nodes are in
"Every node of the pool".

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

A pool's nodes follow its records ("Several nodes" in `docs/DEPLOY-FROM-GIT.md`). Each node writes a row per package
it runs, and `GET /admin/pool/packages`, on any node, answers the pool's records and those rows for every node whose
lease holds. The git half reads that route from the node at `RAIL8_ADMIN_URL` beside `/admin/packages`, once per
request, and the deployed state gains `pool`:

```ts
type Pool =
  | { record: string | null; nodes: PoolNode[] }
  | { error: string };
type PoolNode = { node: string; follows: boolean; behind: string[] } & (
  | { relation: 'none' }
  | { relation: 'deployed' | 'behind' | 'ahead' | 'other'; commit: string; commits?: number;
      state: 'RUNNING' | 'FAILED'; deployedAt: string });
```

- **The record.** `record` is the commit of the pool's record of the package, matched by `repository`, or null when
  the pool records no such package.
- **Each node.** One entry per node, sorted by id. Its commit is related to `HEAD` as the node at `RAIL8_ADMIN_URL`'s
  is, with the same relations, each commit once per package. A node with no row for the repository is `none`. Its
  state reads "Failed" when it is `FAILED`.
- **Behind the record.** `follows` is false when the node's row differs from the record: another commit, a row with no
  record, or a record with no row. That node has not followed yet, or it is behind, and `behind` says why once a tick
  failed.
- **No pool route.** When the node answers the route 404, `pool` is absent. The UI keeps the single node at
  `RAIL8_ADMIN_URL`, named as it is.
- **No answer.** Any other way the route gives no answer is `error`, beside what the node runs.

The Packages screen, in the row's Deployed column, and a package's tab, as **Nodes of the pool** among its facts, show:

- one line per commit the nodes run: its status, its short id and how many of the nodes run it, as "Behind 1
  `a1b2c3d` on 1 of 3 nodes";
- the nodes that do not follow, as "Behind the pool's commit `c3d4e5f`: node-b", or "Running though the pool records
  no deploy of it" when there is no record;
- each such node's reasons, as "node-b: the commit could not be fetched…".

The deploy needs nothing here. Every deploy names the state it expects, the commit of the pool's record or `none`, and
the node refuses it, changing nothing, when the pool holds another ("Deploy").

## Deploy

The deploy dialog opens from the header, from **Commit and deploy**, or from **Deploy this commit…** in History. It
always checks first:

1. **Check.** `POST …/deploy {commit, check: true}`, with `commit` as 40 hex digits from the package's state.
   - The git half checks that the clone holds it, with `rev-parse --verify --end-of-options <commit>^{commit}`.
   - It sends `POST /admin/deploy?check=true` with `{repository, commit}`, the token, and `X-Rail8-Actor` set to the
     identity, `Name <email>`, or `ui` when git has none.
   - It answers as `replaces` what the node's answer names: the commit of the pool's record of the package, or `none`.
   - A header carries no character outside printable ASCII, so each such character becomes `?`, as in `Ji?? Nov?k
     <jiri@example.com>`. The actor is then cut to 128 characters.
2. **The answer.** The dialog shows the added, swapped and removed flows, or every problem. Problems end the dialog,
   and nothing has changed on the node.
3. **Deploy.** The author confirms. The browser sends `{commit, check: false, expect}`, with the commit and, as
   `expect`, the `replaces` the check answered.
   - The git half sends `POST /admin/deploy` with `{repository, commit, expect}`. When the pool no longer holds what
     `expect` names, the node refuses it, changing nothing, and the answer is 412, "the node's commit changed since the
     check".
   - Otherwise the dialog shows the node's answer: what was actually added, swapped and removed.

The check first is the screen's rule. The node's own order is the guarantee: every problem of a deploy is answered
before anything stops, and a deploy is refused when the pool no longer holds the state it expects.

In a deployed environment, the node takes only a commit reachable from its `RAIL8_GIT_REF` on the git host. The check
fetches the commit, proves that, and refuses it before anything changes.

**The node's client.** The git half calls the node with a client of its own in the development server, which sends the
body, the token and `X-Rail8-Actor`. It never goes through the `/admin` proxy, which passes no body.

**A lost answer.** A deploy the node does not answer in time is 504, "outcome unknown". The dialog then reads what the
node runs before it offers to try again.

**Going back.** Going back is a deploy of an earlier commit, from History, through the same dialog. The working
folder is not touched. Afterwards the package reads "Behind *n*", which is what it is.

Undeploy stays with the command line.

## Get a package

**Get a package…** on the Packages screen clones one repository, from an https or ssh address the author pastes, into
a new folder of the working folder named as the repository. As the author types, the dialog shows the folder, or why
the address is refused. A clone that is a package opens in its tab; one that is no package stays in place, listed
below the packages, and the dialog says why. There is no field for the folder or the branch: a node fetches
`RAIL8_GIT_BASE` + the folder's name, and the clone checks out the repository's default branch.

The new folder deploys only from a pool whose `RAIL8_GIT_BASE` holds a repository of that name with these commits: a
fork named `orders` is another repository.

**The address rule.** One rule, `cloneSourceOf` of `ui/src/packages/rules.ts`, is shared by the dialog, the route and
the fake. The first check that fails is the answer, each with a line of its own that says what to do, and no line
repeats the address or any part of it:

1. White space at both ends is trimmed, and a leading `git clone` is dropped. Any other white space is refused.
2. 1 to 1000 characters, each printable ASCII.
3. A leading `-` is refused, and so is `::` anywhere, the form that runs `git-remote-<transport>`.
4. Only lowercase `https://` and `ssh://` are taken, and the scp-like ssh form `[user@]host:path`. `http`, `git`,
   `file`, any other scheme, an uppercase one, and a local path are refused.
5. An https address holds no user information: the credential helper chooses the account. An ssh user is letters,
   digits, `.`, `_` and `-`, and holds no password.
6. A host is labels of letters, digits, `-` and `_`, not starting with `-`, or a dotted IPv4 address; no IPv6 literal.
   In the scp form a host of one letter is a drive letter, refused. A port is 1 to 65535.
7. A path is segments of `[A-Za-z0-9._~+-]`, with `%XX` in the URL forms. Once decoded, each byte is 0x20 to 0x7E and
   neither `/` nor `\`, so a control character and any byte of 0x80 or above are refused. No segment is `.` or `..`
   or starts with `-`.
8. The folder is the last segment, less one trailing `.git`. It must be a repository name, must not end in `.`, and
   must not be a Windows device name such as `con` or `lpt1`, on every platform.

git is given the address as it stands after step 1, so `origin` is what a terminal would store.

**The statuses,** in the order they are checked, after the session gate and the foreign-page refusal:

| Status | When |
| --- | --- |
| 503 | git is missing or older than 2.30 |
| 405 | any method but POST |
| 403 | `RAIL8_READ_ONLY=true` |
| 503 | nine writes of the working folder are under way |
| 409 | another clone is under way |
| 413 | a body over 1 MiB |
| 400 | a body that is no `{address}` of text, or the rule's line |
| 404 | no working folder |
| 409 | anything at the folder's name, before git runs and again at the move |
| 503 | the server is closing |
| 422 | git ended with an error, with git's words as `output` and a line on signing in; or the clone is over the size limit |
| 500 | git wrote more than 1 MiB on a stream, or a file operation of the server's own failed |
| 504 | git ran past 300 s |
| 201 | the folder is in place |

**The steps.**

1. The checks above only read. `lstat` of `<working folder>/<repository>` must find nothing, else 409.
2. The clone runs as one undoable of the runner: a server close refuses it before it starts, with nothing made.
3. It makes a hidden folder `.rail8-clone-*` of the working folder, mode 0700, which the list and the editors' walks
   skip. A failure, a timeout or a server close while git runs removes it whole, and nothing else.
4. git clones into it, in the runner's `clone` mode: one clone at a time, beside the one write, with the environment
   of a write plus `GIT_ALLOW_PROTOCOL=https:ssh`, which wins over every config level and also refuses an `insteadOf`
   that rewrites the address to `file://`. It runs as

   ```
   git -c core.hooksPath=/dev/null -c core.askPass= -c transfer.bundleURI=false -c filter.lfs.process=
       -c filter.lfs.smudge= -c filter.lfs.required=false -c core.protectHFS=true -c core.protectNTFS=true
       clone --quiet --no-recurse-submodules -- <address> <repository>
   ```

   Every `-c` comes before `clone`, so none is written into the new clone's config, and the address comes after `--`.
   A submodule stays an empty folder, and a node refuses such a package at its deploy check. LFS files keep their
   pointer bytes, which are what git stores and what a node deploys.
5. Every second, on the injected timer, the size of the hidden folder is looked at, by `lstat` only, hidden entries
   counted. Past 1 GiB of files or 20 000 entries git is stopped: 422. Once git has ended, it is looked at once more.
6. Under the working folder's lock, `moveIntoPlace` of `ui/server/workingFolder.ts` puts the clone in place: an
   exclusive `mkdir` claims the name and keeps its `dev` and `ino`, `lstat` must still give them just before the
   rename, and the rename replaces only that empty claim. An entry that appeared meanwhile is 409, and is never
   written into, replaced or removed. On Windows, where a rename never replaces a folder, it renames without the claim.
7. The emptied hidden folder is removed, and the new folder is read as the list reads it. The rename is the commit
   point: a failure after it is still a 201, with `problem`.

Getting a package runs no hook and no git-lfs. A filter the author's own config defines runs, as in a terminal.

**The close.** A server close refuses a clone that has not started, and stops git's process group: TERM, and KILL 2 s
later, even after git's leader has exited. The hidden folder is then removed. A clone whose git had ended goes on into
place, and the close waits for it. So the close is bounded by the KILL's 2 s and the removal of at most the size limit
plus one second's growth, or by the move, which waits for the write that holds the lock. During a clone, SIGINT and
SIGHUP are caught as during a commit's undo, and Vite's own SIGTERM closes the server, which closes the git half.

**Credentials** come as for every git call ("Remote"). When git cannot sign in, or does not know the host's key, the
answer says to run `git ls-remote` with the address in a terminal, where it can ask, and then try again.

**As in a terminal:**

- The address names where the author's git connects. The author's `insteadOf`, git's redirect rule, packfile URIs,
  proxies, and hosts on localhost or a private network behave as in a terminal. The address shown is no security
  boundary.
- The clone runs the author's git on the repository the address names, as `git clone` in their terminal does.
  Protection against flaws in git's own parsers is the author's patched git.
- Filters the author's config defines, other than LFS, run on checkout.
- The state read after the clone runs `git status` under the author's config, and may run a clean filter.
- After the clone, every git command in it follows the author's config, a relative `core.hooksPath` included.
- A hard crash can leave a hidden `.rail8-clone-*` folder, and, in the instant between the claim and the rename, an
  empty folder of the repository's name, which the list shows as "no clone of its own". The author deletes them.

## Dependencies

The Dependencies tab reads `package.yaml` and `package.lock` on disk. For each dependency it shows:

```ts
interface Dependency {
  name: string; version: string;                                   // from package.yaml
  pin: { repository: string; commit: string; content: string } | null;  // from package.lock; null: not pinned
  candidate: { folder: string; head: string | null } | null;      // the folder rail8 lock would pin; null: none, or several
  state: 'pinned' | 'unpinned' | 'moved' | 'changed' | 'missing';
  running?: { commit: string; content: string };                  // what the node resolved for it, when it runs the package
}
```

A dependency's candidate is the folder `rail8 lock` would pin: the one folder directly in the working folder whose
`package.yaml` has the dependency's name and version, the package's own folder aside. It is found by that rule, never
by the pin's repository, so the folder a lock would pin is the folder the author sees.

| State | Meaning |
| --- | --- |
| `pinned` | The candidate is the pinned repository, and its `HEAD` is the pinned commit. |
| `unpinned` | `package.lock` pins nothing for it yet. A lock would pin the candidate. |
| `moved` | The candidate is another folder, or its `HEAD` another commit, than the pin. A lock would pin it. |
| `changed` | The candidate's files differ from its `HEAD`, so a lock would refuse it. |
| `missing` | No folder holds that name and version, or several do. |

**Lock** is `POST …/lock {manifestTag, lockTag, dependencies: [{name, folder, head}]}`. It states what the author
saw: the tags of `package.yaml` and `package.lock`, and each dependency's candidate folder with its `HEAD`.

- It runs the Rail8 command line, `RAIL8_CLI`, by default the `bin/rail8` of the Rail8 checkout the UI runs from, as
  `lock <package folder> --deps <working folder>`. It goes through the same runner, with its own limits, under the
  working folder's lock.
- Any tag, candidate folder or head that differs is 412, checked before the command runs.
- It is refused (409) while a dependency has several candidates, since the command line could pin any of them.
- Its `problem:` lines come back as a 422.
- Its pin lines come back as `pins`.
- `rail8 lock` writes `package.lock` through a temporary file and a rename, so a lock stopped at its deadline leaves the
  old file whole. That change to the command line is built with this step.

The new `package.lock` then shows among the changes, compared by its pins, and is committed like any other change.
The content digest stays one implementation, the node's, and is never written again in TypeScript. The command line
needs JDK 25 and the built CLI jar, which the quickstart already requires. When it is missing, Lock says how to build
it.

## Used by

A dependency's files come from the commit a package's `package.lock` pins, so a change of a shared package reaches a
package that uses it only once that package is locked and deployed again. The Used by tab shows who still runs an
older commit.

Its rows come from the same read of `GET /admin/packages` as the deployed state ("The node at RAIL8_ADMIN_URL"): each
package the node runs lists its direct dependencies as its deploy resolved them, the pins of its `package.lock`, each
with its name, version, repository, commit and content digest. No other route is read. A pin is this package's when
its repository is this package's folder. The package state gains `usedBy`, and each row `pinnedBehind`:

```ts
type UsedBy =
  | { node: 'unreachable'; host: string; error: string }
  | { node: 'answered'; host: string; runs: string | null; consumers: Consumer[] };   // runs: the commit the node runs of it
interface Consumer {
  name: string; version: string; repository: string;               // the package that pins this one
  pin: { version: string; commit: string; content: string };
  commit: { at: string; subject: string } | null;                  // null: the clone lacks the pinned commit
  runs?: PinRelation;                                              // absent when the node runs no commit of it
  head?: PinRelation;                                              // absent with no HEAD
}
interface PinRelation { relation: 'same' | 'behind' | 'ahead' | 'other'; commits?: number }
```

- **The rows.** One per package the node runs that pins this one, sorted by name: its name and version, the version
  and commit it pins, and that commit's subject and age when the clone holds it.
- **The relations.** The pinned commit is related to the commit the node runs of this package and to `HEAD`, as
  the deployed state relates a commit, each pinned commit once: "Same as running" or "At HEAD", "Behind *n*", "Ahead
  *n*", or "Other line" when it is not in that commit's history or the clone lacks either.
- **The mark.** `pinnedBehind` counts the rows behind what the node runs, and is absent when there are none. The
  Packages screen shows it as "Pinned behind by *n*".
- **No answer.** When the node does not answer, the tab says so and lists nothing.

## Remote

- **Fetch** is `git fetch -- <the branch's remote>`. Ahead and behind are as of the last fetch, and the screen shows its
  time. The remote and the remote's branch are those of the branch's upstream in the author's config, read with
  `for-each-ref`. When git fails, as when it cannot sign in, the answer is 422 with git's lines.
- **What every fetch, pull and push checks first.** It is refused with 409 while the clone is busy, or `HEAD` is
  detached. A pull or a push is refused with 412 when its `branch`, `head` or `upstream` is no longer what the clone
  has. Then each is refused with 409 when the branch has no upstream, or its remote-tracking branch is gone.
- **Pull** takes `{branch, head, upstream}`. It is a fetch, then, under the working folder's lock, a fast-forward to
  what the fetch brought.
  - `branch`, `head` and `upstream` must still be what they were.
  - It resolves `@{upstream}` once, to a commit id, and runs `git merge --ff-only --no-overwrite-ignore
    --end-of-options <id>`.
  - When `HEAD` holds that commit already, nothing is merged and `changed` is empty. The server refuses it when the
    branch has diverged, found with `merge-base --is-ancestor` before the merge. When the merge itself refuses, as
    when a local change or an ignored file would be overwritten, the answer is 409 with git's lines.
  - `changed` lists the paths that differ between the two commits, read with `diff-tree`. When they cannot be read
    after the merge, `changed` is null with the reason, and the screen reloads every clean tab of the package.
  - A failed fetch is 422 with git's lines, as for Fetch, and nothing is merged.
  - The screen refuses it while a tab of the package holds unsaved changes.
  - Afterwards, the clean tabs of the files it changed are reloaded ("The shell's part").
- **Push** takes `{branch, head, upstream}`. It is `git push --porcelain -- <remote> <head>:refs/heads/<upstream
  branch>`: the commit it checked, never the name `HEAD`. It never forces.
  - `branch`, `head` and `upstream` must still be what they were.
  - Without an upstream, it is refused, naming `git push -u` for a terminal.
  - When the remote rejects it (a protected branch, a push that is not a fast-forward), the answer is a 409 with git's
    lines. Any other failure of git, as when it cannot sign in, is 422 with git's lines.
  - `pushed` names the remote's branch, the commit its remote-tracking branch named before, and the commit pushed.
- **Credentials** come from what the author's git and ssh give without a terminal: credential helpers, `.netrc`,
  `http.extraHeader`, the ssh agent and keys, and `ssh_config`. No terminal prompt opens. A helper's own window (a
  keychain, a browser) may open, and the git call waits for it up to its deadline. An unknown ssh host key fails ("Host
  key verification failed"). BatchMode, a host-key policy and agent forwarding stay the author's `ssh_config` and
  `core.sshCommand`, as for every other git call.

The UI leaves these to the git host:

- creating the remote repository;
- branch protection;
- review and the merge of pull requests;
- release branches, and moving the branch a pool's `RAIL8_GIT_REF` names;
- tags and releases;
- who may push.

It leaves these to a terminal:

- branches, merges other than a fast-forward, rebases and conflicts;
- stash, reset and amend;
- remotes and submodules.

## New package

`POST /git/packages {repository, name}` makes a new package in the working folder, under the working folder's lock:

1. **The names.** The repository name follows the rule above (400), and the package name follows `package.yaml`'s
   schema (`^[a-z][a-z0-9-]*$`, at most 64 characters; 422).
2. **The files.** In a hidden temporary folder `.rail8-new-*` of the working folder, it writes:
   - `package.yaml`: the name, version `0.1.0`, no dependencies;
   - `package.lock`, as `rail8 lock` writes it with no dependency;
   - `.gitattributes`, holding `* -text`.
3. **The repository.** In that folder it runs `git init -b main`, `git add -A` and a first commit, "Create package
   *name*". The commit is refused, like any other commit, without a git identity.
4. **Into place.** The folder is renamed to the repository's name, with the helper Get a package uses,
   `moveIntoPlace` of `ui/server/workingFolder.ts`: an exclusive `mkdir` claims the name and keeps its `dev` and `ino`,
   `lstat` must still give them just before the rename, and the rename replaces only that empty claim. It is refused
   (409) when that name exists. A failure at any point deletes the temporary folder, which is the server's own, and
   nothing else. The rename is the commit point: a failure after it is still a 201, with `problem`.

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
  again from disk, with `useWorkingFile(path, dirty, reload)`, in `ui/src/workingFiles.tsx`. `useWorkingFiles()`
  answers `{files(), reload(paths)}`:
  - `files()` gives each open file by its path as `{path, dirty, show()}`. It asks each editor's `dirty` at the call;
    a file two tabs hold is dirty when either is, and `show()` selects the tab that has it unsaved, or else the first.
  - `reload(paths)` reloads the tabs of those files: it reads each tab's dirty state just before, and leaves a dirty
    tab as it is. A control that holds text that is no value yet, such as half-typed JSON in an endpoint's raw
    settings, counts as an unsaved change. While a reload reads the file, the flow's and the endpoint's Edit waits,
    so a working copy always starts from the version whose `ETag` its save sends. The mapping studio, which edits
    without Edit, keeps an edit made while its reload reads the file and drops what it read; its save then meets the
    conflict.

  Opening a tab that is already open only selects it, so a reload is the only way a tab shows the new file. The commit
  dialog and the pull read this registry. Its dirty state is the one the editor's screen gives the shell's question
  for unsaved changes through `unsaved`, never a second record of it. It holds no tags. Outside the shell, registering
  does nothing.

## Limits

| Limit | Value |
| --- | --- |
| Packages listed | 100 |
| Changed paths of one package, of one `…/changes`, and paths of one commit or one discard | 1000 |
| The walk of a package's artifacts, on disk or at a revision | the working folder's: 1000 files, depth 16 |
| One version of one file, read | 1 MiB |
| A write's body | 1 MiB |
| A commit message | 8 KiB |
| History | 50 commits a page, at most 100 |
| git processes at once | 4 |
| Calls waiting | 8 writes, 32 reads |
| A git read | 10 s, 4 MiB on each stream |
| A commit, with its hooks | 60 s, 1 MiB on each stream |
| A discard's index write and its file write | 60 s each, 1 MiB on each stream |
| Taking a stopped commit's added paths out of the index | at most six git calls, each 10 s and 1 MiB on each stream |
| Fetch, pull, push | 120 s each, 1 MiB on each stream |
| A clone of Get a package | 300 s, 1 MiB on each stream |
| A clone on disk, sampled each second and once more after git | 1 GiB of files, 20 000 entries |
| Clones at once | 1, and 1 waiting |
| The address of Get a package | 1000 characters |
| `rail8 lock` | 120 s, 1 MiB on each stream |
| From TERM to KILL | 2 s |
| After git exits, for the end of its output while a process it started holds the pipes open | 100 ms, within the deadline |
| The node, for a read | 10 s, 8 MiB |
| The node, for a deploy or a check | 5 min, the wait of `rail8 deploy` |
| What git, a hook or the node says, in an answer | 40 lines of 200 characters |
| A line diff | 5000 lines a side |

## Out

- Editing on a shared server, and writes to git there, until sign-in exists. A server for the UI outside development
  answers the reads and refuses every write, as `RAIL8_READ_ONLY=true` does.
- Branches, merges other than a fast-forward, rebases, conflicts, stash, reset, amend, and anything that rewrites
  history.
- An undo of a discard. What a discard drops is gone at once.
- Remotes, submodules and worktrees.
- A package at a folder other than its clone's root.
- Choosing hunks, and the index as such. The author chooses whole files.
- Undeploy, which stays with the command line.
- Settings of the git host.

## Tests

- **The runner.** Its suite runs on a fake process for:
  - every argument vector, with user values only after `--`, after `--end-of-options` or on stdin, and each path as
    `:(literal)<path>`;
  - the environment, with inherited `GIT_*` and `RAIL8_*` removed, and `LC_ALL` and `GIT_OPTIONAL_LOCKS` set for reads
    only;
  - output caps on both streams;
  - the queue's limits;
  - an undo: its calls admitted while the runner closes, at most six of them, and SIGINT caught until it ends, a second
    one ignored;
  - the formatter's redaction of `https://user:secret@` and of the token's value.

  It also runs on real child processes, with the deadline on the fake timer, never a sleep:
  - a child that never exits, stopped at its deadline;
  - a child that floods both streams;
  - a child whose descendant keeps a pipe open;
  - a server shut down while a child runs;
  - a server shut down while a commit runs, whose undo still runs before the close ends.
- **The git half.** `PackagesApi` (`list`, `get`, `file`, `changes`, `artifacts`, `log`, `create`, `commit`, `discard`,
  `deploy`, `lock`, `fetch`, `pull`, `push`) has two implementations:
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
  - **Deployed state.** Each relation. A pool of three nodes, one current, one behind and one failed; a node with no
    row and a row with no record; a node that answers no pool route; and a pool route that fails.
  - **Commit.**
    - A commit of chosen paths that leaves other staged paths staged.
    - A chosen path staged with other content (409).
    - A stale `branch`, `head` or `tag`, and a path no longer among the changes (412).
    - A hook that refuses, with only the request's own additions taken out of the index; one that rewrites an added
      file first, which stays staged while the other added file leaves, with the command that unstages it, on a
      branch with commits and on one without; and a terminal that stages an added file before the undo, which stays
      staged, as the error says.
    - On the real git half only: that command run in a terminal, and a server closed right after the add.
    - git stopped by its post-commit hook after it committed: the error says `HEAD` moved.
    - A renamed file named without its old path, with an old path git no longer pairs it with, and with its old path
      as not on disk while it is on disk (412).
    - A commit made in a terminal just before the commit starts, one made just after it, and an amend just after it.
    - No identity.
    - The read-back after a hook rewrote a file, after a hook staged another path, and after a hook changed a chosen
      file on disk without staging it.
    - A save that waits for a commit and is then refused by its recheck.
    - A detached HEAD, a merge in progress and a conflict (409).
  - **Discard.**
    - A modified, an added and a deleted file in one discard, each read back as in the last commit, the files not
      chosen left as they were; a staged change, and a rename with both its paths.
    - A stale `head`, `tag` or `index`, a path no longer among the changes, a rename named without its old path, and
      its old path named as not on disk while it is on disk (412).
    - A path outside the package, a `.git` segment, and a changed link out of the package (403), and the hostile values.
    - A save that waits for a discard and is refused by its recheck, a discard that waits for a save, and one that
      waits for a commit.
    - A path whose index `differs`, a busy clone, and a detached `HEAD`, which is allowed.
    - A file that replaced a folder, a folder that replaced a file, and a staged file whose name the last commit holds
      as a folder (409).
    - A branch with no commit, read-only, a foreign page, a malformed body and no path.
    - On the real git half only: a symbolic link on disk, a dangling one, a submodule, a skip-worktree entry, an
      intent-to-add entry and an alias in letter case (409); a link to a folder in a path's way and a folder of only
      ignored files (409); a link, an executable bit and a crlf file brought back, with no hook run; and a required
      smudge filter that fails, which leaves its path and the paths after it, with git's words.
  - **Deploy.**
    - A check, then a deploy, sending 40 hex digits and the actor, with a name outside ASCII, such as `Jiří`.
    - A deploy refused by the node, its problems passed on.
    - A deploy refused because the node's commit changed since the check (412).
    - A deploy the node does not answer (504).
    - The node refusing the token (502).
  - **Lock.** A lock; a lock refused because a dependency's `HEAD` moved, or another folder became its candidate
    (412); and an unpinned dependency with two candidates (`missing`).
  - **Remote.**
    - A fast-forward pull, a diverged one, one that would overwrite a change, and one that would overwrite an ignored
      file.
    - A pull or a push after the branch was switched at the same commit (412).
    - A rejected push, and a push without an upstream.
  - **New package.** A new package, one over an existing folder, and one without a git identity.
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
  - A discard of one of two changed files leaves the other changed; a file unsaved in an open tab is named first, and
    its tab keeps its edits while a clean tab reads its file again; one file is confirmed with a tick box and several
    by typing their count, and each is dropped when the listed files change.
- **A discard whose git fails.** On a runner over real git that answers one call itself: an index write that fails
  while another git holds `index.lock` (409, nothing changed) and one that fails without it (500); a file write that
  fails, after which nothing is unlinked and the paths are left; and a read-back that fails after the writes (500,
  never "nothing was discarded").
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
5. **The working folder's lock.** The lock, made once by `moduleServers` and handed to every half on `DevServer`, with
   a test that two halves share it. Also the `If-Match` recheck under it in the saves of the flow screen, the endpoint
   form and the mapping studio.
6. **Commit.** It builds:
   - `…/commit`, the dialog, the identity and the read-back;
   - the shell's working files, fed by the flow screen, the endpoint form and the mapping studio.
7. **Deploy and going back.** It builds:
   - `…/deploy` with the node's own client, and the dialog with its check;
   - **Commit and deploy**, and **Deploy this commit…** in History;
   - the `@stack` scenario.

   The common case then works end to end: edit, commit, deploy.
8. **Get a package.** `POST /git/clone`, the runner's `clone` mode, `moveIntoPlace`, and the dialog on the Packages
   screen. The owner pastes the address of an existing package and gets its tab.
9. **Dependencies and Lock.** The Dependencies tab, `…/lock` through `RAIL8_CLI`, and the write of `package.lock`
   through a temporary file and a rename in `rail8 lock`.
10. **Remote.** Fetch, the fast-forward pull with the clean tabs reloaded, push, and the two-window test.
11. **New package.** `POST /git/packages` and its dialog.
12. **What each node runs, on the node.** The rows of what each node runs and `GET /admin/pool/packages`. It is designed
    first and depends on nothing in the UI, so its design may start at any time.
13. **Every node in the UI.** `GET /admin/pool/packages` read beside `/admin/packages`, one entry per node in the
    deployed state, and the nodes of the pool on the Packages screen and in a package's tab.
14. **Discard.** `…/discard` with its two plumbing writes, the Changes table's tick boxes, and **Discard changes…**
    with its dialog.
