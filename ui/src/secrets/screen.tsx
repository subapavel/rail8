import { LockOutlined } from '@ant-design/icons';
import { msg } from '../language';
import type { Screen } from '../screens';
import type { SecretsApi } from './api';
import { SecretList } from './SecretList';

/**
 * The Secrets screen over `api`: the secrets that flows, connections and the platform use, without their values, which a
 * developer reads and a secret administrator creates, overwrites and deletes there.
 */
export function secretsScreen(api: SecretsApi): Screen {
  return {
    id: 'secrets',
    title: msg('secrets.screen'),
    group: msg('frame.group.administration'),
    icon: <LockOutlined />,
    description: msg('secrets.screen.description'),
    action: 'secret.list',
    render: () => <SecretList api={api} />,
  };
}
