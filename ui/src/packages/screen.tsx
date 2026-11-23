import { BranchesOutlined } from '@ant-design/icons';
import type { Screen } from '../screens';
import { PackageList } from './PackageList';
import type { PackagesApi } from './api';

const clock = () => new Date();

/** The Packages screen over `api`: every package of the working folder, with its clone's state and what the node runs of it; `now` tells the ages. */
export function packagesScreen(api: PackagesApi, now: () => Date = clock): Screen {
  return {
    id: 'packages',
    title: 'Packages',
    group: 'Design',
    icon: <BranchesOutlined aria-hidden />,
    description: 'See each package of the working folder: its branch, last commit, changes and what the node runs.',
    render: () => <PackageList api={api} now={now} />,
  };
}
