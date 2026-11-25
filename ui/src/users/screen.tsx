import { AuditOutlined, KeyOutlined, SafetyCertificateOutlined, TeamOutlined } from '@ant-design/icons';
import type { Screen } from '../screens';
import { msg } from '../language';
import type { AuditApi, TokensApi, UsersApi } from './api';
import { AuditList } from './AuditList';
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
    title: msg('users.screen.users'),
    group: msg('frame.group.administration'),
    icon: <TeamOutlined />,
    description: msg('users.screen.users.description'),
    action: 'user.read',
    render: () => <UserList api={api} tokens={tokens} />,
  };
}

/** The Tokens screen over `tokens`: the installation's ceiling of a token's life, and the tokens that expire soon. */
export function tokensScreen(tokens: TokensApi): Screen {
  return {
    id: 'tokens',
    title: msg('users.screen.tokens'),
    group: msg('frame.group.administration'),
    icon: <SafetyCertificateOutlined />,
    description: msg('users.screen.tokens.description', { days: 14 }),
    action: 'token.read',
    render: () => <TokenAdmin api={tokens} />,
  };
}

/** The Audit screen over `api`: the whole audit, filtered by user, action and time, and its export as a CSV file. */
export function auditScreen(api: AuditApi): Screen {
  return {
    id: 'audit',
    title: msg('users.screen.audit'),
    group: msg('frame.group.administration'),
    icon: <AuditOutlined />,
    description: msg('users.screen.audit.description'),
    action: 'audit.read',
    render: () => <AuditList api={api} />,
  };
}

/** The My tokens screen over `tokens`: a person's own tokens for the command line. */
export function myTokensScreen(tokens: TokensApi): Screen {
  return {
    id: 'my-tokens',
    title: msg('users.screen.myTokens'),
    group: msg('frame.group.account'),
    icon: <KeyOutlined />,
    description: msg('users.screen.myTokens.description'),
    action: 'self.tokens',
    render: () => <MyTokens api={tokens} />,
  };
}
