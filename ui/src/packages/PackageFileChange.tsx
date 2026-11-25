import { Alert, Flex, Table, Tag, Typography } from 'antd';
import { useMemo } from 'react';
import { msg, say, useText, type Message } from '../language';
import type { SettingChange } from '../leaves';
import { packageFileChanges, type DependencyChange } from './packageFiles';

const TAG: Record<DependencyChange['status'], { color: string; label: Message }> = {
  added: { color: 'success', label: msg('packages.change.added') },
  removed: { color: 'error', label: msg('packages.change.removed') },
  changed: { color: 'warning', label: msg('packages.change.changed') },
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
  const texts = useText();
  const { t } = texts;
  if (changes.status === 'same') return <Typography.Text type="secondary">{t('packages.file.same')}</Typography.Text>;
  if (changes.status === 'formatting') return <Typography.Text>{t('packages.file.formatting')}</Typography.Text>;
  if (changes.status === 'unreadable') {
    return (
      <Flex vertical gap="small">
        {changes.unreadable.map((u) => (
          <Alert
            key={u.side}
            type="error"
            showIcon
            title={u.side === 'before' ? t('packages.file.older.unread') : t('packages.file.newer.unread')}
            description={say(texts, u.message)}
          />
        ))}
      </Flex>
    );
  }
  return (
    <Flex vertical gap="small">
      {changes.fields.length > 0 && (
        <Table<SettingChange>
          aria-label={t('packages.file.fields', { path })}
          size="small"
          pagination={false}
          rowKey="key"
          dataSource={changes.fields}
          columns={[
            { title: t('packages.file.field'), dataIndex: 'key' },
            { title: t('packages.file.before'), dataIndex: 'before', render: code },
            { title: t('packages.file.after'), dataIndex: 'after', render: code },
          ]}
        />
      )}
      {changes.dependencies.length > 0 && (
        <Table<DependencyChange>
          aria-label={t('packages.file.dependencies', { path })}
          size="small"
          pagination={false}
          rowKey="name"
          dataSource={changes.dependencies}
          columns={[
            { title: t('packages.dependency'), dataIndex: 'name' },
            { title: t('packages.file.versionBefore'), render: (_, d) => code(d.version.before) },
            { title: t('packages.file.versionAfter'), render: (_, d) => code(d.version.after) },
            { title: t('packages.file.pinBefore'), render: (_, d) => code(d.pin.before) },
            { title: t('packages.file.pinAfter'), render: (_, d) => code(d.pin.after) },
            { title: t('packages.file.status'), dataIndex: 'status', width: 110, render: (status: DependencyChange['status']) => <Tag color={TAG[status].color}>{say(texts, TAG[status].label)}</Tag> },
          ]}
        />
      )}
    </Flex>
  );
}
