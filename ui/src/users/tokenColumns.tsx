import { Button, Popconfirm, Typography } from 'antd';
import type { ReactNode } from 'react';
import { Allowed } from '../SessionGate';
import type { LiveToken } from './api';
import { when } from './words';

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
 * The columns of a list of tokens: its name, its id, when and by whom it was issued, its expiry and its last use; with
 * `user`, the column of the user each token belongs to first; with `revoke`, a Revoke in each row that asks first and is
 * disabled, saying why, when the caller may not do `action`.
 */
export function tokenColumns<T extends LiveToken>({
  user,
  revoke,
}: {
  user?: (token: T) => string;
  revoke?: { action: string; run: (token: T) => Promise<void> };
}): TokenColumn<T>[] {
  const columns: TokenColumn<T>[] = [
    { title: 'Token', key: 'name', render: (_, t) => t.name },
    { title: 'Id', key: 'id', width: 190, render: (_, t) => <Typography.Text code>{t.id}</Typography.Text> },
    { title: 'Issued', key: 'createdAt', render: (_, t) => `${when(t.createdAt)} by ${t.createdBy}` },
    { title: 'Expires', key: 'expiresAt', width: 180, render: (_, t) => when(t.expiresAt) },
    { title: 'Last used', key: 'lastUsedAt', width: 180, render: (_, t) => (t.lastUsedAt === null ? '-' : when(t.lastUsedAt)) },
  ];
  if (user !== undefined) columns.unshift({ title: 'User', key: 'user', render: (_, t) => user(t) });
  if (revoke !== undefined) {
    columns.push({
      title: 'Actions',
      key: 'actions',
      width: 100,
      render: (_, t) => (
        <Allowed action={revoke.action}>
          <RevokeButton title={user === undefined ? `Revoke the token ${t.name}?` : `Revoke the token ${t.name} of ${user(t)}?`} onConfirm={() => revoke.run(t)} />
        </Allowed>
      ),
    });
  }
  return columns;
}

/** Revoke, which asks `title` before it runs `onConfirm`. */
function RevokeButton({ title, onConfirm, disabled }: { title: string; onConfirm: () => Promise<void>; disabled?: boolean }) {
  return (
    <Popconfirm
      title={title}
      description="Whoever holds it is refused from the next request on."
      okText="Revoke"
      okButtonProps={{ danger: true }}
      onConfirm={onConfirm}
      disabled={disabled}
      destroyOnHidden
    >
      <Button type="link" size="small" disabled={disabled}>
        Revoke
      </Button>
    </Popconfirm>
  );
}
