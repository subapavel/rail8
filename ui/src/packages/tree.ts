import type { Artifact } from './api';
import { nameOf } from './data';
import { folderPathRefused, namespaceTaken, type Refused } from './rules';

/**
 * A folder of the tree: its key (its path relative to the working folder, with `/` at its end), its path within the
 * top folder, its own name, its `folder.yaml` when it has one, why the package's rules refuse it (its name, its depth,
 * its namespace shared with another folder), and what it holds, folders first, each part sorted by name.
 */
export interface FolderNode {
  type: 'folder';
  key: string;
  inside: string;
  name: string;
  settings?: Artifact;
  problems: Refused[];
  children: TreeNode[];
}

/** An artifact of the tree: its key (its path), the artifact, and the name it shows, its id or its file's name. */
export interface ArtifactNode {
  type: 'artifact';
  key: string;
  artifact: Artifact;
  name: string;
}

export type TreeNode = FolderNode | ArtifactNode;

/** What the kind filter above the tree shows: every artifact, or the artifacts of one kind, `file` meaning every file of no kind of its own. */
export type KindFilter = 'all' | 'flow' | 'endpoint' | 'mapping' | 'test' | 'file';

/** The kind filter an artifact falls under. */
export const filterOf = (artifact: Artifact): Exclude<KindFilter, 'all'> =>
  artifact.kind === 'flow' || artifact.kind === 'endpoint' || artifact.kind === 'mapping' || artifact.kind === 'test' ? artifact.kind : 'file';

const order = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
/** Folders first, then artifacts, each by name with the case ignored, then by name, then by key. */
const byName = (a: TreeNode, b: TreeNode) =>
  a.type !== b.type ? (a.type === 'folder' ? -1 : 1) : order(a.name.toLowerCase(), b.name.toLowerCase()) || order(a.name, b.name) || order(a.key, b.key);

/**
 * The artifacts of the folder `top`, each a path relative to the working folder below it, as a tree of their folders:
 * each folder's `folder.yaml` is its settings, not an artifact of its own, but at the top folder's root. With `rules`,
 * as for a package, each folder carries what the package's rules refuse of it: a name that is no folder's name, a
 * depth over four folders, or a namespace another folder has too.
 */
export function treeOf(top: string, artifacts: readonly Artifact[], rules: boolean): TreeNode[] {
  const root: FolderNode = { type: 'folder', key: `${top}/`, inside: '', name: top, problems: [], children: [] };
  const folders = new Map<string, FolderNode>([['', root]]);
  const folderAt = (inside: string): FolderNode => {
    const found = folders.get(inside);
    if (found !== undefined) return found;
    const cut = inside.lastIndexOf('/');
    const parent = folderAt(cut === -1 ? '' : inside.slice(0, cut));
    const made: FolderNode = { type: 'folder', key: `${top}/${inside}/`, inside, name: inside.slice(cut + 1), problems: [], children: [] };
    parent.children.push(made);
    folders.set(inside, made);
    return made;
  };
  for (const artifact of artifacts) {
    const inside = artifact.path.slice(top.length + 1);
    const cut = inside.lastIndexOf('/');
    const folder = folderAt(cut === -1 ? '' : inside.slice(0, cut));
    if (artifact.kind === 'folder' && folder !== root) folder.settings = artifact;
    else folder.children.push({ type: 'artifact', key: artifact.path, artifact, name: artifact.id ?? nameOf(artifact.path) });
  }
  if (rules) {
    const named = [...folders.values()].filter((f) => f.settings?.namespace !== undefined).map((f) => ({ inside: f.inside, namespace: f.settings!.namespace }));
    for (const folder of folders.values()) {
      if (folder === root) continue;
      const refused = folderPathRefused(folder.inside);
      // a folder too deep says so once, at the first folder below the limit
      if (refused !== null && (refused.code !== 'folder-too-deep' || folder.inside.split('/').length === 5)) folder.problems.push(refused);
      const namespace = folder.settings?.namespace;
      const taken = namespace === undefined ? null : namespaceTaken(namespace, folder.inside, named);
      if (taken !== null) folder.problems.push(taken);
    }
  }
  const sort = (nodes: TreeNode[]) => {
    nodes.sort(byName);
    for (const node of nodes) if (node.type === 'folder') sort(node.children);
  };
  sort(root.children);
  return root.children;
}

/** Every folder of `nodes`, at any depth, in the tree's order. */
export function foldersOf(nodes: readonly TreeNode[]): FolderNode[] {
  return nodes.flatMap((node) => (node.type === 'folder' ? [node, ...foldersOf(node.children)] : []));
}

/**
 * The tree of `nodes` that shows the artifacts of `kind` whose name or path within the top folder holds `text`, or
 * that lie in a folder whose name or namespace holds it, the case ignored, each in its folders. A folder shows when
 * something below it shows; with no filter, an empty folder shows too.
 */
export function filtered(nodes: readonly TreeNode[], kind: KindFilter, text: string): TreeNode[] {
  const wanted = text.trim().toLowerCase();
  if (kind === 'all' && wanted === '') return [...nodes];
  const holds = (value: string | undefined) => value !== undefined && value.toLowerCase().includes(wanted);
  const visit = (list: readonly TreeNode[], matched: boolean): TreeNode[] =>
    list.flatMap((node): TreeNode[] => {
      if (node.type === 'artifact') {
        const ofKind = kind === 'all' || filterOf(node.artifact) === kind;
        return ofKind && (matched || wanted === '' || holds(node.name) || holds(node.artifact.path.slice(node.artifact.path.indexOf('/') + 1))) ? [node] : [];
      }
      const children = visit(node.children, matched || (wanted !== '' && (holds(node.name) || holds(node.settings?.namespace))));
      return children.length > 0 ? [{ ...node, children }] : [];
    });
  return visit(nodes, false);
}
