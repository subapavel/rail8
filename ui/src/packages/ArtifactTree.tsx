import {
  ApartmentOutlined,
  ApiOutlined,
  ExperimentOutlined,
  FileOutlined,
  FolderAddOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  LockOutlined,
  ProfileOutlined,
  SettingOutlined,
  SwapOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { ProCard } from '@ant-design/pro-components';
import { Button, Empty, Flex, Input, Segmented, Space, Table, Tag, theme, Tooltip, Typography } from 'antd';
import { useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ArtifactKinds } from '../artifacts';
import { say, useText } from '../language';
import type { Screen } from '../screens';
import type { Artifact, Kind, PackagesApi, PackageState } from './api';
import { FolderDialog, type FolderDialogFor } from './FolderDialog';
import { ArtifactName, ChangeBadge } from './parts';
import { refusedIn } from './refusals';
import { filtered, filterOf, foldersOf, treeOf, type FolderNode, type KindFilter, type TreeNode } from './tree';
import { KIND } from './data';

/** The icon of each kind of file, before its name. */
const ICON: Record<Kind, ReactNode> = {
  flow: <ApartmentOutlined aria-hidden />,
  endpoint: <ApiOutlined aria-hidden />,
  mapping: <SwapOutlined aria-hidden />,
  test: <ExperimentOutlined aria-hidden />,
  package: <ProfileOutlined aria-hidden />,
  lock: <LockOutlined aria-hidden />,
  folder: <SettingOutlined aria-hidden />,
  file: <FileOutlined aria-hidden />,
};

/** The kind filters in their order, each with the text of its choice. */
const FILTERS: { value: KindFilter; label: string }[] = [
  { value: 'all', label: 'packages.tree.all' },
  { value: 'flow', label: 'packages.tree.flows' },
  { value: 'endpoint', label: 'packages.tree.endpoints' },
  { value: 'mapping', label: 'packages.tree.mappings' },
  { value: 'test', label: 'packages.tree.tests' },
  { value: 'file', label: 'packages.tree.files' },
];

/** A row of the table: a node, with its children as the table nests them, and none for an empty folder. */
type Row = TreeNode & { rows?: Row[] };
const rowsOf = (nodes: readonly TreeNode[]): Row[] =>
  nodes.map((node) => (node.type === 'folder' && node.children.length > 0 ? { ...node, rows: rowsOf(node.children) } : { ...node, rows: undefined }));

/** The folder within the top folder a node chooses for new artifacts: a folder itself, or the folder an artifact lies in. */
function folderOfKey(nodes: readonly TreeNode[], key: string | null): FolderNode | undefined {
  if (key === null) return undefined;
  const folders = foldersOf(nodes);
  return folders.find((f) => f.key === key) ?? folders.find((f) => f.children.some((child) => child.key === key));
}

/**
 * The artifacts of the folder `folder`, a package's or a folder that is no package, as one tree in a titled card: its
 * folders nested, folders first, each holding its artifacts of every kind and every other file, each with the icon
 * and the word of its kind, its name (an artifact opens in its editor's tab when a module owns its kind) and its change
 * since the last commit. A folder shows the namespace its `folder.yaml` gives, and for a package what its rules refuse
 * of it. Above the tree, a filter by kind and by text, and Expand all and Collapse all; the folders closed stay closed
 * while the tab lives, and a new folder shows, the folders that hold it opened. Choosing a folder's name chooses it for the New actions in the card's corner:
 * on a server that takes writes, each kind whose module can write a new one, among `creates` when it is given, writes
 * into the chosen folder; with none chosen, into its own folder in a package, and in a folder that is no package into
 * the folder its first artifact of that kind lies in; `onCreated` is told once it wrote one. With `folders`, as for a package,
 * New folder makes a folder in the chosen one, and each folder's Edit folder… sets its namespace and its description
 * (see {@link FolderDialog}); `folders.onState` is told the package's state after a write. `select`, an artifact's path,
 * is chosen and shown when the tree is first drawn.
 */
export function ArtifactTree({ artifacts, folder, writable, open, onCreated, creates, folders, select }: {
  artifacts: Artifact[];
  folder: string;
  writable: boolean;
  open: (screen: Screen) => void;
  onCreated: () => void;
  creates?: readonly string[];
  folders?: { api: PackagesApi; onState: (state: PackageState | null) => void };
  select?: string;
}) {
  const texts = useText();
  const { t } = texts;
  const { token } = theme.useToken();
  const kinds = useContext(ArtifactKinds);
  const tree = useMemo(() => treeOf(folder, artifacts, folders !== undefined), [folder, artifacts, folders]);
  const [kind, setKind] = useState<KindFilter>('all');
  const [text, setText] = useState('');
  const [closed, setClosed] = useState<ReadonlySet<string>>(() => new Set());
  const [chosen, setChosen] = useState<string | null>(select ?? null);
  const [dialog, setDialog] = useState<FolderDialogFor | null>(null);
  const table = useRef<HTMLDivElement>(null);

  const all = foldersOf(tree);
  const shown = filtered(tree, kind, text);
  const filtering = kind !== 'all' || text.trim() !== '';
  const expanded = foldersOf(shown)
    .map((f) => f.key)
    .filter((key) => filtering || !closed.has(key));
  const target = folderOfKey(tree, chosen);
  const counts = new Map<KindFilter, number>();
  for (const a of artifacts) if (a.kind !== 'folder') counts.set(filterOf(a), (counts.get(filterOf(a)) ?? 0) + 1);

  // the artifact Quick open asked for, in folders that all start open: its row scrolled into view once it is drawn
  useEffect(() => {
    if (select === undefined) return;
    const row = [...(table.current?.querySelectorAll<HTMLElement>('tr[data-row-key]') ?? [])].find((r) => r.dataset.rowKey === select);
    row?.scrollIntoView({ block: 'center' });
    // only as the tree is first drawn
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggle = (key: string, open: boolean) =>
    setClosed((keys) => {
      const next = new Set(keys);
      if (open) next.delete(key);
      else next.add(key);
      return next;
    });

  const News = [...kinds.values()].filter((k) => writable && k.New !== undefined && (creates === undefined || creates.includes(k.kind)));
  const within = target?.inside;
  // with no folder chosen: in a package, each kind's own folder; in a folder that is no package, the folder its first artifact of the kind lies in
  const withinFor = (kind: string) => {
    if (target !== undefined || folders !== undefined) return within;
    const first = artifacts.find((a) => a.kind === kind)?.path.slice(folder.length + 1);
    return first === undefined ? undefined : first.slice(0, Math.max(first.lastIndexOf('/'), 0));
  };
  const extra = (
    <Flex gap="small" align="center" wrap justify="flex-end">
      {writable && target !== undefined && (
        <Tag closable={{ 'aria-label': t('packages.tree.unchoose') }} onClose={() => setChosen(null)}>
          {t('packages.tree.in', { folder: target.inside })}
        </Tag>
      )}
      {writable && folders !== undefined && (
        <Button icon={<FolderAddOutlined aria-hidden />} onClick={() => setDialog({ under: within ?? '' })}>
          {t('packages.tree.newFolder')}
        </Button>
      )}
      {News.map(({ kind: k, New }) => New && <New key={k} folder={folder} within={withinFor(k)} open={open} onCreated={onCreated} />)}
    </Flex>
  );

  const used = artifacts.some((a) => a.kind === 'file');
  const nameOfRow = (row: Row) => {
    if (row.type === 'artifact') {
      return (
        <Space size="small">
          {ICON[row.artifact.kind]}
          <ArtifactName artifact={row.artifact} open={open} />
        </Space>
      );
    }
    const problems = row.problems.map((p) => refusedIn(texts, p));
    return (
      <Space size="small">
        {expanded.includes(row.key) ? <FolderOpenOutlined aria-hidden /> : <FolderOutlined aria-hidden />}
        <Button type="text" size="small" aria-pressed={chosen === row.key} onClick={() => setChosen(chosen === row.key ? null : row.key)}>
          {row.name}
        </Button>
        {row.settings?.namespace !== undefined && (
          <Tooltip title={t('packages.tree.namespace')}>
            <Tag variant="outlined" aria-label={t('packages.tree.namespaceOf', { namespace: row.settings.namespace })}>
              {row.settings.namespace}
            </Tag>
          </Tooltip>
        )}
        {problems.length > 0 && (
          <Tooltip title={problems.join(' ')}>
            <Typography.Text type="warning" role="img" aria-label={problems.join(' ')}>
              <WarningOutlined />
            </Typography.Text>
          </Tooltip>
        )}
      </Space>
    );
  };

  let body;
  if (artifacts.length === 0 && News.length === 0 && !(writable && folders !== undefined)) {
    body = <Empty description={t('packages.artifacts.none')} />;
  } else {
    body = (
      <Flex vertical gap="middle" ref={table}>
        <Flex gap="small" align="center" wrap>
          <Segmented<KindFilter>
            aria-label={t('packages.tree.kinds')}
            value={kind}
            onChange={setKind}
            options={FILTERS.filter((f) => f.value === 'all' || counts.has(f.value)).map((f) => ({
              value: f.value,
              label: t(f.label, { count: counts.get(f.value) ?? 0 }),
            }))}
          />
          <Input.Search allowClear aria-label={t('packages.tree.filter')} placeholder={t('packages.tree.filter')} value={text} onChange={(e) => setText(e.target.value)} style={{ width: token.controlHeight * 12 }} />
          <Button disabled={filtering} onClick={() => setClosed(new Set())}>
            {t('packages.tree.expandAll')}
          </Button>
          <Button disabled={filtering} onClick={() => setClosed(new Set(all.map((f) => f.key)))}>
            {t('packages.tree.collapseAll')}
          </Button>
        </Flex>
        {filtering && shown.length === 0 ? (
          <Empty description={t('packages.tree.noMatch')} />
        ) : (
          <Table<Row>
            aria-label={t('packages.tree.title')}
            rowKey="key"
            size="small"
            dataSource={rowsOf(shown)}
            pagination={false}
            rowClassName={(row) => (row.key === chosen ? 'rail8-chosen' : '')}
            expandable={{
              childrenColumnName: 'rows',
              expandedRowKeys: expanded,
              onExpand: (open, row) => !filtering && toggle(row.key, open),
            }}
            columns={[
              { title: t('packages.name'), key: 'name', width: used ? '40%' : undefined, ellipsis: { showTitle: false }, render: (_, row) => nameOfRow(row) },
              { title: t('packages.kind'), key: 'kind', width: 128, render: (_, row) => (row.type === 'folder' ? t('packages.tree.folder') : say(texts, KIND[row.artifact.kind])) },
              ...(used
                ? [
                    {
                      title: t('packages.usedBy'),
                      key: 'usedBy',
                      render: (_: unknown, row: Row) => (
                        <span style={{ overflowWrap: 'anywhere' }}>{row.type === 'artifact' ? (row.artifact.usedBy ?? []).map((p) => p.slice(p.indexOf('/') + 1)).join(', ') || '-' : '-'}</span>
                      ),
                    },
                  ]
                : []),
              {
                title: t('packages.change'),
                key: 'change',
                width: 128,
                render: (_, row) => {
                  const change = row.type === 'folder' ? row.settings?.change : row.artifact.change;
                  return change === undefined ? '-' : <ChangeBadge status={change} />;
                },
              },
              ...(writable && folders !== undefined
                ? [
                    {
                      title: t('packages.action'),
                      key: 'action',
                      width: 144,
                      render: (_: unknown, row: Row) =>
                        row.type === 'folder' ? (
                          <Button type="link" size="small" className="rail8-link" aria-label={t('packages.tree.editOf', { folder: row.inside })} onClick={() => setDialog({ edit: row.inside, settings: row.settings?.path })}>
                            {t('packages.tree.edit')}
                          </Button>
                        ) : (
                          '-'
                        ),
                    },
                  ]
                : []),
            ]}
          />
        )}
      </Flex>
    );
  }

  return (
    <>
      <ProCard title={<h2>{t('packages.tree.title')}</h2>} headerBordered extra={extra}>
        {body}
      </ProCard>
      {dialog !== null && folders !== undefined && (
        <FolderDialog
          api={folders.api}
          repository={folder}
          of={dialog}
          namespaces={all.map((f) => ({ inside: f.inside, namespace: f.settings?.namespace }))}
          onState={folders.onState}
          onWritten={(inside) => {
            setDialog(null);
            const key = `${folder}/${inside}/`;
            setChosen(key);
            // the new folder shows: it and the folders that hold it open
            setClosed((keys) => new Set([...keys].filter((closedKey) => !key.startsWith(closedKey))));
          }}
          onClose={() => setDialog(null)}
        />
      )}
    </>
  );
}
