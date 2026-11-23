import { createContext, type ComponentType } from 'react';
import type { Screen } from './screens';

/**
 * What a file of a package is: a flow, an endpoint, a mapping or a test; the package's `package.yaml`, its
 * `package.lock` or a `folder.yaml`; or any other file.
 */
export type Kind = 'flow' | 'endpoint' | 'mapping' | 'test' | 'package' | 'lock' | 'folder' | 'file';

/** How a file differs from the version it is compared with. */
export type ChangeStatus = 'added' | 'modified' | 'deleted' | 'renamed';

/**
 * One artifact of a package at a revision: its path relative to the working folder, with `/`, as the editors' routes
 * name it; its kind; its id when the file gives one; how it changed since the last commit, for the files on disk; and,
 * for a file of the kind `file`, the paths of the artifacts that name its path from the package's root.
 */
export interface Artifact {
  path: string;
  kind: Kind;
  id?: string;
  change?: ChangeStatus;
  usedBy?: string[];
}

/** One version of a file, with the files of its package at the same revision. */
export interface Side {
  /** `work`, the files on disk, or a commit of 40 hex digits. */
  rev: string;
  text: string;
  /** The package's artifacts at `rev`. */
  artifacts(): Promise<Artifact[]>;
  /** The text of a file of the package at `rev`, by its path relative to the working folder; null when there is none there. */
  read(path: string): Promise<string | null>;
}

/** What a module of the UI draws of the kind of artifact it owns, for the tab of a package. */
export interface ArtifactKind {
  kind: 'flow' | 'endpoint' | 'mapping';
  /** The tab the module's own list opens for the file at `path`, relative to the working folder. */
  open(path: string): Screen;
  /** The readable change between two versions of the file at `path`; a side is null when the file has no version there. */
  Change: ComponentType<{ path: string; before: Side | null; after: Side | null }>;
}

/** The kinds of artifact the modules register, by kind. */
export const ArtifactKinds = createContext<ReadonlyMap<string, ArtifactKind>>(new Map());

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;

/**
 * The kinds of artifact the modules register, by kind, from what each module's file exports, keyed by the file, as
 * `import.meta.glob` with `eager: true` gives it. A module registers them as the `artifacts` of its `UiModule`. Throws,
 * naming the files, when a module's `artifacts` is no list of kinds, and when a kind is registered twice.
 */
export function artifactKinds(found: Readonly<Record<string, unknown>>): ReadonlyMap<string, ArtifactKind> {
  const kinds = new Map<string, ArtifactKind>();
  const owners = new Map<string, string>();
  for (const file of Object.keys(found).sort()) {
    const exported = found[file];
    const module = isRecord(exported) && isRecord(exported.default) ? exported.default : undefined;
    const listed = module?.artifacts;
    if (listed === undefined) continue;
    const valid =
      Array.isArray(listed) &&
      listed.every((kind: unknown) => isRecord(kind) && typeof kind.kind === 'string' && typeof kind.open === 'function' && typeof kind.Change === 'function');
    if (!valid) throw new Error(`${file} registers artifacts that are no list of { kind, open, Change }`);
    for (const kind of listed as ArtifactKind[]) {
      const owner = owners.get(kind.kind);
      if (owner !== undefined) throw new Error(`the artifact kind "${kind.kind}" is registered twice: by ${owner} and by ${file}`);
      owners.set(kind.kind, file);
      kinds.set(kind.kind, kind);
    }
  }
  return kinds;
}
