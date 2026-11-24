import type { PackagesApi } from '../api';
import { fakeLock, fakePackagesApi, tagOf, type FakeCommit, type FakeFolder, type FakeLock, type FakeNodeRefusal, type FakeSent, type FakeState } from '../fake';
import { GIT_MISSING, gitTooOld, inPackage } from '../rules';

/**
 * A working folder, its git and its node, as data that each world builds the same way: the http world with real git
 * in temporary folders behind the routes, the fake world in memory. The contract suite seeds both with it.
 */
export interface Seed {
  clones?: CloneSeed[];
  /** Folders with a file and no clone. */
  plain?: string[];
  /** Folders that are a worktree of the clone `of`. */
  worktrees?: { folder: string; of: string }[];
  /** Folders whose `.git` folder is no repository, so git takes them for folders of the repository the working folder lies in. */
  nested?: string[];
  /** Symbolic links to the folder `to` of the working folder. */
  links?: { folder: string; to: string }[];
  /** What the node runs (nothing by default), or `silent`: it gives no answer. */
  node?: 'silent' | RunningSeed[];
  /**
   * How the node takes a deploy and its check, which it otherwise takes as it runs, recording the commit deployed: with a
   * refusal of its own of every one, or `silent`, giving no answer to a deploy though it answers its check.
   */
  deploys?: FakeNodeRefusal | 'silent';
  /** The node refuses the token the git half sends (401) for every call. */
  tokenRefused?: true;
  readOnly?: boolean;
  /** False: no working folder is set. */
  workspace?: false;
  /** git is missing from the PATH, or older than 2.30. */
  git?: 'missing' | 'old';
  /** The calls come from a page of another site. */
  foreignPage?: boolean;
  /** null: git gives no author identity, so a commit is refused. By default {@link AUTHOR}. */
  identity?: null;
}

/** The author identity git gives by default. */
export const AUTHOR = { name: 'Jiří Novák', email: 'jiri@example.com' } as const;

/** A time zone a seeded commit is made in, by its name, with the offset git gives its times. */
export const ZONES = { UTC: '+00:00', 'Asia/Kolkata': '+05:30' } as const;
export type Zone = keyof typeof ZONES;

/** A clone of its own on branch `main`. */
export interface CloneSeed {
  folder: string;
  /** The text of `package.yaml` at its root, committed in its first commit; null: none there. By default it names the folder, version 1.0.0. */
  manifest?: string | null;
  /** Other files of its first commit, by path. */
  files?: Record<string, string>;
  /** The commits of `main`, oldest first, by label: each adds the file `<label>.txt`. None: no commit yet. By default `first`. */
  commits?: string[];
  /** The time zone the seeded commits are made in, one without summer time. By default UTC. */
  zone?: Zone;
  /** Files a labelled commit writes besides `<label>.txt`, by path; null deletes one. */
  commitFiles?: Record<string, Record<string, string | null>>;
  /** The upstream `origin/main`, fetched: at the commit `at` of main, with the commits `more` of its own on top. */
  upstream?: { at: string; more?: string[] };
  /** A merge in progress, with `conflict.txt` in conflict: `main` gains the commit `conflict`, and the branch `side` the commit `side`. */
  conflict?: boolean;
  /** With `conflict`: the merge's own files are gone, so only the path in conflict is left of it. */
  unmerged?: true;
  /** Another git holds the index: `.git/index.lock` exists. */
  indexLock?: true;
  /**
   * A pre-commit hook: it prints `says`, writes and stages each file of `writes`, by path, writes each file of
   * `unstaged` without staging it, and refuses the commit when `refuses`; with `stopsGit`, a post-commit hook stops git
   * with TERM once it has committed.
   */
  hook?: { says?: string; writes?: Record<string, string>; unstaged?: Record<string, string>; refuses?: true; stopsGit?: true };
  /** A commit made in a terminal just before the git half's commit starts: it adds `<label>.txt`. */
  meanwhile?: string;
  /**
   * What a terminal does just after the git half's `git commit` ends: the files of `stages` written and staged, by path;
   * a commit that adds `<commits>.txt`, with the message `<commits>`; and an amend of git's commit that adds
   * `<amends>.txt`, with the message `<amends>`.
   */
  afterwards?: { commits?: string; stages?: Record<string, string>; amends?: string };
  /** `HEAD` detached at the last commit. */
  detached?: boolean;
  /** Files moved with `git mv` after the commits, from path to path. */
  moves?: Record<string, string>;
  /** Files written on disk and staged after the moves, by path; null deletes one from disk and index. */
  staged?: Record<string, string | null>;
  /** Files written on disk last, by path; null deletes one. */
  edits?: Record<string, string | null>;
  /** Paths of the first commit that are symbolic links to a file outside the package. */
  outside?: string[];
  /** Symbolic links of the first commit into the clone's own `.git` folder: from the link's path to the path within `.git` it names. */
  intoGit?: Record<string, string>;
}

/** What the node runs of `repository`: the commit of that clone with the label `commit`, or `commit` itself when it is 40 hex digits. */
export interface RunningSeed {
  repository: string;
  commit: string;
  state?: 'RUNNING' | 'FAILED';
  problems?: string[];
}

/** A world the contract suite asserts on, through `api` and an editor's save. */
export interface World {
  api: PackagesApi;
  /** The id of the commit with `label` in the clone `folder`. */
  id(folder: string, label: string): string;
  /**
   * An editor's save of `text` over the file at `path`, relative to the working folder, which the author read with the
   * git half's `tag`: under the working folder's lock it compares the bytes on disk with that tag once more, and answers
   * 200 when it wrote, or 412 when they differ.
   */
  save(path: string, text: string, tag: string): Promise<200 | 412>;
  /** Resolves once the next write holds the working folder's lock. */
  held(): Promise<void>;
  /** Every deploy and check the node was sent, in order. */
  sent(): FakeSent[];
  /** Resolves once the node has been sent a deploy, not a check, that it has not answered. */
  heard(): Promise<void>;
  /** Moves the clock the git half waits on the node by on by `ms`. */
  elapse(ms: number): void;
  /** The node runs the commit with `label` of the clone `folder` from now on, as after a deploy by someone else. */
  deployedElsewhere(folder: string, label: string): void;
  /** git run with `args` in a terminal in the clone `folder`, as the author would: what it prints. The real git half only. */
  terminal?(folder: string, args: readonly string[]): string;
  /** Makes a symbolic link at `path` of the clone `folder` whose target is the text `target`, as the author would on disk. The real git half only. */
  link?(folder: string, path: string, target: string): Promise<void>;
  /** Closes the git half's runner, as the development server does when it ends, once its next `git <command>` ends. The real git half only. */
  closesAfter?(command: string): void;
  close(): Promise<void>;
}

export type Build = (seed: Seed) => Promise<World>;

/** The time every seeded deploy was made at. */
export const DEPLOYED_AT = '2026-11-20T10:00:00Z';

/** The git version the seed's old git gives. */
export const OLD_GIT = '2.29.2';

export const manifestFor = (clone: CloneSeed) => (clone.manifest === undefined ? `name: ${clone.folder}\nversion: 1.0.0\n` : clone.manifest);

/** The commit a label names in the seed's own terms: a 40 hex id stands for itself. */
export const isId = (commit: string) => /^[0-9a-f]{40}$/.test(commit);

/** The fake world's state for `seed`, with the id of each labelled commit. */
export function fakeStateOf(seed: Seed): { state: FakeState; ids: Map<string, string> } {
  const ids = new Map<string, string>();
  let count = 0;
  // the time of the nth commit, a minute apart, as git writes it in `zone`
  const at = (n: number, zone: Zone = 'UTC') => {
    const offset = ZONES[zone];
    const minutes = (offset.startsWith('-') ? -1 : 1) * (Number(offset.slice(1, 3)) * 60 + Number(offset.slice(4)));
    return new Date(Date.parse('2026-11-01T09:00:00Z') + (n + minutes) * 60_000).toISOString().slice(0, 19) + offset;
  };

  const cloneOf = (clone: CloneSeed): FakeFolder => {
    const folder: FakeFolder = {
      name: clone.folder,
      git: 'clone',
      commits: [],
      head: { branch: 'main', commit: null },
      upstream: null,
      index: {},
      disk: {},
      operation: null,
      conflicts: [],
    };
    const manifest = manifestFor(clone);
    let files: Record<string, string> = { ...(manifest === null ? {} : { 'package.yaml': manifest }), ...clone.files };
    folder.disk = { ...files };
    const commit = (label: string, parent: string | null, add: Record<string, string | null>): FakeCommit => {
      files = { ...(parent === null ? {} : folder.commits.find((c) => c.id === parent)!.files) };
      for (const [path, text] of Object.entries(add)) {
        if (text === null) delete files[path];
        else files[path] = text;
      }
      count++;
      const made = { id: count.toString(16).padStart(40, '0'), parents: parent === null ? [] : [parent], subject: label, author: 'Seed Author', at: at(count, clone.zone), files };
      folder.commits.push(made);
      ids.set(`${clone.folder}/${label}`, made.id);
      return made;
    };
    for (const label of clone.commits ?? ['first']) {
      const first = folder.head.commit === null;
      folder.head.commit = commit(label, folder.head.commit, { ...(first ? folder.disk : {}), [`${label}.txt`]: `${label}\n`, ...clone.commitFiles?.[label] }).id;
    }
    if (clone.upstream !== undefined) {
      let tip = ids.get(`${clone.folder}/${clone.upstream.at}`)!;
      for (const label of clone.upstream.more ?? []) tip = commit(label, tip, { [`${label}.txt`]: `${label}\n` }).id;
      folder.upstream = { name: 'origin/main', commit: tip, fetchedAt: at(count + 1) };
    }
    if (clone.conflict) {
      const first = folder.commits[0]!.id;
      commit('side', first, { 'conflict.txt': 'side\n' });
      folder.head.commit = commit('conflict', folder.head.commit, { 'conflict.txt': 'main\n' }).id;
      folder.operation = 'merge';
      folder.conflicts = ['conflict.txt'];
    }
    const head = folder.commits.find((c) => c.id === folder.head.commit);
    folder.index = { ...(head?.files ?? {}) };
    folder.disk = { ...(head?.files ?? folder.disk) };
    if (clone.conflict) folder.disk['conflict.txt'] = '<<<<<<< HEAD\nmain\n=======\nside\n>>>>>>> side\n';
    if (clone.detached) folder.head.branch = null;
    if (clone.outside !== undefined || clone.intoGit !== undefined) folder.escapes = [...(clone.outside ?? []), ...Object.keys(clone.intoGit ?? {})];
    if (clone.conflict && clone.unmerged) folder.operation = null;
    if (clone.indexLock) folder.indexLocked = true;
    if (clone.hook !== undefined) folder.hook = clone.hook;
    if (clone.meanwhile !== undefined) folder.meanwhile = clone.meanwhile;
    if (clone.afterwards !== undefined) folder.afterwards = clone.afterwards;
    for (const [from, to] of Object.entries(clone.moves ?? {})) {
      folder.index[to] = folder.index[from]!;
      folder.disk[to] = folder.disk[from]!;
      delete folder.index[from];
      delete folder.disk[from];
    }
    for (const [path, text] of Object.entries(clone.staged ?? {})) {
      if (text === null) {
        delete folder.index[path];
        delete folder.disk[path];
      } else {
        folder.index[path] = text;
        folder.disk[path] = text;
      }
    }
    for (const [path, text] of Object.entries(clone.edits ?? {})) {
      if (text === null) delete folder.disk[path];
      else folder.disk[path] = text;
    }
    return folder;
  };

  const plainOf = (name: string, git: FakeFolder['git'], link?: true): FakeFolder => ({
    name,
    git,
    ...(link ? { link } : {}),
    commits: [],
    head: { branch: null, commit: null },
    upstream: null,
    index: {},
    disk: {},
    operation: null,
    conflicts: [],
  });

  const folders = [
    ...(seed.clones ?? []).map(cloneOf),
    ...(seed.plain ?? []).map((name) => plainOf(name, 'none')),
    ...(seed.worktrees ?? []).map(({ folder }) => plainOf(folder, 'file')),
    ...(seed.nested ?? []).map((name) => plainOf(name, 'no-repository')),
    ...(seed.links ?? []).map(({ folder }) => plainOf(folder, 'clone', true)),
  ];
  const host = '127.0.0.1:8081';
  const node: FakeState['node'] =
    seed.node === 'silent'
      ? { host, answers: false, error: `the node at ${host} does not answer (is it running?)` }
      : seed.tokenRefused
        ? { host, answers: false, error: `the node at ${host} refuses RAIL8_ADMIN_TOKEN (401)`, refusesToken: true }
      : {
          host,
          answers: true,
          ...(seed.deploys === undefined ? {} : { deploys: seed.deploys }),
          running: (seed.node ?? []).map((r) => ({
            repository: r.repository,
            commit: isId(r.commit) ? r.commit : ids.get(`${r.repository}/${r.commit}`)!,
            state: r.state ?? 'RUNNING',
            problems: r.problems ?? [],
            deployedAt: DEPLOYED_AT,
          })),
        };
  const state: FakeState = {
    workspace: seed.workspace !== false,
    writable: !seed.readOnly,
    foreignPage: seed.foreignPage ?? false,
    git: seed.git === 'missing' ? GIT_MISSING : seed.git === 'old' ? gitTooOld(OLD_GIT) : null,
    identity: seed.identity === null ? null : { ...AUTHOR },
    node,
    sent: [],
    folders,
  };
  return { state, ids };
}

/** The fake world: the fake git half over the state of `seed`, whose saves share the fake working folder's lock with it. */
export const fakeWorld: Build = async (seed) => {
  const { state, ids } = fakeStateOf(seed);
  const all = fakeLock();
  const holding: (() => void)[] = [];
  const lock: FakeLock = (work) =>
    all(() => {
      for (const done of holding.splice(0)) done();
      return work();
    });
  return {
    api: fakePackagesApi(state, lock),
    // a commit made since the seed, as by the terminal of `meanwhile`, is found by its subject
    id: (folder, label) => ids.get(`${folder}/${label}`) ?? state.folders.find((f) => f.name === folder)!.commits.find((c) => c.subject === label)!.id,
    save: (path, text, tag) =>
      lock(async () => {
        const [repository] = path.split('/');
        const folder = state.folders.find((f) => f.name === repository)!;
        const { inside } = inPackage(repository!, path) as { inside: string };
        const now = folder.disk[inside];
        if (now === undefined || (await tagOf(now)) !== tag) return 412;
        folder.disk[inside] = text;
        return 200;
      }),
    held: () => new Promise<void>((done) => holding.push(done)),
    sent: () => state.sent,
    // the fake node answers at once: a silent one with the git half's 504
    heard: async () => {},
    elapse: () => {},
    deployedElsewhere(folder, label) {
      if (!state.node.answers) return;
      const commit = ids.get(`${folder}/${label}`)!;
      state.node.running = [...state.node.running.filter((r) => r.repository !== folder), { repository: folder, commit, state: 'RUNNING', problems: [], deployedAt: DEPLOYED_AT }];
    },
    close: async () => {},
  };
};
