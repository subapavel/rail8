import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import { msg, type Label, type Message } from '../language';
import type { Change, Commit, Kind, PackagesApi, PackageState } from './api';

/** What reading a package gave: nothing yet, a refusal with its message, or the package and the time it was read at. */
export type PackageRead = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; state: PackageState; at: Date };

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/** How a file differs, in words, with the status of the badge that marks it. */
export const CHANGE: Record<Change['status'], { label: Message; status: 'success' | 'warning' | 'error' | 'processing' }> = {
  added: { label: msg('packages.change.added'), status: 'success' },
  modified: { label: msg('packages.change.modified'), status: 'warning' },
  deleted: { label: msg('packages.change.deleted'), status: 'error' },
  renamed: { label: msg('packages.change.renamed'), status: 'processing' },
};

/** The name of each kind of file, as a column or a tag shows it: a word, or the file's own name. */
export const KIND: Record<Kind, Label> = {
  flow: msg('packages.kind.flow'),
  endpoint: msg('packages.kind.endpoint'),
  mapping: msg('packages.kind.mapping'),
  test: msg('packages.kind.test'),
  package: 'package.yaml',
  lock: 'package.lock',
  folder: 'folder.yaml',
  file: msg('packages.kind.file'),
};

/** The kinds the artifacts are grouped by, in their order, each under its title. */
export const GROUPS: { key: string; title: Message; kinds: Kind[] }[] = [
  { key: 'flows', title: msg('packages.group.flows'), kinds: ['flow'] },
  { key: 'endpoints', title: msg('packages.group.endpoints'), kinds: ['endpoint'] },
  { key: 'mappings', title: msg('packages.group.mappings'), kinds: ['mapping'] },
  { key: 'tests', title: msg('packages.group.tests'), kinds: ['test'] },
  { key: 'package', title: msg('packages.group.package'), kinds: ['package', 'lock', 'folder'] },
  { key: 'files', title: msg('packages.group.files'), kinds: ['file'] },
];

/** The path of `path` within its package: the working folder's path without its first segment. */
export const inside = (path: string) => path.slice(path.indexOf('/') + 1);

/** The last segment of `path`. */
export const nameOf = (path: string) => path.slice(path.lastIndexOf('/') + 1);

/** Calls `shown` each time the element of `ref` comes into view again after it was hidden, as a tab that is chosen again. */
function useShownAgain(ref: RefObject<HTMLElement | null>, shown: () => void) {
  useEffect(() => {
    const element = ref.current;
    if (element === null || typeof IntersectionObserver === 'undefined') return;
    let visible: boolean | undefined;
    const observer = new IntersectionObserver(([entry]) => {
      const now = entry?.isIntersecting ?? false;
      if (visible === false && now) shown();
      visible = now;
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref, shown]);
}

/**
 * The package `repository`, read at once and again when the element of the returned ref is shown again, when the
 * window gets the focus and on `refresh`.
 */
export function usePackage(api: PackagesApi, repository: string, now: () => Date) {
  const [read, setRead] = useState<PackageRead>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const root = useRef<HTMLDivElement>(null);
  useShownAgain(root, refresh);

  useEffect(() => {
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [refresh]);

  useEffect(() => {
    let current = true;
    api.get(repository).then(
      (state) => current && setRead({ kind: 'read', state, at: now() }),
      (error: unknown) => current && setRead({ kind: 'failed', message: messageOf(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, repository, asked, now]);

  /** Shows `state`, as an answer that carries the package's state gives it, read now. */
  const show = useCallback((state: PackageState) => setRead({ kind: 'read', state, at: now() }), [now]);

  return { read, refresh, show, root };
}

/** The commits of the branch whose tip is `head`, newest first, a page at a time, read again when `head` changes and on `retry`. */
export function useHistory(api: PackagesApi, repository: string, head: string | null) {
  const [shown, setShown] = useState<{ commits: Commit[]; next: string | null; failure: string | null; read: boolean }>({ commits: [], next: null, failure: null, read: false });
  const [more, setMore] = useState(false);
  const [asked, setAsked] = useState(0);
  /** Counts the reads of the first page, so that an older page asked for before the latest one is dropped. */
  const reads = useRef(0);

  useEffect(() => {
    let current = true;
    reads.current++;
    api.log(repository).then(
      (page) => current && setShown({ commits: page.items, next: page.next, failure: null, read: true }),
      (error: unknown) => current && setShown((s) => ({ ...s, failure: messageOf(error), read: true })),
    );
    return () => {
      current = false;
    };
  }, [api, repository, head, asked]);

  /** Reads the first page again, after a read that failed. */
  const retry = () => {
    setShown((s) => ({ ...s, failure: null, read: false }));
    setAsked((n) => n + 1);
  };

  const older = (before: string) => {
    const read = reads.current;
    setMore(true);
    api
      .log(repository, { before })
      .then(
        (page) => read === reads.current && setShown((s) => ({ ...s, commits: [...s.commits, ...page.items], next: page.next })),
        (error: unknown) => read === reads.current && setShown((s) => ({ ...s, failure: messageOf(error) })),
      )
      .finally(() => setMore(false));
  };
  return { ...shown, more, older, retry };
}

/** The files the commit `commit` changed against its first parent; none to read for the first commit of the branch. */
export function useCommitChanges(api: PackagesApi, repository: string, commit: Commit) {
  const parent = commit.parents[0];
  const [read, setRead] = useState<{ kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; changes: Change[]; truncated: boolean }>({ kind: 'reading' });
  useEffect(() => {
    if (parent === undefined) return;
    let current = true;
    api.changes(repository, parent, commit.id).then(
      (listed) => current && setRead({ kind: 'read', ...listed }),
      (error: unknown) => current && setRead({ kind: 'failed', message: messageOf(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, repository, commit.id, parent]);
  return { parent, read };
}
