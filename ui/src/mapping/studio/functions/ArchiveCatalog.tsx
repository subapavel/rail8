import { Button, Flex, Input, Table, Tag } from 'antd';
import { MountedModal } from '../../../MountedModal';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useRef, useState, type FormEvent } from 'react';
import { useText } from '../../../language';
import { ARCHIVE_EXAMPLES, type Archive } from '../mock/function-model';
import { Tool } from '../shared/Tool';

/**
 * The picker of an archive or an archive namespace for Archives Used: a tabular search help, without search criteria
 * and without a hierarchical help, whose root would be a software component version, which the studio does not have. Go
 * moves to the next row that contains the search text; a click selects a row, a double click applies it.
 */
export function ArchiveCatalog({ kind, onApply, close }: { kind: 'archive' | 'namespace'; onApply: (entry: Archive) => void; close: () => void }) {
  const namespaces = kind === 'namespace';
  const { t } = useText();
  const rows: readonly Archive[] = namespaces
    ? [...new Set(ARCHIVE_EXAMPLES.map((r) => r.namespace))].map((name) => ({ name, namespace: name }))
    : ARCHIVE_EXAMPLES;
  const columns: [string, keyof Archive][] = namespaces
    ? [[t('studio.lookup.namespace'), 'name']]
    : [
        [t('studio.name'), 'name'],
        [t('studio.lookup.namespace'), 'namespace'],
      ];
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState('');
  const [hit, setHit] = useState('');
  const [notice, setNotice] = useState('');
  const table = useRef<HTMLDivElement>(null);

  const chosen = rows.find((r) => r.name === selected);
  function go(event: FormEvent) {
    event.preventDefault();
    const q = query.trim().toLowerCase();
    const matches = rows.filter((r) => columns.some(([, key]) => r[key].toLowerCase().includes(q)));
    if (!matches.length) {
      setHit('');
      setNotice(t('studio.archive.noMatch'));
      return;
    }
    const next = matches[(matches.findIndex((r) => r.name === hit) + 1) % matches.length]!;
    setHit(next.name);
    setNotice(t('studio.archive.matched', { name: next.name }));
    table.current?.querySelector(`[data-row-key="${CSS.escape(next.name)}"]`)?.scrollIntoView({ block: 'nearest' });
  }
  function refresh() {
    setQuery('');
    setSelected('');
    setHit('');
    setNotice('');
  }
  return (
    <MountedModal
      title={t(namespaces ? 'studio.archive.chooseNamespace' : 'studio.archive.choose')}
      onCancel={close}
      cancelText={t('frame.cancel')}
      okText={t('studio.apply')}
      onOk={() => chosen && onApply(chosen)}
      okButtonProps={{ disabled: !chosen }}
    >
      <h3>{t('studio.lookup.result')}</h3>
      <form onSubmit={go}>
        <Flex align="center" gap="small">
          <Tool label={t('studio.archive.refresh')} icon={ReloadOutlined} onClick={refresh} />

          <Input
            autoFocus
            aria-label={t('studio.archive.search')}
            placeholder={t('studio.archive.find')}
            prefix={<SearchOutlined />}
            value={query}
            onChange={(event) => {
              const value = event.target.value;
              setQuery(value);
              setHit('');
              setNotice('');
            }}
          />

          <Button htmlType="submit" size="small" type="default" disabled={!query.trim()}>
            {t('studio.archive.go')}
          </Button>
        </Flex>
      </form>

      <div ref={table}>
        <Table<Archive>
          aria-label={t('studio.archive.results')}
          rowKey="name"
          size="small"
          pagination={false}
          dataSource={rows}
          rowClassName={(row) => (row.name === selected ? 'studio-chosen' : '')}
          onRow={(row) => ({ onClick: () => setSelected(row.name), onDoubleClick: () => onApply(row) })}
          columns={columns.map(([title, field], index) => ({
            title,
            dataIndex: field,
            render: (_, row) =>
              index === 0 ? (
                <>
                  <Button type="link" aria-pressed={row.name === selected} onClick={() => setSelected(row.name)}>
                    {row[field]}
                  </Button>
                  {hit === row.name && <Tag color="blue">{t('studio.archive.match')}</Tag>}
                </>
              ) : (
                row[field]
              ),
          }))}
        />
      </div>
      <p role="status">{notice}</p>
    </MountedModal>
  );
}
