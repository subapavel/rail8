import { Button, Popconfirm, Typography } from 'antd';
import type { ReactNode } from 'react';
import { instant, useText, type Texts } from '../language';
import { Allowed } from '../SessionGate';
import type { LiveToken } from './api';

/** The tokens on one page of a list. */
export const TOKENS_PAGE_SIZE = 25;

/** A column of a list of tokens, which antd's Table and ProTable both take. */
export interface TokenColumn<T> {
  title: string;
  key: string;
  width?: number;
  render: (value: unknown, token: T) => ReactNode;
}

/**
 * The columns of a list of tokens, with titles in `texts`' language: its name, its id, when and by whom it was issued, its expiry and its last use; with
 * `user`, the column of the user each token belongs to first; with `revoke`, a Revoke in each row that asks first and is
 * disabled, saying why, when the caller may not do `action`.
 */
export function tokenColumns<T extends LiveToken>(
  texts: Texts,
  {
    user,
    revoke,
  }: {
  user?: (token: T) => string;
    revoke?: { action: string; run: (token: T) => Promise<void> };
  },
): TokenColumn<T>[] {
  const { t } = texts;
  const columns: TokenColumn<T>[] = [
    { title: t('users.token'), key: 'name', render: (_, token) => token.name },
    { title: t('users.token.id'), key: 'id', width: 190, render: (_, token) => <Typography.Text code>{token.id}</Typography.Text> },
    { title: t('users.token.issued'), key: 'createdAt', render: (_, token) => t('users.atBy', { at: instant(texts, token.createdAt), name: token.createdBy }) },
    { title: t('users.token.expires'), key: 'expiresAt', width: 210, render: (_, token) => instant(texts, token.expiresAt) },
    { title: t('users.token.lastUsed'), key: 'lastUsedAt', width: 210, render: (_, token) => (token.lastUsedAt === null ? '-' : instant(texts, token.lastUsedAt)) },
  ];
  if (user !== undefined) columns.unshift({ title: t('users.user'), key: 'user', render: (_, token) => user(token) });
  if (revoke !== undefined) {
    columns.push({
      title: t('users.actions'),
      key: 'actions',
      width: 100,
      render: (_, token) => (
        <Allowed action={revoke.action}>
          <RevokeButton
            title={user === undefined ? t('users.token.revoke.question', { token: token.name }) : t('users.token.revoke.questionOf', { token: token.name, name: user(token) })}
            onConfirm={() => revoke.run(token)}
          />
        </Allowed>
      ),
    });
  }
  return columns;
}

/** Revoke, which asks `title` before it runs `onConfirm`. */
function RevokeButton({ title, onConfirm, disabled }: { title: string; onConfirm: () => Promise<void>; disabled?: boolean }) {
  const { t } = useText();
  return (
    <Popconfirm
      title={title}
      description={t('users.token.revoke.description')}
      okText={t('users.token.revoke')}
      okButtonProps={{ danger: true }}
      onConfirm={onConfirm}
      disabled={disabled}
      destroyOnHidden
    >
      <Button type="link" size="small" disabled={disabled}>
        {t('users.token.revoke')}
      </Button>
    </Popconfirm>
  );
}
