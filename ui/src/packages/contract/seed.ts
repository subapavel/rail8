import type { PackagesApi } from '../api';
import { fakePackagesApi, type FakeCommit, type FakeFolder, type FakeState } from '../fake';
import { GIT_MISSING, gitTooOld } from '../rules';

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
  readOnly?: boolean;
  /** False: no working folder is set. */
  workspace?: false;
  /** git is missing from the PATH, or older than 2.30. */
  git?: 'missing' | 'old';
  /** The calls come from a page of another site. */
  foreignPage?: boolean;
}

/** A clone of its own on branch `main`. */
export interface CloneSeed {
  folder: string;
  /** The text of `package.yaml` at its root, committed in its first commit; null: none there. By default it names the folder, version 1.0.0. */
  manifest?: string | null;
  /** Other files of its first commit, by path. */
  files?: Record<string, string>;
  /** The commits of `main`, oldest first, by label: each adds the file `<label>.txt`. None: no commit yet. By default `first`. */
  commits?: string[];
  /** The upstream `origin/main`, fetched: at the commit `at` of main, with the commits `more` of its own on top. */
  upstream?: { at: string; more?: string[] };
  /** A merge in progress, with `conflict.txt` in conflict: `main` gains the commit `conflict`, and the branch `side` the commit `side`. */
  conflict?: boolean;
  /** `HEAD` detached at the last commit. */
  detached?: boolean;
  /** Files written on disk last, by path; null deletes one. */
  edits?: Record<string, string | null>;
}

/** What the node runs of `repository`: the commit of that clone with the label `commit`, or `commit` itself when it is 40 hex digits. */
export interface RunningSeed {
  repository: string;
  commit: string;
  state?: 'RUNNING' | 'FAILED';
  problems?: string[];
}

/** A world the contract suite asserts on, only through `api`. */
export interface World {
  api: PackagesApi;
  /** The id of the commit with `label` in the clone `folder`. */
  id(folder: string, label: string): string;
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
  const at = (n: number) => new Date(Date.parse('2026-11-01T09:00:00Z') + n * 60_000).toISOString().replace('.000Z', '+00:00');

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
    const commit = (label: string, parent: string | null, add: Record<string, string>): FakeCommit => {
      files = { ...(parent === null ? {} : folder.commits.find((c) => c.id === parent)!.files), ...add };
      count++;
      const made = { id: count.toString(16).padStart(40, '0'), parents: parent === null ? [] : [parent], subject: label, author: 'Seed Author', at: at(count), files };
      folder.commits.push(made);
      ids.set(`${clone.folder}/${label}`, made.id);
      return made;
    };
    for (const label of clone.commits ?? ['first']) {
      const first = folder.head.commit === null;
      folder.head.commit = commit(label, folder.head.commit, { ...(first ? folder.disk : {}), [`${label}.txt`]: `${label}\n` }).id;
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
      : {
          host,
          answers: true,
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
    node,
    folders,
  };
  return { state, ids };
}

/** The fake world: the fake git half over the state of `seed`. */
export const fakeWorld: Build = async (seed) => {
  const { state, ids } = fakeStateOf(seed);
  return {
    api: fakePackagesApi(state),
    id: (folder, label) => ids.get(`${folder}/${label}`)!,
    close: async () => {},
  };
};
