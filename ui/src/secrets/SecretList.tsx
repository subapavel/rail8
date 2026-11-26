import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, App, Badge, Button, Descriptions, Flex, Space, Table, Typography } from 'antd';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { instant, useText } from '../language';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import { Allowed } from '../SessionGate';
import { KINDS, type KeyState, type MissingSecret, type NodeKeys, type SecretEntry, type SecretKind, type SecretList as Listed, type SecretsApi } from './api';
import { DeleteSecretDialog } from './DeleteSecretDialog';
import { kindWords, UsedBy } from './parts';
import { SecretDialog } from './SecretDialog';

/** The secrets on one page of the list. */
const PAGE_SIZE = 25;

type Shown = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed };

type Dialog = { kind: 'create'; name?: string; kinds?: SecretKind[] } | { kind: 'overwrite'; entry: SecretEntry } | { kind: 'delete'; entry: SecretEntry };

/** The kinds every user of `missing` takes, in the screen's order; every kind when its users name none in common. */
const kindsOf = (missing: MissingSecret): SecretKind[] => {
  const taken = KINDS.filter((kind) => missing.usedBy.every((user) => user.kinds.includes(kind)));
  return taken.length > 0 ? taken : [...KINDS];
};

/**
 * The Secrets screen: every secret of the store by its name, with its kind, its users, whether this node can read it, and
 * who changed it last and when; a key pair's row opens to its algorithm and size, its leaf certificate's subject, issuer and
 * end, and its fingerprint. Beside it the names that uses name and the store does not hold, the state of the store's key
 * on each live node, and the node that answered. No value is ever shown. A secret administrator creates a secret of each
 * kind, overwrites one with a value of its kind and deletes one, confirming with its name when it has a use; what the
 * caller may not do is disabled, saying which role it needs.
 */
export function SecretList({ api }: { api: SecretsApi }) {
  const [shown, setShown] = useState<Shown>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const { notification } = App.useApp();
  const texts = useText();
  const { t } = texts;

  useEffect(() => {
    let current = true;
    api.list().then(
      (list) => current && setShown({ kind: 'read', list }),
      (error: unknown) => current && setShown({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, asked]);

  const closed = (done: string | null) => {
    setDialog(null);
    if (done !== null) notification.success({ title: done, role: 'status' });
    refresh();
  };

  let body: ReactNode;
  if (shown.kind === 'reading') {
    body = <ReadSpinner label={t('secrets.reading')} />;
  } else if (shown.kind === 'failed') {
    body = <Alert type="error" showIcon title={t('secrets.unread')} description={shown.message} action={<Button onClick={refresh}>{t('frame.tryAgain')}</Button>} />;
  } else {
    const { list } = shown;
    body = (
      <Flex vertical gap="large">
        <ProTable<SecretEntry>
          aria-label={t('secrets.screen')}
          rowKey="name"
          size="small"
          dataSource={list.secrets}
          search={false}
          options={false}
          pagination={{ pageSize: PAGE_SIZE, hideOnSinglePage: true, showSizeChanger: false }}
          headerTitle={<h2>{t('secrets.screen')}</h2>}
          locale={{ emptyText: t('secrets.empty') }}
          toolBarRender={() => [
            <Typography.Text key="node" type="secondary">
              {t('secrets.node', { node: list.node })}
            </Typography.Text>,
            <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
              {t('secrets.refresh')}
            </Button>,
            <Allowed key="new" action="secret.write">
              <Button type="primary" icon={<PlusOutlined aria-hidden />} onClick={() => setDialog({ kind: 'create' })}>
                {t('secrets.new')}
              </Button>
            </Allowed>,
          ]}
          expandable={{ rowExpandable: (entry) => entry.keyPair !== undefined, expandedRowRender: (entry) => <KeyPairFacts entry={entry} /> }}
          columns={[
            { title: t('secrets.name'), dataIndex: 'name', render: (_, entry) => <Typography.Text code>{entry.name}</Typography.Text> },
            { title: t('secrets.kind'), dataIndex: 'kind', width: 150, render: (_, entry) => kindWords(texts, entry.kind) },
            { title: t('secrets.usedBy'), key: 'usedBy', render: (_, entry) => <UsedBy usedBy={entry.usedBy} users={entry.users} /> },
            {
              title: t('secrets.state'),
              key: 'state',
              width: 120,
              render: (_, entry) =>
                entry.unreadable ? <Badge status="error" text={t('secrets.unreadable')} /> : <Badge status="success" text={t('secrets.readable')} />,
            },
            {
              title: t('secrets.changed'),
              key: 'changed',
              width: 240,
              render: (_, entry) => t('secrets.changedBy', { by: entry.changedBy, at: instant(texts, entry.changedAt) }),
            },
            {
              title: t('secrets.actions'),
              key: 'actions',
              width: 170,
              render: (_, entry) => (
                <Space size={0}>
                  <Allowed action="secret.write">
                    <Button type="link" size="small" onClick={() => setDialog({ kind: 'overwrite', entry })}>
                      {t('secrets.overwrite.open')}
                    </Button>
                  </Allowed>
                  <Allowed action="secret.delete">
                    <Button type="link" size="small" danger onClick={() => setDialog({ kind: 'delete', entry })}>
                      {t('secrets.delete.open')}
                    </Button>
                  </Allowed>
                </Space>
              ),
            },
          ]}
        />
        <ProCard title={<h2>{t('secrets.missing')}</h2>} headerBordered extra={<Typography.Text type="secondary">{t('secrets.missing.note')}</Typography.Text>}>
          <Table<MissingSecret>
            aria-label={t('secrets.missing')}
            rowKey="name"
            size="small"
            dataSource={list.missing}
            pagination={false}
            locale={{ emptyText: t('secrets.missing.empty') }}
            columns={[
              { title: t('secrets.name'), dataIndex: 'name', render: (_, missing) => <Typography.Text code>{missing.name}</Typography.Text> },
              { title: t('secrets.usedBy'), key: 'usedBy', render: (_, missing) => <UsedBy usedBy={missing.usedBy} users={missing.users} /> },
              {
                title: t('secrets.actions'),
                key: 'actions',
                width: 170,
                render: (_, missing) => (
                  <Allowed action="secret.write">
                    <Button type="link" size="small" onClick={() => setDialog({ kind: 'create', name: missing.name, kinds: kindsOf(missing) })}>
                      {t('secrets.missing.create')}
                    </Button>
                  </Allowed>
                ),
              },
            ]}
          />
        </ProCard>
        <KeyCard state={list.key} />
      </Flex>
    );
  }

  return (
    <Page title={t('secrets.screen')} content={t('secrets.content')}>
      {body}
      {dialog?.kind === 'create' && (
        <SecretDialog
          api={api}
          {...(dialog.name === undefined ? {} : { name: dialog.name })}
          {...(dialog.kinds === undefined ? {} : { kinds: dialog.kinds })}
          onClose={(written) => closed(written === null ? null : t('secrets.created', { name: written.name }))}
        />
      )}
      {dialog?.kind === 'overwrite' && (
        <SecretDialog
          api={api}
          name={dialog.entry.name}
          kinds={[dialog.entry.kind]}
          overwrite
          onClose={(written) => closed(written === null ? null : t('secrets.overwritten', { name: written.name }))}
        />
      )}
      {dialog?.kind === 'delete' && (
        <DeleteSecretDialog api={api} entry={dialog.entry} onClose={(deleted) => closed(deleted ? t('secrets.deleted', { name: dialog.entry.name }) : null)} />
      )}
    </Page>
  );
}

/** What the store knows of a key pair: never its private key. */
function KeyPairFacts({ entry }: { entry: SecretEntry }) {
  const texts = useText();
  const { t } = texts;
  const facts = entry.keyPair!;
  return (
    <Descriptions
      size="small"
      column={2}
      aria-label={t('secrets.keyPair.of', { name: entry.name })}
      items={[
        { key: 'algorithm', label: t('secrets.keyPair.algorithm'), children: t('secrets.keyPair.size', { algorithm: facts.algorithm, size: facts.size }) },
        { key: 'notAfter', label: t('secrets.keyPair.notAfter'), children: facts.notAfter === undefined ? '-' : instant(texts, facts.notAfter) },
        { key: 'subject', label: t('secrets.keyPair.subject'), children: facts.subject ?? t('secrets.keyPair.noCertificate') },
        { key: 'issuer', label: t('secrets.keyPair.issuer'), children: facts.issuer ?? '-' },
        { key: 'fingerprint', label: t('secrets.keyPair.fingerprint'), span: 2, children: <Typography.Text code>{facts.fingerprint}</Typography.Text> },
      ]}
    />
  );
}

/** The key's state: each live node's key ids, the rows under another key than the common one, and whether a previous key is still set. */
function KeyCard({ state }: { state: KeyState }) {
  const { t } = useText();
  const keyId = (id: string | null) => (id === null ? '-' : <Typography.Text code>{id}</Typography.Text>);
  return (
    <ProCard title={<h2>{t('secrets.key')}</h2>} headerBordered>
      <Flex vertical gap="middle">
        <Descriptions
          size="small"
          column={3}
          items={[
            {
              key: 'common',
              label: t('secrets.key.common'),
              children: state.common === null ? <Badge status="warning" text={t('secrets.key.differ')} /> : keyId(state.common),
            },
            {
              key: 'rows',
              label: t('secrets.key.rows'),
              children:
                state.rowsUnderAnotherKey === 0 ? (
                  <Badge status="success" text={t('secrets.key.rows.none')} />
                ) : (
                  <Badge status="processing" text={t('secrets.key.rows.some', { count: state.rowsUnderAnotherKey })} />
                ),
            },
            {
              key: 'previous',
              label: t('secrets.key.previous'),
              children: state.previousSet ? <Badge status="warning" text={t('secrets.key.previous.set')} /> : <Badge status="success" text={t('secrets.key.previous.unset')} />,
            },
          ]}
        />
        <Table<NodeKeys>
          aria-label={t('secrets.key.nodes')}
          rowKey="node"
          size="small"
          dataSource={state.nodes}
          pagination={false}
          locale={{ emptyText: t('secrets.key.nodes.empty') }}
          columns={[
            { title: t('secrets.key.node'), dataIndex: 'node' },
            { title: t('secrets.key.current'), key: 'key', render: (_, node) => (node.joined ? keyId(node.key) : <Badge status="default" text={t('secrets.key.notJoined')} />) },
            { title: t('secrets.key.previousOf'), key: 'previous', render: (_, node) => keyId(node.previous) },
          ]}
        />
      </Flex>
    </ProCard>
  );
}
