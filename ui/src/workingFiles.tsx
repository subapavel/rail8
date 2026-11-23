import { createContext, useContext, useEffect, useLayoutEffect, useRef } from 'react';

/**
 * A file an open tab holds: its path relative to the working folder, with `/`; whether a tab holding it has unsaved
 * changes, as its editor says at the moment it is asked; and what shows that tab, or the first tab holding the file
 * when none has unsaved changes.
 */
export interface WorkingFile {
  path: string;
  dirty: boolean;
  show(): void;
}

/** The files the open tabs hold, as their editors register them. */
export interface WorkingFiles {
  /** Each file an open tab holds, by its path, with its state read from its editors at this call. */
  files(): ReadonlyMap<string, WorkingFile>;
  /**
   * Reads the files of `paths` again from disk in each tab that holds one, asking each tab just before whether it has
   * unsaved changes; a tab that has some is left as it is.
   */
  reload(paths: Iterable<string>): void;
}

/** One editor's file: its path, the tab it is in (null: no tab), and the editor's own unsaved state and reload. */
export interface WorkingFileRegistration {
  path: string;
  tab: string | null;
  dirty: () => boolean;
  reload: () => void;
}

/** The shell's registry of working files: what {@link useWorkingFile} adds a registration to, and takes it out of again. */
export interface WorkingFileRegistry extends WorkingFiles {
  /** Adds `registration`; the function given back takes it out. */
  add(registration: WorkingFileRegistration): () => void;
}

/** A registry that holds no registration until one is added, and whose `show` selects a tab through `select`. */
export function workingFileRegistry(select: (tab: string) => void): WorkingFileRegistry {
  const registrations = new Set<WorkingFileRegistration>();
  return {
    add: (registration) => {
      registrations.add(registration);
      return () => void registrations.delete(registration);
    },
    files: () => {
      const byPath = new Map<string, WorkingFileRegistration[]>();
      for (const registration of registrations) byPath.set(registration.path, [...(byPath.get(registration.path) ?? []), registration]);
      return new Map(
        Array.from(byPath, ([path, held]) => {
          const dirty = held.find((registration) => registration.dirty());
          const tab = (dirty ?? held[0]!).tab;
          return [path, { path, dirty: dirty !== undefined, show: () => tab !== null && select(tab) }];
        }),
      );
    },
    reload: (paths) => {
      const wanted = new Set(paths);
      for (const registration of [...registrations]) if (wanted.has(registration.path) && !registration.dirty()) registration.reload();
    },
  };
}

const inert: WorkingFiles = { files: () => new Map(), reload: () => {} };

/** The registry the shell gives every tab; null outside a shell, where registering does nothing. */
export const RegisteredWorkingFiles = createContext<WorkingFileRegistry | null>(null);

/** The id of the tab its children are in; null outside a tab. */
export const WorkingTab = createContext<string | null>(null);

/**
 * Registers the file at `path`, relative to the working folder, which this editor holds, while it is mounted: `dirty`
 * says whether the editor has unsaved changes, with the same value it gives the shell's question for unsaved changes,
 * and `reload` reads the file again from disk, as the editor's own dialog for a file changed on disk does. Both are
 * called as the editor's latest render gave them. Outside a shell it does nothing.
 */
export function useWorkingFile(path: string, dirty: () => boolean, reload: () => void) {
  const registry = useContext(RegisteredWorkingFiles);
  const tab = useContext(WorkingTab);
  const latest = useRef({ dirty, reload });
  useLayoutEffect(() => {
    latest.current = { dirty, reload };
  });
  useEffect(() => registry?.add({ path, tab, dirty: () => latest.current.dirty(), reload: () => latest.current.reload() }), [registry, path, tab]);
}

/** The files the open tabs hold; outside a shell, none, and `reload` does nothing. */
export function useWorkingFiles(): WorkingFiles {
  return useContext(RegisteredWorkingFiles) ?? inert;
}
