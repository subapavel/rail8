import { BranchesOutlined } from '@ant-design/icons';
import type { Screen } from '../screens';
import { PackageList } from './PackageList';
import { PackageTab } from './PackageTab';
import type { PackagesApi } from './api';

const clock = () => new Date();

/**
 * The tab of the package whose clone is the folder `repository`, on a server that takes writes when `writable`: its id
 * is `package:` and the repository, its title the repository.
 */
export function packageTab(api: PackagesApi, repository: string, writable: boolean, now: () => Date = clock): Screen {
  return {
    id: `package:${repository}`,
    title: repository,
    group: 'Design',
    icon: <BranchesOutlined aria-hidden />,
    render: (open) => <PackageTab api={api} repository={repository} now={now} open={open} writable={writable} />,
  };
}

/**
 * The Packages screen over `api`: every package of the working folder, with its clone's state and what the node runs
 * of it, each opening in a tab of its own; `now` tells the ages.
 */
export function packagesScreen(api: PackagesApi, now: () => Date = clock): Screen {
  return {
    id: 'packages',
    title: 'Packages',
    group: 'Design',
    icon: <BranchesOutlined aria-hidden />,
    description: 'See each package of the working folder: its branch, last commit, changes and what the node runs.',
    render: (open) => <PackageList api={api} now={now} onOpen={(repository, writable) => open(packageTab(api, repository, writable, now))} />,
  };
}
