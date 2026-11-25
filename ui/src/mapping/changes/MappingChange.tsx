import { ProTable } from '@ant-design/pro-components';
import { Alert, Flex, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { Side } from '../../artifacts';
import { useText } from '../../language';
import { LineDiff } from '../../LineDiff';
import { ReadSpinner } from '../../ReadSpinner';
import { mappingChanges, udfSourcePaths, type MappingChanges, type MappingRow } from './mappingChanges';

export interface MappingChangeProps {
  /** The mapping file's path, relative to the working folder. */
  path: string;
  before: Side | null;
  after: Side | null;
}

/** The texts of the UDF source files `side` names, each read at the side's revision. */
async function sourcesOf(path: string, side: Side | null): Promise<Map<string, string | null>> {
  if (side === null) return new Map();
  const paths = udfSourcePaths(path, side.text);
  return new Map(await Promise.all(paths.map(async (p) => [p, await side.read(p)] as const)));
}

const COLOR: Record<MappingRow['status'], string> = { added: 'success', removed: 'error', changed: 'warning', 'structure changed': 'warning' }; // untranslated: statuses
/** The id of the text of each status. */
const LABEL: Record<MappingRow['status'], string> = {
  added: 'mapping.change.status.added',
  removed: 'mapping.change.status.removed',
  changed: 'mapping.change.status.changed',
  'structure changed': 'mapping.change.status.structure', // untranslated: a status
};

/**
 * The readable change of a mapping file between two versions, as a table of target field, before, after and status:
 * the source's and the target's structures, each target field added, removed or changed as one formula a side, the
 * UDFs with their source files as line diffs, and the description. Target fields whose boxes only moved are counted
 * once; boxes only numbered differently are no change. A version that is no mapping says why.
 */
export function MappingChange({ path, before, after }: MappingChangeProps) {
  const [read, setRead] = useState<{ before: Side | null; after: Side | null; changes: MappingChanges } | { failed: string } | null>(null);
  const texts = useText();
  const { t } = texts;

  useEffect(() => {
    let current = true;
    Promise.all([sourcesOf(path, before), sourcesOf(path, after)])
      .then(([b, a]) => mappingChanges(path, before?.text ?? null, after?.text ?? null, { before: b, after: a }, texts))
      .then(
        (changes) => current && setRead({ before, after, changes }),
        (error: unknown) => current && setRead({ failed: error instanceof Error ? error.message : String(error) }),
      );
    return () => {
      current = false;
    };
  }, [path, before, after, texts]);

  if (read === null || ('changes' in read && (read.before !== before || read.after !== after))) return <ReadSpinner label={t('mapping.change.reading', { path })} />;
  if ('failed' in read) return <Alert type="error" showIcon title={t('mapping.change.unread')} description={read.failed} />;
  const { changes } = read;
  if (changes.status === 'same') return <Typography.Text type="secondary">{t('mapping.change.same')}</Typography.Text>;
  if (changes.status === 'formatting') return <Typography.Text>{t('mapping.change.formatting')}</Typography.Text>;
  if (changes.status === 'unreadable') {
    return (
      <Flex vertical gap="small">
        {changes.unreadable.map((u) => (
          <Alert key={u.side} type="error" showIcon title={t(`mapping.change.unreadable.${u.side}`)} description={u.message} />
        ))}
      </Flex>
    );
  }
  const rows: MappingRow[] =
    changes.moved === 0
      ? changes.rows
      : [...changes.rows, { key: 'moved', field: t('mapping.change.moved', { count: changes.moved }), before: '', after: '', status: 'changed' }];
  if (rows.length === 0) return <Typography.Text>{t('mapping.change.renumbered')}</Typography.Text>;
  return (
    <Flex vertical gap="small">
      {changes.status === 'added' && <Alert type="success" showIcon title={t('mapping.change.added')} />}
      {changes.status === 'deleted' && <Alert type="warning" showIcon title={t('mapping.change.deleted')} />}
      <ProTable<MappingRow>
        aria-label={t('mapping.change.table', { path })}
        rowKey="key"
        size="small"
        dataSource={rows}
        search={false}
        options={false}
        toolBarRender={false}
        pagination={false}
        expandable={{
          rowExpandable: (row) => row.diff !== undefined,
          expandedRowRender: (row) => (row.diff === undefined ? null : <LineDiff before={row.diff.before} after={row.diff.after} />),
        }}
        columns={[
          { title: t('mapping.change.column.field'), dataIndex: 'field', render: (_, row) => <Typography.Text code={row.field.startsWith('/')}>{row.field}</Typography.Text> },
          { title: t('mapping.change.column.before'), dataIndex: 'before', render: (_, row) => (row.before === '' ? null : <Typography.Text code>{row.before}</Typography.Text>) },
          { title: t('mapping.change.column.after'), dataIndex: 'after', render: (_, row) => (row.after === '' ? null : <Typography.Text code>{row.after}</Typography.Text>) },
          { title: t('mapping.change.column.status'), dataIndex: 'status', width: 150, render: (_, row) => <Tag color={COLOR[row.status]}>{t(LABEL[row.status])}</Tag> },
        ]}
      />
    </Flex>
  );
}
