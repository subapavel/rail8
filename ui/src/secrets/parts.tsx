import { Flex, Tag, Typography } from 'antd';
import { msg, say, useText, type Message, type Texts } from '../language';
import type { SecretKind, SecretUser } from './api';

/** The words of each kind of secret. */
export const KIND_WORDS: Record<SecretKind, Message> = {
  value: msg('secrets.kind.value'),
  credentials: msg('secrets.kind.credentials'),
  'key-pair': msg('secrets.kind.keyPair'),
};

/** The ids of the words of each kind of user, each taking the user's `id`. */
const USER_WORDS: Record<string, string> = {
  flow: 'secrets.user.flow',
  drain: 'secrets.user.drain',
  connection: 'secrets.user.connection',
  credential: 'secrets.user.credential',
  platform: 'secrets.user.platform',
};

/** A user of a secret in words, such as "Flow orders/order-in"; a kind of user the screen does not know shows its id. */
export const userWords = ({ t }: Texts, user: SecretUser) =>
  Object.hasOwn(USER_WORDS, user.kind) ? t(USER_WORDS[user.kind]!, { id: user.id }) : `${user.kind} ${user.id}`;

/** The users of a secret as tags, and how many more there are than `usedBy` names; `-` when it has none. */
export function UsedBy({ usedBy, users }: { usedBy: readonly SecretUser[]; users: number }) {
  const texts = useText();
  if (users === 0 && usedBy.length === 0) return '-';
  return (
    <Flex wrap gap="small">
      {usedBy.map((user) => (
        <Tag key={`${user.kind}/${user.id}`}>{userWords(texts, user)}</Tag>
      ))}
      {users > usedBy.length && <Typography.Text type="secondary">{texts.t('secrets.user.more', { count: users - usedBy.length })}</Typography.Text>}
    </Flex>
  );
}

/** The kind of a secret in words. */
export const kindWords = (texts: Texts, kind: SecretKind) => say(texts, KIND_WORDS[kind]);
