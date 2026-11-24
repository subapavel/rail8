import { KeyOutlined, SafetyCertificateOutlined, TeamOutlined } from '@ant-design/icons';
import type { Screen } from '../screens';
import type { TokensApi, UsersApi } from './api';
import { MyTokens } from './MyTokens';
import { TokenAdmin } from './TokenAdmin';
import { UserList } from './UserList';

/**
 * The Users screen over `api` and `tokens`: who uses the installation and their roles, which an administrator manages
 * there, with the tokens of technical accounts.
 */
export function usersScreen(api: UsersApi, tokens: TokensApi): Screen {
  return {
    id: 'users',
    title: 'Users',
    group: 'Administration',
    icon: <TeamOutlined />,
    description: 'Create users, change their roles, block them, reset passwords, end sessions and issue tokens of technical accounts.',
    action: 'user.read',
    render: () => <UserList api={api} tokens={tokens} />,
  };
}

/** The Tokens screen over `tokens`: the installation's ceiling of a token's life, and the tokens that expire soon. */
export function tokensScreen(tokens: TokensApi): Screen {
  return {
    id: 'tokens',
    title: 'Tokens',
    group: 'Administration',
    icon: <SafetyCertificateOutlined />,
    description: "Set the ceiling of a token's life, and see the tokens that expire within 14 days.",
    action: 'token.read',
    render: () => <TokenAdmin api={tokens} />,
  };
}

/** The My tokens screen over `tokens`: a person's own tokens for the command line. */
export function myTokensScreen(tokens: TokensApi): Screen {
  return {
    id: 'my-tokens',
    title: 'My tokens',
    group: 'Account',
    icon: <KeyOutlined />,
    description: 'Issue and revoke your own tokens for the command line.',
    action: 'self.tokens',
    render: () => <MyTokens api={tokens} />,
  };
}
