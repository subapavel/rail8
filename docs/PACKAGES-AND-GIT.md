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
folder, sorted by repository. Each row shows:

- the repository;
- the package's name and version;
- the branch;
- the last commit: its subject, short id and age;
- ahead and behind its upstream, as of the last fetch;
- the number of changed files;
- the deployed state on the node: Deployed, Deployed with changes since, Behind *n*, Other commit, Failed, or Never
  deployed.

**New package** sits above the table.

Some folders are not packages:

- one with no clone of its own;
- a worktree;
- one without `package.yaml`.

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
  - Choosing a commit shows the files it changed.
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
- It holds `package.yaml` at `RAIL8_GIT_PATH`, the setting the node reads, the root by default.

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

The binary is the author's tool, not a dependency Rail8 ships.

Every git process is started through one runner:

- **No shell.** It uses `spawn` with an argument array, `--no-pager` and `-C <the clone's real path>`.
- **User values stay out of options.** A path comes after `--`, a revision after `--end-of-options`, and a commit
  message on stdin.
- **The environment** is the development server's, with every `GIT_*` variable removed, then:
  - `GIT_TERMINAL_PROMPT=0`;
  - `GCM_INTERACTIVE=never`;
  - `SSH_ASKPASS_REQUIRE=never`;
  - `GIT_LITERAL_PATHSPECS=1`, so a `*` in a file name is no pattern;
  - `LC_ALL=C`;
  - for reads, `GIT_OPTIONAL_LOCKS=0`, so a read never blocks the author's own git in a terminal.
- **No prompt can hang it.** Each process runs in a session of its own, with no terminal, so a credential, ssh or
  signing prompt fails at once instead of waiting.
- **Machine formats only.** Only these formats are parsed: `status --porcelain=v2 -z`, `log -z --format`,
  `push --porcelain`, `rev-list --count` and `cat-file --batch`.
- **Finite in time and size.** Each call has a deadline on the injected timer and caps on its output ("Limits"). At its
  deadline, its whole process group is killed.
- **One formatter for everything it says.** Every text that reaches an answer, from git, a hook or the node, passes
  through one formatter. It replaces the user information in a URL (`https://user:secret@host`) with `***`, and keeps
  at most 40 lines of 200 characters. The development server never asks for, holds or passes on git credentials.

### The commands it runs

The git half runs only these commands:

- **Reads:** `status`, `log`, `cat-file`, `ls-files`, `ls-tree`, `hash-object` (without `-w`), `rev-parse`,
  `rev-list`, `merge-base` and `var`.
- **Writes:**
  - `init` (a new package);
  - `add`, of the new files a commit takes;
  - `rm --cached`, which only undoes that `add` when the commit fails;
  - `commit --only`;
  - `fetch`;
  - `merge --ff-only`, against the upstream;
  - `push`, of the branch to its upstream.

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

## The routes

The routes live in the `server.ts` of a new UI module, `ui/src/packages/`, under `/git`.

Every route takes only the UI's own pages, by the same rule as the other routes of the development server
(`Sec-Fetch-Site: same-origin`, and an `Origin` from the fixed list). A foreign page is refused with 403.

Every write:

- is a `POST` with a JSON body of at most 1 MiB;
- is refused with 403 under `RAIL8_READ_ONLY=true`, except a deploy check;
- answers, besides its own result, the package's state after it, as `GET /git/packages/{repository}` answers it, so
  that the screen redraws from one answer.

| Route | Answer |
| --- | --- |
| `GET /git/packages` | `{configured, writable, packages: PackageRow[], others: {folder, reason}[], truncated}` |
| `GET /git/packages/{repository}` | `PackageState` |
| `GET /git/packages/{repository}/file?rev=&path=` | One version of one file of the package: `{text}`, or `{binary: true, size}`, with its `ETag` |
| `GET /git/packages/{repository}/log?before=&limit=` | `{items: Commit[], next}`: the first-parent history of `HEAD`, or of `before`, 50 to a page by default and at most 100 |
| `POST /git/packages` `{repository, name}` | 201 `PackageState`: a new package ("New package") |
| `POST /git/packages/{repository}/commit` `{message, head, paths}` | `{commit, output, package}` ("Commit") |
| `POST /git/packages/{repository}/deploy` `{commit, check}` | `{deploy, package}` ("Deploy") |
| `POST /git/packages/{repository}/lock` `{lockTag}` | `{pins, package}` ("Dependencies") |
| `POST /git/packages/{repository}/fetch` `{}` | `{package}` ("Remote") |
| `POST /git/packages/{repository}/pull` `{head}` | `{head, changed, package}` ("Remote") |
| `POST /git/packages/{repository}/push` `{head}` | `{pushed: {ref, from, to}, package}` ("Remote") |

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
interface Commit { id: string; subject: string; author: string; at: string; deployed?: true }

/** One package, as its tab shows it. */
interface PackageState extends PackageRow {
  identity: { name: string; email: string } | null;  // git's author identity in this clone; null: a commit is refused
  changes: Change[];                      // sorted by path
  artifacts: Artifact[];                  // sorted by path
  dependencies: Dependency[];             // sorted by name
  lockTag: string | null;                 // the tag of package.lock on disk; null when there is none
  truncated: boolean;
}
interface Change {
  path: string;                           // relative to the working folder, with '/', as the editors' routes name it
  kind: Kind;
  status: 'added' | 'modified' | 'deleted' | 'renamed';
  renamedFrom?: string;
  tag: string | null;                     // the editors' ETag of the bytes on disk; null when deleted
}
type Kind = 'flow' | 'endpoint' | 'mapping' | 'test' | 'package' | 'lock' | 'folder' | 'file';
interface Artifact { path: string; kind: Kind; id?: string; change?: Change['status']; usedBy?: string[] }
```

`rev` is `work` (the file on disk), `HEAD`, or a commit of 40 hex digits that the clone holds. A file is read up to
1 MiB. A path outside the package's folder is 404, as is a revision the clone lacks.

A file's kind comes from its top-level `kind` key for YAML, or from its name for `*.mapping.json`, `package.yaml`,
`package.lock` and `folder.yaml`. Every other file is `file`. Its `usedBy` lists the artifacts that name its path from
the package's root.

Every refusal is `{error, problems?, output?}`, with the status:

| Status | When |
| --- | --- |
| 400 | A malformed body, repository name, path or revision. A message that is blank, over 8 KiB or holds a NUL. More than 1000 paths. |
| 403 | A foreign page. Any write under `RAIL8_READ_ONLY=true` except a deploy check. |
| 404 | No working folder, no such package, a path outside it, or a revision the clone lacks. |
| 409 | The clone is busy. A detached HEAD for a commit, pull or push. No upstream for a fetch, pull or push. A pull that is not a fast-forward, or that would overwrite a change. A push the remote rejects. A new package over a folder that exists. |
| 412 | `head`, a `tag` or `lockTag` differs from what is on disk now. Nothing was done. |
| 413 | A body or a file over its limit. |
| 422 | No git identity. Nothing to commit. A hook refused the commit (its `output`). The problems `rail8 lock` printed. A package name the schema refuses. |
| node's | The node's own 400, 409, 422 or 503 for a deploy, passed on with its `error` and `problems`. |
| 502 | The node does not answer. |
| 503 | git, or the `rail8` command for a lock, is missing. |
| 504 | git, the node or `rail8 lock` took longer than its limit. |

## State against git

A package's state comes from these reads:

- **The status.** One `git status --porcelain=v2 --branch -z --untracked-files=all -- <package folder>` gives the
  branch, `HEAD`, the upstream with ahead and behind, and every changed path.
- **The last commit's subject.** `log -1`.
- **An operation left open.** The state files git leaves in `.git`: `MERGE_HEAD`, `rebase-merge`, `rebase-apply`,
  `CHERRY_PICK_HEAD`, `REVERT_HEAD` and `BISECT_LOG`.
- **The last fetch.** `fetchedAt` is the time `FETCH_HEAD` was written. The git half never fetches by itself.
- **A change's tag.** The editors' `ETag` of the bytes on disk (`etagOf`), so a tag means the same to the Changes view,
  the flow screen, the endpoint form and the mapping studio.

The temporary files of a save (`.<name>.<uuid>.tmp`) are never listed.

### Readable changes

A change is shown by comparing two versions of the file. The two versions are read through `…/file`, for example
`HEAD` and `work` for what changed since the last commit, or the deployed commit and `work` for what changed since
what runs. The comparison runs in the browser, in the module that owns the kind ("The shell's part"). Each comparison
is a pure function with a file of cases.

**A flow.** Both versions are read as values, with the flow screen's reader, so comments, quoting and key order never
count. When the values are equal and the bytes are not, the change reads "Only comments or formatting changed."

- *Steps are matched by name.* Each list of steps (the flow's own, and each process's) is matched by the step's
  effective name: its `name`, else its type, else the process it calls. Names are unique in each list, as the node
  requires, so the match is exact. Processes are matched by name, endpoints by id.
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

The commit dialog opens from Changes. It lists every change of the package, ticked, with:

- a message, required;
- "Committing as *name* <*email*>", the identity git gives in this clone (`git var GIT_AUTHOR_IDENT`);
- the paths that an open tab holds with unsaved changes. Each of these is unticked, says "unsaved in an open tab", and
  offers **Show tab**. The author may still tick it, to commit the version saved on disk.

The dialog's buttons are **Commit**, and **Commit and deploy**, which goes on to the deploy dialog for the new commit.

`POST …/commit` takes `{message, head, paths: [{path, tag}]}`:

1. **Refusals.** It is refused when:
   - the clone is busy, or `HEAD` is detached;
   - git has no identity (422, naming `git config user.name` and `git config user.email`);
   - a path is not among the package's changes.
2. **What the author saw.** `head` must still be `HEAD`, and each path's `tag` must still be the tag of its bytes on
   disk (`null` for a deleted file). Both are compared once the write's turn has come. When either differs, the answer
   is 412 and nothing is done.
3. **The commit.** `git add -- <the chosen new files>`, then `git commit --only -F - -- <paths>`, with the message on
   stdin.
   - Only the chosen paths change. A renamed file is chosen with both its paths.
   - Whatever the author staged on other paths stays staged.
   - The author's hooks and signing run, as in a terminal.
   - When the commit fails, the new files it added are taken out of the index again (`rm --cached`), and the hook's
     words come back as `output`.
4. **The read-back.** The new commit's tree is read back for the chosen paths and compared with the blob of each
   path's bytes as they were checked (`hash-object`). Its parent is compared with `head`. A difference is never undone
   but always reported:
   - a hook that rewrote a file;
   - a save made in another tab during the commit;
   - a commit made in a terminal in between.

**Before sign-in, the author is whoever git says on this machine**, exactly as for a commit made in a terminal. The UI
never writes git config and takes no identity typed into it.

**Open tabs.** A commit changes no file, so every open tab keeps its working copy, and its `ETag` stays valid. A tab's
unsaved changes stay in the tab, uncommitted, until it saves them and they are committed later.

## Deployed state

### Today: the node at RAIL8_ADMIN_URL

The git half reads `GET /admin/packages` and `GET /admin/components` from the node at `RAIL8_ADMIN_URL`, with
`RAIL8_ADMIN_TOKEN`, as the mapping half does. `/admin/packages` gives the record, and `/admin/components` gives the
node's environment.

The node's record of the package is matched by `repository`. Its commit is related to `HEAD` with
`cat-file -e <commit>^{commit}`, `merge-base --is-ancestor` in both directions, and `rev-list --count`:

```ts
type Deployed =
  | { node: 'unreachable'; error: string }
  | { node: 'answered'; relation: 'never' }
  | { node: 'answered'; relation: 'deployed' | 'behind' | 'ahead' | 'other'; commit: string; commits?: number;
      state: 'RUNNING' | 'FAILED'; problems: string[]; deployedAt: string };
```

| Relation | Meaning | Reads |
| --- | --- | --- |
| `deployed` | The node runs `HEAD`. | "Deployed", or "Deployed, with changes since" while files differ from `HEAD` |
| `behind` | The node runs an ancestor of `HEAD`, `commits` back. | "Behind *n*" |
| `ahead` | `HEAD` is an ancestor of what runs, `commits` back. | "Ahead *n*": pull |
| `other` | The commit is not in `HEAD`'s history, or the clone lacks it. | "Other commit" |
| `never` | No record names this repository. | "Never deployed" |

A record listed `FAILED` reads "Failed", with its problems. Uncommitted changes are never deployed, and the tab says
so while there are any.

### Every node of the pool

A pool's nodes follow its records ("Several nodes" in `docs/DEPLOY-FROM-GIT.md`), but no admin route says what each
node runs. A node keeps that only in memory, and shows it in `/health` as `BEHIND`. Showing it per node takes a change
on the node, built as a step of its own and designed first:

- Each node writes, in Postgres, a row per package it runs: the repository, the commit, its state, and why it is
  behind. It writes the row when it installs or undeploys a package, and when a tick fails to install one.
- `GET /admin/pool/packages` answers those rows for every node whose lease holds.

The deployed state then gains one entry per node, with the same relations. Until then, it shows the node at
`RAIL8_ADMIN_URL`, and names it.

## Deploy

The deploy dialog opens from the header, from **Commit and deploy**, or from **Deploy this commit…** in History. It
always checks first:

1. **Check.** `POST …/deploy {commit, check: true}`.
   - The git half resolves `commit` (`HEAD`, or 40 hex digits) with
     `rev-parse --verify --end-of-options <commit>^{commit}` in the clone.
   - It sends `POST /admin/deploy?check=true` with `{repository, commit}`, the token, and `X-Rail8-Actor` set to the
     identity, `Name <email>`, at most 128 characters, or `ui` when git has none.
2. **The answer.** The dialog shows the added, swapped and removed flows, or every problem. Problems end the dialog,
   and nothing has changed on the node.
3. **Deploy.** The author confirms. The git half sends the same request without `check`, and the dialog shows the
   node's answer.

The check first is the screen's rule. The node's own order is the guarantee: every problem of a deploy is answered
before anything stops. In a deployed environment (the node's environment is neither `dev` nor `ci`), the dialog warns
when the commit is not in the history of the branch's upstream, "push first", and the node's answer decides.

**Going back.** Going back is a deploy of an earlier commit, from History, through the same dialog. The working
folder is not touched. Afterwards the package reads "Behind *n*", which is what it is.

Undeploy stays with the command line.

## Dependencies

The Dependencies tab reads `package.yaml` and `package.lock` on disk. For each dependency it shows:

```ts
interface Dependency {
  name: string; version: string;                                   // from package.yaml
  pin: { repository: string; commit: string; content: string } | null;  // from package.lock; null: not pinned
  clone: 'pinned' | 'newer' | 'changed' | 'missing';               // the clone pin.repository names, against the pin
  running?: { commit: string; content: string };                  // what the node resolved for it, when it runs the package
}
```

| State | Meaning |
| --- | --- |
| `pinned` | The clone's `HEAD` is the pinned commit. |
| `newer` | `HEAD` is a descendant of the pin. |
| `changed` | The clone's files differ from its `HEAD`, so a lock would refuse it. |
| `missing` | No clone of that name is in the working folder. |

**Lock** is `POST …/lock {lockTag}`. It runs the Rail8 command line, `RAIL8_CLI`, by default the `bin/rail8` of the
Rail8 checkout the UI runs from, as `lock <package folder> --deps <working folder>`, through the same runner, with
its own limits.

- A different `lockTag` is 412.
- Its `problem:` lines come back as a 422.
- Its pin lines come back as `pins`.

The new `package.lock` then shows among the changes, compared by its pins, and is committed like any other change.
The content digest stays one implementation, the node's, and is never written again in TypeScript. The command line
needs JDK 25 and the built CLI jar, which the quickstart already requires. When it is missing, Lock says how to build
it.

## Remote

- **Fetch** is `git fetch -- <the branch's remote>`. Ahead and behind are as of the last fetch, and the screen shows its
  time.
- **Pull** is a fetch, then `git merge --ff-only --end-of-options @{upstream}`.
  - The server refuses it when the branch has diverged, or when git says a local change would be overwritten.
  - The screen refuses it while a tab of the package holds unsaved changes.
  - After a pull, the open, clean tabs of the files it changed are opened again, so they show the new files.
  - `head` must still be `HEAD`.
- **Push** is `git push --porcelain -- <remote> HEAD:<the upstream branch>`. It never forces.
  - Without an upstream, it is refused, naming `git push -u` for a terminal.
  - When the remote rejects it (a protected branch, a push that is not a fast-forward), the answer is a 409 with git's
    lines.
- **Credentials** are the author's credential helper or ssh agent. Nothing can prompt, so a helper that must ask fails
  and says so.

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
- remotes, clones and submodules.

## New package

`POST /git/packages {repository, name}` makes a new package in the working folder:

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
  interface ArtifactKind {
    kind: 'flow' | 'endpoint' | 'mapping';
    /** The tab the module's own list opens for the file at `path`. */
    open(path: string): Screen;
    /** The readable change between two versions of the file at `path`; null: no such version on that side. */
    Change: ComponentType<{ path: string; before: string | null; after: string | null }>;
  }
  ```

- **Working files.** An editor registers the file its tab holds and whether that file has unsaved changes, with
  `useWorkingFile(path, dirty)`. `useWorkingFiles()` answers the open files with their state, and
  `reopen(paths)` opens again the clean tabs of those files. The commit dialog, the pull and the shell's own question
  for unsaved changes read this one registry.

## Limits

| Limit | Value |
| --- | --- |
| Packages listed | 100 |
| Changed paths of one package, and paths of one commit | 1000 |
| The walk of a package's artifacts | the working folder's: 1000 files, depth 16 |
| One version of one file, read | 1 MiB |
| A write's body | 1 MiB |
| A commit message | 8 KiB |
| History | 50 commits a page, at most 100 |
| git processes at once | 4 |
| A git read | 10 s, 4 MiB of output |
| A commit, with its hooks | 60 s |
| Fetch, pull, push | 120 s each |
| `rail8 lock` | 120 s, 1 MiB of output |
| The node, for a read | 10 s, 8 MiB |
| The node, for a deploy or a check | 5 min, the wait of `rail8 deploy` |
| What git, a hook or the node says, in an answer | 40 lines of 200 characters |
| A line diff | 5000 lines a side |

## Out

- Editing on a shared server, and writes to git there, until sign-in exists. A server for the UI outside development
  answers the reads and refuses every write, as `RAIL8_READ_ONLY=true` does.
- Branches, merges other than a fast-forward, rebases, conflicts, stash, reset, amend, and anything that rewrites
  history.
- Cloning, remotes, submodules and worktrees.
- Choosing hunks, and the index as such. The author chooses whole files.
- Undeploy, which stays with the command line.
- Settings of the git host.

## Tests

- **The runner.** Its suite runs on a fake process:
  - every argument vector, with user values only after `--`, after `--end-of-options` or on stdin;
  - the environment, with inherited `GIT_*` removed;
  - a process killed at its deadline on a fake timer;
  - output caps;
  - the formatter's redaction of `https://user:secret@`.
- **The git half.** `PackagesApi` (`list`, `get`, `file`, `log`, `create`, `commit`, `deploy`, `lock`, `fetch`,
  `pull`, `push`) has two implementations:
  - `httpPackagesApi`, over the routes, with real git in temporary folders, a bare `file://` upstream, a fake node
    (`/admin/packages`, `/admin/components`, `/admin/deploy` with scripted answers) and a fake lock;
  - `fakePackagesApi`, in memory: commits as maps of path to bytes, the files on disk, an upstream, busy states, the
    identity and a fake node.

  One contract suite runs on both, seeded through one builder, and asserts only through the API. Its cases:
  - the list, with a folder that is no package, a worktree and a folder inside another repository;
  - changes after an edit, an add, a delete and a rename;
  - a file at `work`, at `HEAD` and at a commit;
  - each relation of the deployed state;
  - a commit of chosen paths that leaves other staged paths staged;
  - a stale `head` or `tag` (412);
  - a hook that refuses, with the index restored;
  - no identity;
  - the read-back after a hook rewrote a file;
  - a detached HEAD, a merge in progress and a conflict (409);
  - a check, then a deploy, sending 40 hex digits and the actor;
  - a deploy refused by the node, its problems passed on;
  - a lock;
  - a fast-forward pull, a diverged one, and one that would overwrite a change;
  - a rejected push, and a push without an upstream;
  - a new package, and one over an existing folder;
  - every write refused under `RAIL8_READ_ONLY=true`, and every route refused for a foreign page;
  - `-x`, `:(glob)*` and `..` as a path, a revision or a name.
- **The readable changes.** Each comparison (`flowChanges`, `mappingChanges`, `endpointChanges` and the package
  files') has a file of cases: a step added, removed, moved and renamed, a setting changed, a change of formatting
  only, a field remapped, boxes moved, a UDF changed, and an overlay changed.
- **The screens.** Browser tests run on `fakePackagesApi`. One `@stack` scenario edits a flow, commits it, deploys the
  commit to the quickstart's node, and sends a message through the new flow.

## What each step builds

1. **The Packages screen, read-only.** The runner, the rule for which folders are packages, `GET /git/packages`, the
   deployed state from the node at `RAIL8_ADMIN_URL`, the fake and the contract suite. The owner sees each package
   with its branch, last commit, changed files, ahead and behind, and whether the node runs it.
2. **A package's tab.** `GET /git/packages/{repository}` and `…/file`, the Artifacts, Changes and History tabs, the
   shell's artifact kinds opening the existing editors, and the Package column of the three lists. A change of a flow,
   an endpoint or a mapping reads only "changed" until its readable change is built; any other file has its line diff.
3. **Commit.** `…/commit`, the dialog, the identity, the read-back, and the shell's working files, fed by the flow
   screen, the endpoint form and the mapping studio.
4. **Deploy and going back.** `…/deploy`, the dialog with its check, **Commit and deploy**, and **Deploy this commit…**
   in History, with the `@stack` scenario. The common case then works end to end: edit, commit, deploy.
5. **The readable change of a flow.** `flowChanges` and the marked picture, in the endpoints module.
6. **The readable changes of an endpoint and a mapping.** In their modules.
7. **Dependencies and Lock.** The Dependencies tab and `…/lock` through `RAIL8_CLI`.
8. **Remote.** Fetch, the fast-forward pull with the tabs opened again, and push.
9. **New package.** `POST /git/packages` and its dialog.
10. **What each node runs, on the node.** The rows of what each node runs and `GET /admin/pool/packages`, designed
    first.
11. **Every node in the UI.** One entry per node in the deployed state.
