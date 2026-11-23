import { Alert, Flex, Table, Tag, Typography } from 'antd';
import { useMemo } from 'react';
import type { SettingChange } from '../leaves';
import { packageFileChanges, type DependencyChange } from './packageFiles';

const TAG: Record<DependencyChange['status'], { color: string; label: string }> = {
  added: { color: 'success', label: 'Added' },
  removed: { color: 'error', label: 'Removed' },
  changed: { color: 'warning', label: 'Changed' },
};

const code = (value: string) => <Typography.Text code>{value}</Typography.Text>;

/**
 * The readable change of a `package.yaml`, a `package.lock` or a `folder.yaml` between two versions, each its text or
 * null when the file has none there: its fields, leaf by leaf, and its dependencies as a table of name, version and
 * pin, before and after. A change of comments or formatting only says so, and a version that is no YAML mapping says
 * why.
 */
export function PackageFileChange({ path, before, after }: { path: string; before: string | null; after: string | null }) {
  const changes = useMemo(() => packageFileChanges(before, after), [before, after]);
  if (changes.status === 'same') return <Typography.Text type="secondary">No change.</Typography.Text>;
  if (changes.status === 'formatting') return <Typography.Text>Only comments or formatting changed.</Typography.Text>;
  if (changes.status === 'unreadable') {
    return (
      <Flex vertical gap="small">
        {changes.unreadable.map((u) => (
          <Alert key={u.side} type="error" showIcon title={`The ${u.side === 'before' ? 'older' : 'newer'} version cannot be read`} description={u.message} />
        ))}
      </Flex>
    );
  }
  return (
    <Flex vertical gap="small">
      {changes.fields.length > 0 && (
        <Table<SettingChange>
          aria-label={`Fields of ${path}`}
          size="small"
          pagination={false}
          rowKey="key"
          dataSource={changes.fields}
          columns={[
            { title: 'Field', dataIndex: 'key' },
            { title: 'Before', dataIndex: 'before', render: code },
            { title: 'After', dataIndex: 'after', render: code },
          ]}
        />
      )}
      {changes.dependencies.length > 0 && (
        <Table<DependencyChange>
          aria-label={`Dependencies of ${path}`}
          size="small"
          pagination={false}
          rowKey="name"
          dataSource={changes.dependencies}
          columns={[
            { title: 'Dependency', dataIndex: 'name' },
            { title: 'Version before', render: (_, d) => code(d.version.before) },
            { title: 'Version after', render: (_, d) => code(d.version.after) },
            { title: 'Pin before', render: (_, d) => code(d.pin.before) },
            { title: 'Pin after', render: (_, d) => code(d.pin.after) },
            { title: 'Status', dataIndex: 'status', width: 110, render: (status: DependencyChange['status']) => <Tag color={TAG[status].color}>{TAG[status].label}</Tag> },
          ]}
        />
      )}
    </Flex>
  );
}
