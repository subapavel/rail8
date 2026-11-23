import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { ProTable, type ProColumns } from '@ant-design/pro-components';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Empty, Flex, Input } from 'antd';
import { useEffect, useState, type TableHTMLAttributes } from 'react';
import { packageColumn } from '../../packageColumn';
import { ReadSpinner } from '../../ReadSpinner';
import { describeError, type ApiClient, type MappingSummary } from '../api/client';
import { NewMappingModal } from './NewMappingModal';

const MappingsTable = (props: TableHTMLAttributes<HTMLTableElement>) => <table {...props} aria-label="Mappings" />;

const PAGE_SIZE = 25;

/** What a cell shows for an absent value: the folder of a mapping at the top, a message not chosen, no description. */
const orDash = (value: string | undefined) => (value ? value : '—');

/** Whether a mapping's name, package, messages or description contain `term`, ignoring case. */
function matches(mapping: MappingSummary, term: string): boolean {
  const lower = term.toLowerCase();
  return [mapping.name, mapping.package, mapping.source, mapping.target, mapping.description].some((value) => value?.toLowerCase().includes(lower));
}

export interface MappingListProps {
  api: ApiClient;
  /** Told the id of a mapping whose Open in studio was chosen. */
  onOpen: (id: string) => void;
  /** Told the id of a mapping the New mapping modal created. */
  onCreated: (id: string) => void;
}

/**
 * The Mappings screen: a table of the workspace's mappings with a toolbar of a search and New mapping, which opens the
 * New mapping modal; a mapping it creates joins the table. Each row shows its package, the first segment of its id, with
 * a filter on it, and its folder; its Open in studio opens the mapping. A warning above the toolbar says when the list
 * was cut at its limit.
 */
export function MappingList({ api, onOpen, onCreated }: MappingListProps) {
  const mappings = useQuery({ queryKey: ['mappings'], queryFn: ({ signal }) => api.listMappings(signal) });
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [creating, setCreating] = useState(false);
  const changeSearch = (value: string) => {
    setSearch(value);
    setPage(1);
  };
  const term = search.trim();
  // an empty term is in every name
  const shown = (mappings.data?.mappings ?? []).filter((m) => matches(m, term));
  const lastPage = Math.max(1, Math.ceil(shown.length / PAGE_SIZE));
  if (page > lastPage) setPage(lastPage);

  useEffect(() => {
    if (mappings.isSuccess) {
      // Load the editor code while the user chooses a mapping. Opening it handles errors and offers Retry.
      void import('../studio/StudioFrame').catch(() => undefined);
    }
  }, [mappings.isSuccess]);

  const failure = mappings.isError ? describeError(mappings.error) : undefined;
  const empty = mappings.isPending ? (
    <div role="status">
      <ReadSpinner />
      <h2>Loading the mappings</h2>
    </div>
  ) : failure ? (
    <Alert type="error" showIcon title={<h2>{failure.title}</h2>} description={failure.detail} />
  ) : (
    <Empty
      description={
        mappings.data?.mappings.length ? (
          <>
            <h2>No mappings match</h2>
            <p>No mapping's name, package, messages or description contain "{term}".</p>
          </>
        ) : (
          <>
            <h2>No mappings yet</h2>
            <p>Choose New mapping to create one; its messages are chosen in the studio.</p>
          </>
        )
      }
    >
      {mappings.data?.mappings.length ? <Button onClick={() => changeSearch('')}>Clear search</Button> : null}
    </Empty>
  );
  const column = (key: 'name' | 'package' | 'source' | 'target' | 'description', title: string) => ({
    title,
    dataIndex: key,
    render: (_: unknown, row: MappingSummary) => orDash(row[key]),
  });
  const columns: ProColumns<MappingSummary>[] = [
    column('name', 'Name'),
    packageColumn(mappings.data?.mappings ?? [], (row) => row.id),
    column('package', 'Folder'),
    column('source', 'Source'),
    column('target', 'Target'),
    column('description', 'Description'),
    {
      title: 'Actions',
      key: 'open',
      render: (_, row) => (
        <Button type="link" onClick={() => onOpen(row.id)}>
          Open in studio
        </Button>
      ),
    },
  ];

  return (
    <section className="rail8-page">
      <h1>Mappings</h1>
      {mappings.data?.truncated && <Alert type="warning" showIcon title="Only the first 1,000 mapping files of the working folder are listed" />}
      <ProTable<MappingSummary>
        rowKey="id"
        columns={columns}
        dataSource={shown}
        pagination={{ current: page, pageSize: PAGE_SIZE, onChange: setPage, hideOnSinglePage: true, showSizeChanger: false }}
        search={false}
        options={false}
        size="small"
        components={{ table: MappingsTable }}
        locale={{ emptyText: empty }}
        toolBarRender={() => [
          <Flex key="search" align="center">
            <Input
              aria-label="Find mappings"
              placeholder="Find mappings"
              prefix={<SearchOutlined aria-hidden />}
              value={search}
              onChange={(event) => changeSearch(event.target.value)}
              suffix={
                search && (
                  <Button type="text" size="small" aria-label="Reset" onClick={() => changeSearch('')}>
                    ×
                  </Button>
                )
              }
            />
          </Flex>,
          <Button key="new" type="primary" icon={<PlusOutlined aria-hidden />} onClick={() => setCreating(true)}>
            New mapping
          </Button>,
        ]}
      />
      {creating && (
        <NewMappingModal
          api={api}
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false);
            void queryClient.invalidateQueries({ queryKey: ['mappings'] });
            onCreated(id);
          }}
        />
      )}
    </section>
  );
}
