import { Alert, Flex, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { Side } from '../artifacts';
import { say, useText, type Texts } from '../language';
import { ReadSpinner } from '../ReadSpinner';
import type { EndpointsApi } from './api';
import { endpointChanges, type EndpointChanges } from './endpointChanges';
import type { ComponentForms } from './settings';

export interface EndpointChangeProps {
  /** The node's components, whose settings descriptions title the options. */
  api: EndpointsApi;
  /** The endpoint file's path, relative to the working folder. */
  path: string;
  before: Side | null;
  after: Side | null;
}

/** One line of the table of changes. */
interface Line {
  key: string;
  part: string;
  status?: 'added' | 'removed';
  setting: string;
  before: string;
  after: string;
}

/** The lines of `changes`: the envelope, the options, then each environment's overlay, their parts named in `texts`' language. */
function linesOf({ t }: Texts, changes: EndpointChanges): Line[] {
  return [
    ...changes.envelope.map((c) => ({ key: `envelope:${c.key}`, part: t('endpoints.list.endpoint'), setting: c.key, before: c.before, after: c.after })),
    ...changes.config.map((c) => ({ key: `config:${c.key}`, part: t('endpoints.raw.label'), setting: c.title, before: c.before, after: c.after })),
    ...changes.environments.flatMap((e) =>
      e.config.map((c) => ({
        key: `environment:${e.name}:${c.key}`,
        part: t('endpoints.change.environment', { name: e.name }),
        ...(e.status === 'changed' ? {} : { status: e.status }),
        setting: c.title,
        before: c.before,
        after: c.after,
      })),
    ),
  ];
}

/**
 * The readable change of an endpoint file between two versions: its scheme and direction first, then its options leaf
 * by leaf, under the field titles of its component's settings description where the node gives them and else under
 * their key paths, then each environment's overlay. A change of comments or formatting only says so, and a version
 * the settings form cannot read says why.
 */
export function EndpointChange({ api, path, before, after }: EndpointChangeProps) {
  const texts = useText();
  const { t } = texts;
  const [descriptions, setDescriptions] = useState<ReadonlyMap<string, ComponentForms> | { failed: string } | null>(null);

  useEffect(() => {
    let current = true;
    api.components().then(
      (node) => current && setDescriptions(new Map(node.items.map((c) => [c.scheme, c.settings]))),
      (error: unknown) => current && setDescriptions({ failed: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api]);

  const described = useMemo(() => (descriptions === null || 'failed' in descriptions ? new Map<string, ComponentForms>() : descriptions), [descriptions]);
  const changes = useMemo(() => endpointChanges(before?.text ?? null, after?.text ?? null, described), [before, after, described]);

  if (changes.status === 'same') return <Typography.Text type="secondary">{t('endpoints.change.none')}</Typography.Text>;
  if (changes.status === 'formatting') return <Typography.Text>{t('endpoints.change.formatting')}</Typography.Text>;
  if (changes.status === 'unreadable') {
    return (
      <Flex vertical gap="small">
        {changes.unreadable.map((u) => (
          <Alert key={u.side} type="error" showIcon title={t(u.side === 'before' ? 'endpoints.change.olderUnread' : 'endpoints.change.newerUnread')} description={say(texts, u.message)} />
        ))}
      </Flex>
    );
  }
  if (descriptions === null) return <ReadSpinner label={t('endpoints.change.describing')} />;
  return (
    <Flex vertical gap="small">
      {changes.status === 'added' && <Alert type="success" showIcon title={t('endpoints.change.added')} />}
      {changes.status === 'deleted' && <Alert type="warning" showIcon title={t('endpoints.change.deleted')} />}
      {'failed' in descriptions && (
        <Alert type="info" showIcon title={t('endpoints.change.undescribed')} description={descriptions.failed} />
      )}
      <Table<Line>
        aria-label={t('endpoints.change.table', { path })}
        size="small"
        pagination={false}
        rowKey="key"
        dataSource={linesOf(texts, changes)}
        columns={[
          {
            title: t('endpoints.change.part'),
            dataIndex: 'part',
            width: 200,
            render: (part: string, line) => (
              <Flex gap="small" wrap>
                {part}
                {line.status !== undefined && <Tag color={line.status === 'added' ? 'success' : 'error'}>{t(line.status === 'added' ? 'endpoints.change.addedTag' : 'endpoints.change.removedTag')}</Tag>}
              </Flex>
            ),
          },
          { title: t('endpoints.change.setting'), dataIndex: 'setting' },
          { title: t('endpoints.change.before'), dataIndex: 'before', render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
          { title: t('endpoints.change.after'), dataIndex: 'after', render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
        ]}
      />
    </Flex>
  );
}
