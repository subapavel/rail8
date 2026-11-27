import { BranchesOutlined, FolderOutlined } from '@ant-design/icons';
import type { ArtifactKind } from '../artifacts';
import type { Found, Screen } from '../screens';
import { msg, type Message } from '../language';
import type { Kind, PackagesApi } from './api';
import { nameOf } from './data';
import { FolderTab, listedArtifacts } from './FolderTab';
import { PackageList } from './PackageList';
import { PackageTab } from './PackageTab';

const clock = () => new Date();

/**
 * The tab of the package whose clone is the folder `repository`, on a server that takes writes when `writable`: its id
 * is `package:` and the repository, its title the repository. `pane` names the page's own tab it shows first, and
 * `select` the path of an artifact its Artifacts tab chooses and shows.
 */
export function packageTab(api: PackagesApi, repository: string, writable: boolean, now: () => Date = clock, pane?: string, select?: string): Screen {
  return {
    id: `package:${repository}`,
    title: repository,
    group: msg('frame.group.design'),
    icon: <BranchesOutlined aria-hidden />,
    render: (open) => <PackageTab api={api} repository={repository} now={now} open={open} writable={writable} pane={pane} select={select} />,
  };
}

/**
 * The tab of the folder `folder` of the working folder that is no package but holds artifacts, on a server that takes
 * writes when `writable`: its id is `folder:` and the folder, its title the folder. Packages does not list such a
 * folder: its tab opens from the breadcrumb of an artifact Quick open finds in it.
 */
export function folderTab(folder: string, writable: boolean): Screen {
  return {
    id: `folder:${folder}`,
    title: folder,
    group: msg('frame.group.design'),
    icon: <FolderOutlined aria-hidden />,
    render: (open) => <FolderTab folder={folder} writable={writable} open={open} />,
  };
}

/**
 * The Packages screen over `api`: every package of the working folder, with its clone's state and what the node runs
 * of it, each opening in a tab of its own; `now` tells the ages.
 */
export function packagesScreen(api: PackagesApi, now: () => Date = clock): Screen {
  return {
    id: 'packages',
    title: msg('packages.screen.title'),
    group: msg('frame.group.design'),
    icon: <BranchesOutlined aria-hidden />,
    description: msg('packages.screen.description'),
    render: (open) => (
      <PackageList api={api} now={now} onOpen={(repository, writable) => open(packageTab(api, repository, writable, now))} />
    ),
  };
}

/** The word for each kind of artifact Quick open finds. */
const KIND: Partial<Record<Kind, Message>> = {
  flow: msg('packages.kind.flow'),
  endpoint: msg('packages.kind.endpoint'),
  mapping: msg('packages.kind.mapping'),
  test: msg('packages.kind.test'),
};

/**
 * Every artifact of the working folder over `api` that Quick open finds, sorted by path: each flow, endpoint and
 * mapping of a package, opening in its editor's tab after Packages and the package's tab; each test of a package,
 * which has no tab of its own, opening the package's tab on its Artifacts; and each artifact a kind of `kinds` lists
 * in a folder that is no package, opening in its editor's tab after Packages and the folder's tab.
 */
export async function findArtifacts(api: PackagesApi, kinds: ReadonlyMap<string, ArtifactKind>, now: () => Date = clock): Promise<Found[]> {
  const list = await api.list();
  if (!list.configured) return [];
  const packages = packagesScreen(api, now);
  const found: Found[] = [];
  const add = (path: string, kind: Kind, id: string | undefined, screen: Screen, trail: readonly Screen[]) =>
    found.push({ name: id ?? nameOf(path), path, kind: KIND[kind]!, screen, trail });
  await Promise.all(
    list.packages.map(async ({ repository }) => {
      const { artifacts } = await api.artifacts(repository, 'work');
      const tab = packageTab(api, repository, list.writable, now);
      for (const { path, kind, id } of artifacts) {
        const owner = kinds.get(kind);
        if (owner !== undefined) add(path, kind, id, owner.open(path), [packages, tab]);
        else if (kind === 'test') add(path, kind, id, packageTab(api, repository, list.writable, now, 'artifacts', path), [packages]);
      }
    }),
  );
  const others = new Set(list.others.map(({ folder }) => folder));
  for (const { path, kind, id } of others.size === 0 ? [] : await listedArtifacts(kinds)) {
    const owner = kinds.get(kind);
    const folder = path.slice(0, path.indexOf('/'));
    if (owner !== undefined && others.has(folder)) add(path, kind, id, owner.open(path), [packages, folderTab(folder, list.writable)]);
  }
  return found.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
}
