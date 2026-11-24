import { TeamOutlined } from '@ant-design/icons';
import type { Screen } from '../screens';
import type { UsersApi } from './api';
import { UserList } from './UserList';

/** The Users screen over `api`: who uses the installation and their roles, which an administrator manages there. */
export function usersScreen(api: UsersApi): Screen {
  return {
    id: 'users',
    title: 'Users',
    group: 'Administration',
    icon: <TeamOutlined />,
    description: 'Create users, change their roles, block them, reset passwords and end sessions.',
    action: 'user.read',
    render: () => <UserList api={api} />,
  };
}
