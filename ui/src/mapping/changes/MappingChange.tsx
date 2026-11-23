import { ProTable } from '@ant-design/pro-components';
import { Alert, Flex, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { Side } from '../../artifacts';
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

const COLOR: Record<MappingRow['status'], string> = { added: 'success', removed: 'error', changed: 'warning', 'structure changed': 'warning' };
const LABEL: Record<MappingRow['status'], string> = { added: 'Added', removed: 'Removed', changed: 'Changed', 'structure changed': 'Structure changed' };

/**
 * The readable change of a mapping file between two versions, as a table of target field, before, after and status:
 * the source's and the target's structures, each target field added, removed or changed as one formula a side, the
 * UDFs with their source files as line diffs, and the description. Target fields whose boxes only moved are counted
 * once; boxes only numbered differently are no change. A version that is no mapping says why.
 */
export function MappingChange({ path, before, after }: MappingChangeProps) {
  const [read, setRead] = useState<{ before: Side | null; after: Side | null; changes: MappingChanges } | { failed: string } | null>(null);

  useEffect(() => {
    let current = true;
    Promise.all([sourcesOf(path, before), sourcesOf(path, after)])
      .then(([b, a]) => mappingChanges(path, before?.text ?? null, after?.text ?? null, { before: b, after: a }))
      .then(
        (changes) => current && setRead({ before, after, changes }),
        (error: unknown) => current && setRead({ failed: error instanceof Error ? error.message : String(error) }),
      );
    return () => {
      current = false;
    };
  }, [path, before, after]);

  if (read === null || ('changes' in read && (read.before !== before || read.after !== after))) return <ReadSpinner label={`Reading the UDF sources of ${path}`} />;
  if ('failed' in read) return <Alert type="error" showIcon title="The change could not be read" description={read.failed} />;
  const { changes } = read;
  if (changes.status === 'same') return <Typography.Text type="secondary">No change.</Typography.Text>;
  if (changes.status === 'formatting') return <Typography.Text>Only the formatting changed.</Typography.Text>;
  if (changes.status === 'unreadable') {
    return (
      <Flex vertical gap="small">
        {changes.unreadable.map((u) => (
          <Alert key={u.side} type="error" showIcon title={`The ${u.side === 'before' ? 'older' : 'newer'} version cannot be read`} description={u.message} />
        ))}
      </Flex>
    );
  }
  const rows: MappingRow[] =
    changes.moved === 0
      ? changes.rows
      : [...changes.rows, { key: 'moved', field: `Boxes moved in ${changes.moved} target ${changes.moved === 1 ? 'field' : 'fields'}`, before: '', after: '', status: 'changed' }];
  if (rows.length === 0) return <Typography.Text>No target field changed: the boxes are only numbered differently.</Typography.Text>;
  return (
    <Flex vertical gap="small">
      {changes.status === 'added' && <Alert type="success" showIcon title="Added: every target field is new." />}
      {changes.status === 'deleted' && <Alert type="warning" showIcon title="Deleted: every target field is removed." />}
      <ProTable<MappingRow>
        aria-label={`Changes of ${path}`}
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
          { title: 'Target field', dataIndex: 'field', render: (_, row) => <Typography.Text code={row.field.startsWith('/')}>{row.field}</Typography.Text> },
          { title: 'Before', dataIndex: 'before', render: (_, row) => (row.before === '' ? null : <Typography.Text code>{row.before}</Typography.Text>) },
          { title: 'After', dataIndex: 'after', render: (_, row) => (row.after === '' ? null : <Typography.Text code>{row.after}</Typography.Text>) },
          { title: 'Status', dataIndex: 'status', width: 150, render: (_, row) => <Tag color={COLOR[row.status]}>{LABEL[row.status]}</Tag> },
        ]}
      />
    </Flex>
  );
}
