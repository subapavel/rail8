import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { ProTable, type ProColumns } from '@ant-design/pro-components';
import { Alert, Button, Flex, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AUDIT_ACTIONS, auditAction, auditActionName, AuditHow } from '../audit';
import { useText, type Texts } from '../language';
import { Page } from '../Page';
import type { AuditApi, AuditEntry, AuditQuery } from './api';
import { auditCsv, readAll } from './auditExport';
import { reasonOf } from './words';

export interface AuditListProps {
  api: AuditApi;
  /** The time the name of an exported file carries; the browser's clock by default. */
  now?: () => Date;
  /** Hands the exported file to the browser to save; a link the page clicks by default. */
  save?: (name: string, file: Blob) => void;
}

/** What the list read last: its rows, the cursor of the next page, and whether that page is being read or could not be. */
type Shown =
  | { kind: 'reading' }
  | { kind: 'failed'; message: string }
  | { kind: 'read'; rows: AuditEntry[]; next: string | null; more: 'idle' | 'reading' | { failed: string } };

/** Where an export stands: not asked, reading so many rows, too long, or refused. */
type Export = { kind: 'idle' } | { kind: 'reading'; count: number } | { kind: 'too-many'; max: number } | { kind: 'failed'; message: string };

/** The values of the search form: the user's name, the action, and the time as two ISO-8601 instants. */
interface Searched {
  user?: string;
  action?: string;
  from?: string;
  to?: string;
}

const BROWSER_CLOCK = () => new Date();

/** Saves `file` as `name` through a link the page clicks. */
function saveWithLink(name: string, file: Blob) {
  const url = URL.createObjectURL(file);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

/** The query of what the search form holds; an empty field keeps every row. */
function queryOf(searched: Searched): AuditQuery {
  const query: AuditQuery = {};
  if (searched.user !== undefined && searched.user.trim() !== '') query.user = searched.user.trim();
  if (searched.action !== undefined && searched.action !== '') query.action = searched.action;
  if (searched.from !== undefined) query.from = searched.from;
  if (searched.to !== undefined) query.to = searched.to;
  return query;
}

/** The columns of the list and the fields of its search form, titled in `texts`' language. */
const columnsIn = (texts: Texts): ProColumns<AuditEntry>[] => [
  { title: texts.t('users.user'), dataIndex: 'user', hideInTable: true, fieldProps: { placeholder: texts.t('audit.anyUser') } },
  {
    title: texts.t('audit.time'),
    dataIndex: 'time',
    valueType: 'dateTimeRange',
    hideInTable: true,
    fieldProps: { placeholder: [texts.t('audit.from'), texts.t('audit.to')] },
    search: { transform: (value?: [string, string]) => (value === undefined ? {} : { from: value[0], to: value[1] }) },
  },
  { title: texts.t('users.when'), dataIndex: 'at', key: 'at', width: 230, search: false, render: (_, row) => row.at },
  {
    title: texts.t('audit.action'),
    dataIndex: 'action',
    valueType: 'select',
    valueEnum: Object.fromEntries(AUDIT_ACTIONS.map((id) => [id, { text: auditActionName(texts, id) }])),
    fieldProps: { showSearch: true, virtual: false, placeholder: texts.t('audit.anyAction') },
    render: (_, row) => auditAction(texts, row),
  },
  { title: texts.t('audit.subject'), dataIndex: 'subject', search: false, render: (_, row) => row.subject },
  { title: texts.t('audit.who'), dataIndex: 'actor', search: false, render: (_, row) => row.actor },
  { title: texts.t('users.how'), key: 'how', search: false, render: (_, row) => <AuditHow row={row} /> },
  {
    title: texts.t('audit.detail'),
    key: 'detail',
    search: false,
    ellipsis: true,
    render: (_, row) => (Object.keys(row.detail).length === 0 ? '-' : <Typography.Text code>{JSON.stringify(row.detail)}</Typography.Text>),
  },
];

/**
 * The administrator's Audit screen: the whole audit, newest first, a page at a time with Load more, narrowed by a user's
 * name, an action and a time when the search form is submitted. Each row shows when, what was done to what, who did it, and
 * how they proved who they are with the id of their session or token. Export CSV reads every page of the rows the form keeps,
 * up to 100,000, and saves them as a CSV file; a longer list asks for a narrower time.
 */
export function AuditList({ api, now = BROWSER_CLOCK, save = saveWithLink }: AuditListProps) {
  const [query, setQuery] = useState<AuditQuery>({});
  const [asked, setAsked] = useState(0);
  const [shown, setShown] = useState<Shown>({ kind: 'reading' });
  const [exported, setExported] = useState<Export>({ kind: 'idle' });
  // only the answer to the latest read of the list is shown
  const latest = useRef(0);
  const texts = useText();
  const { t } = texts;
  const columns = useMemo(() => columnsIn(texts), [texts]);

  useEffect(() => {
    const read = ++latest.current;
    api.read(query).then(
      (page) => read === latest.current && setShown({ kind: 'read', rows: page.items, next: page.next, more: 'idle' }),
      (error: unknown) => read === latest.current && setShown({ kind: 'failed', message: reasonOf(error) }),
    );
  }, [api, query, asked]);

  const readAgain = useCallback((next: AuditQuery) => {
    setShown({ kind: 'reading' });
    setQuery(next);
    setAsked((n) => n + 1);
  }, []);

  const loadMore = (rows: AuditEntry[], after: string) => {
    const read = ++latest.current;
    setShown({ kind: 'read', rows, next: after, more: 'reading' });
    api.read(query, after).then(
      (page) => read === latest.current && setShown({ kind: 'read', rows: [...rows, ...page.items], next: page.next, more: 'idle' }),
      (error: unknown) => read === latest.current && setShown({ kind: 'read', rows, next: after, more: { failed: reasonOf(error) } }),
    );
  };

  const exportCsv = async () => {
    setExported({ kind: 'reading', count: 0 });
    try {
      const all = await readAll(api, query, (count) => setExported({ kind: 'reading', count }));
      if (all.kind === 'too-many') {
        setExported(all);
        return;
      }
      const stamp = now().toISOString().slice(0, 19).replaceAll(':', '-');
      save(`rail8-audit-${stamp}Z.csv`, new Blob([auditCsv(all.rows)], { type: 'text/csv;charset=utf-8' }));
      setExported({ kind: 'idle' });
    } catch (error) {
      setExported({ kind: 'failed', message: reasonOf(error) });
    }
  };

  const reading = exported.kind === 'reading';
  return (
    <Page title={t('users.screen.audit')} content={t('audit.content')}>
      <Flex vertical gap="large">
        {exported.kind === 'too-many' && (
          <Alert
            type="warning"
            showIcon
            closable={{ 'aria-label': t('users.dismiss') }}
            onClose={() => setExported({ kind: 'idle' })}
            title={t('audit.tooMany', { max: exported.max })}
            description={t('audit.tooMany.description')}
          />
        )}
        {exported.kind === 'failed' && (
          <Alert
            type="error"
            showIcon
            closable={{ 'aria-label': t('users.dismiss') }}
            onClose={() => setExported({ kind: 'idle' })}
            title={t('audit.export.failed')}
            description={exported.message}
          />
        )}
        <ProTable<AuditEntry, Searched>
          aria-label={t('users.screen.audit')}
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={shown.kind === 'read' ? shown.rows : []}
          loading={shown.kind === 'reading'}
          dateFormatter={(value) => value.toISOString()}
          search={{ labelWidth: 'auto', defaultCollapsed: false }}
          onSubmit={(searched) => readAgain(queryOf(searched))}
          onReset={() => readAgain({})}
          tableViewRender={(_, table) =>
            shown.kind === 'failed' ? (
              <Alert type="error" showIcon title={t('audit.unread')} description={shown.message} action={<Button onClick={() => readAgain(query)}>{t('frame.tryAgain')}</Button>} />
            ) : typeof table === 'function' ? (
              table()
            ) : (
              table
            )
          }
          options={false}
          pagination={false}
          locale={{ emptyText: t('audit.none') }}
          headerTitle={<h2>{t('audit.newestFirst')}</h2>}
          toolBarRender={() => [
            <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={() => readAgain(query)}>
              {t('users.refresh')}
            </Button>,
            <Button key="export" type="primary" icon={<DownloadOutlined aria-hidden />} loading={reading} onClick={() => void exportCsv()}>
              {reading ? t('audit.exporting', { count: exported.count }) : t('audit.export')}
            </Button>,
          ]}
        />
        {shown.kind === 'read' && typeof shown.more === 'object' && <Alert type="error" showIcon title={t('audit.older.unread')} description={shown.more.failed} />}
        {shown.kind === 'read' && shown.next !== null && (
          <Flex justify="center">
            <Button loading={shown.more === 'reading'} onClick={() => shown.next !== null && loadMore(shown.rows, shown.next)}>
              {t('audit.more')}
            </Button>
          </Flex>
        )}
      </Flex>
    </Page>
  );
}
