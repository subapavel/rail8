import { ReloadOutlined } from '@ant-design/icons';
import { ProTable, type ProColumns, type ProFormInstance } from '@ant-design/pro-components';
import { Alert, Badge, Button, Empty, Flex, Form, Input, Space } from 'antd';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type TableHTMLAttributes } from 'react';
import { msg, say, useText, type Message, type Texts } from '../language';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import type { AdminApi, Counts, Listed, ListQuery, Status } from './admin';
import { duration } from './progress';

export interface MessageListProps {
  api: AdminApi;
  /** Opens the message with this id in a tab of its own. */
  onOpen: (id: string) => void;
  /** The time the list's range ends at; the browser's clock by default. */
  now?: () => Date;
}

/** The time ranges to choose from, each ending now. */
const RANGES = [
  { label: msg('messages.range.minutes', { count: 15 }), minutes: 15 },
  { label: msg('messages.range.hour'), minutes: 60 },
  { label: msg('messages.range.hours', { count: 24 }), minutes: 24 * 60 },
  { label: msg('messages.range.days', { count: 7 }), minutes: 7 * 24 * 60 },
] as const;

/** Each status with its word and its dot of the status vocabulary. */
const STATUSES: { value: Status; label: Message; badge: 'success' | 'error' | 'processing' | 'default' }[] = [
  { value: 'completed', label: msg('messages.status.completed'), badge: 'success' },
  { value: 'failed', label: msg('messages.status.failed'), badge: 'error' },
  { value: 'running', label: msg('messages.status.running'), badge: 'processing' },
  { value: 'cancelled', label: msg('messages.status.cancelled'), badge: 'default' },
];

/** What the filters are set to; an empty text is no filter. */
interface Filters {
  minutes: number;
  flow: string;
  status: Status | '';
  keyName: string;
  keyValue: string;
  step: string;
}

const DEFAULTS: Filters = { minutes: 60, flow: '', status: '', keyName: '', keyValue: '', step: '' };

/** The fields of the search form whose choice reads the list at once; the others wait for Search. */
const AT_ONCE: readonly (keyof Filters)[] = ['minutes', 'flow', 'status'];

/** The deployed flows the flow filter picks from: being read, read, or refused. */
type Flows = { kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'loaded'; ids: string[] };

/** What the list read last: the query, the messages, the cursor of the next page, and whether that page is being read. */
interface Shown {
  query: ListQuery;
  items: Listed[];
  next: string | null;
  more: boolean;
}

type Result = { kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'shown'; shown: Shown };

/** The counts by status of the list's time range and flow: being read, refused, or read. */
type Counted = { kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'counted'; counts: Counts };

const BROWSER_CLOCK = () => new Date();

const reason = (failed: unknown) => (failed instanceof Error ? failed.message : String(failed));

/** The query the filters name, over the range that ends at `now`. */
function queryOf(filters: Filters, now: Date): ListQuery {
  const query: ListQuery = {
    from: new Date(now.getTime() - filters.minutes * 60_000).toISOString(),
    to: now.toISOString(),
  };
  if (filters.flow !== '') query.flow = filters.flow;
  if (filters.status !== '') query.status = filters.status;
  if (filters.keyName.trim() !== '') query.key = { name: filters.keyName.trim(), value: filters.keyValue };
  if (filters.step.trim() !== '') query.step = filters.step.trim();
  return query;
}

/** A message's status as a dot with its word; a status this screen does not know shows as the node named it. */
export function StatusBadge({ status }: { status: string }) {
  const texts = useText();
  const known = STATUSES.find(({ value }) => value === status);
  return <Badge status={known?.badge ?? 'default'} text={known === undefined ? status : say(texts, known.label)} />;
}

/**
 * The columns of the list and the fields of its search form, in `texts`' language: the time range, the flow, the status,
 * and the business key and step name, which only search; the flow picks from `flows`, and the id opens the message.
 * They stay the same while `texts` and `flows` do, so the search form keeps its fields.
 */
function columnsIn(texts: Texts, flows: Flows): ProColumns<Listed, 'text'>[] {
  const { t } = texts;
  // each field carries its name itself: the colon the form draws after a label would be part of it
  const select = { allowClear: false, virtual: false };
  return [
    {
      title: t('messages.range'),
      dataIndex: 'minutes',
      hideInTable: true,
      valueType: 'select',
      initialValue: DEFAULTS.minutes,
      fieldProps: { ...select, 'aria-label': t('messages.range'), options: RANGES.map(({ minutes, label }) => ({ value: minutes, label: say(texts, label) })) },
    },
    {
      title: t('messages.id'),
      dataIndex: 'id',
      search: false,
      render: (_, row) => <MessageLink id={row.id} />,
    },
    { title: t('messages.startedAt'), dataIndex: 'startedAt', search: false, width: 240 },
    {
      title: t('messages.flow'),
      dataIndex: 'flow',
      valueType: 'select',
      initialValue: DEFAULTS.flow,
      fieldProps: {
        ...select,
        'aria-label': t('messages.flow'),
        disabled: flows.kind !== 'loaded',
        options: [{ label: t('messages.flow.all'), value: '' }, ...(flows.kind === 'loaded' ? flows.ids.map((value) => ({ label: value, value })) : [])],
      },
      render: (_, row) => row.flow,
    },
    {
      title: t('messages.status'),
      dataIndex: 'status',
      valueType: 'select',
      initialValue: DEFAULTS.status,
      width: 140,
      fieldProps: { ...select, 'aria-label': t('messages.status'), options: [{ label: t('messages.status.all'), value: '' }, ...STATUSES.map(({ value, label }) => ({ value, label: say(texts, label) }))] },
      render: (_, row) => <StatusBadge status={row.status} />,
    },
    { title: t('messages.duration'), key: 'duration', search: false, align: 'right', width: 120, render: (_, row) => duration(texts, row.durationNanos) },
    {
      title: t('messages.keys'),
      key: 'keys',
      search: false,
      render: (_, row) =>
        Object.keys(row.keys).length === 0
          ? '-'
          : Object.entries(row.keys)
              .map(([name, value]) => `${name}=${value}`)
              .join(', '),
    },
    { title: t('messages.key.name'), dataIndex: 'keyName', hideInTable: true, fieldProps: { 'aria-label': t('messages.key.name') } },
    { title: t('messages.key.value'), dataIndex: 'keyValue', hideInTable: true, fieldProps: { 'aria-label': t('messages.key.value') } },
    { title: t('messages.step.name'), dataIndex: 'step', hideInTable: true, fieldProps: { 'aria-label': t('messages.step.name') } },
  ];
}

/** Opens the message with an id in a tab of its own, for the links of the list's rows. */
const OpenMessage = createContext<(id: string) => void>(() => undefined);

/** The id of a message as the link that opens it. */
function MessageLink({ id }: { id: string }) {
  const open = useContext(OpenMessage);
  return (
    <Button type="link" className="rail8-link" onClick={() => open(id)}>
      {id}
    </Button>
  );
}

/** The filters the search form holds. */
const filtersOf = (values: Partial<Filters>): Filters => ({ ...DEFAULTS, ...values });

/**
 * The recent messages on the node, newest first, a page at a time, as a list page: a search form above a card with the
 * table, each message with its id, which opens it in a tab of its own, its start, flow, status, duration and business
 * keys. It opens on the last hour of every flow; the time range, the flow and the status apply when chosen, the business
 * key and the step name with Search, which also reads the list again up to now, as Refresh does; Reset goes back to the
 * last hour of every flow. Under the card's title a strip counts the messages of the time range and flow in each status;
 * choosing a count narrows the list to that status, and choosing it again shows every status. In the card's corner, Open
 * opens the message whose id is typed beside it.
 */
export function MessageList({ api, onOpen, now = BROWSER_CLOCK }: MessageListProps) {
  const [filters, setFilters] = useState<Filters>(DEFAULTS);
  const [flows, setFlows] = useState<Flows>({ kind: 'loading' });
  const [result, setResult] = useState<Result>({ kind: 'loading' });
  const [counted, setCounted] = useState<Counted>({ kind: 'loading' });
  const [id, setId] = useState('');
  const form = useRef<ProFormInstance>(undefined);
  const texts = useText();
  const { t } = texts;
  // only the answers to the latest read of the list and of the counts are shown
  const latest = useRef(0);
  const latestCounts = useRef(0);
  const columns = useMemo(() => columnsIn(texts, flows), [texts, flows]);

  // reads the first page of what `chosen` names and the counts of its time range and flow, and shows them, unless a later
  // read began meanwhile
  const read = useCallback(
    (chosen: Filters) => {
      const asked = ++latest.current;
      const askedCounts = ++latestCounts.current;
      const query = queryOf(chosen, now());
      api.counts({ from: query.from, to: query.to, flow: query.flow }).then(
        (counts) => {
          if (askedCounts === latestCounts.current) setCounted({ kind: 'counted', counts });
        },
        (failed: unknown) => {
          if (askedCounts === latestCounts.current) setCounted({ kind: 'failed', message: reason(failed) });
        },
      );
      api.list(query).then(
        (page) => {
          if (asked === latest.current) setResult({ kind: 'shown', shown: { query, items: page.items, next: page.next, more: false } });
        },
        (failed: unknown) => {
          if (asked === latest.current) setResult({ kind: 'failed', message: reason(failed) });
        },
      );
    },
    [api, now],
  );

  useEffect(() => {
    read(DEFAULTS);
  }, [read]);

  useEffect(() => {
    api.flows().then(
      (ids) => setFlows({ kind: 'loaded', ids }),
      (failed: unknown) => setFlows({ kind: 'failed', message: reason(failed) }),
    );
  }, [api]);

  const readAgain = (chosen: Filters) => {
    setFilters(chosen);
    setResult({ kind: 'loading' });
    setCounted({ kind: 'loading' });
    read(chosen);
  };

  const chooseStatus = (status: Status | '') => {
    form.current?.setFieldsValue({ status });
    readAgain({ ...filters, status });
  };

  const loadMore = (shown: Shown) => {
    if (shown.next === null) return;
    const asked = ++latest.current;
    setResult({ kind: 'shown', shown: { ...shown, more: true } });
    api.list(shown.query, shown.next).then(
      (page) => {
        if (asked === latest.current) setResult({ kind: 'shown', shown: { ...shown, items: [...shown.items, ...page.items], next: page.next, more: false } });
      },
      (failed: unknown) => {
        if (asked === latest.current) setResult({ kind: 'failed', message: reason(failed) });
      },
    );
  };

  let strip;
  if (counted.kind === 'loading') strip = <ReadSpinner size="small" label={t('messages.counting')} />;
  else if (counted.kind === 'failed') strip = <Alert showIcon type="error" title={t('messages.counts.unread')} description={counted.message} />;
  else
    strip = (
      <Flex role="group" aria-label={t('messages.byStatus')} gap="small" wrap>
        {STATUSES.map(({ value, label, badge }) => {
          const pressed = filters.status === value;
          return (
            <Button
              key={value}
              aria-label={t('messages.byStatus.count', { status: say(texts, label), count: counted.counts[value] })}
              aria-pressed={pressed}
              color={pressed ? 'primary' : 'default'}
              variant={pressed ? 'outlined' : 'filled'}
              onClick={() => chooseStatus(pressed ? '' : value)}
            >
              <Badge status={badge} text={say(texts, label)} /> <strong>{texts.intl.formatNumber(counted.counts[value])}</strong>
            </Button>
          );
        })}
      </Flex>
    );

  const shown = result.kind === 'shown' ? result.shown : null;
  return (
    <Page title={t('messages.screen.title')} content={t('messages.screen.description')}>
      <OpenMessage.Provider value={onOpen}>
        <Flex vertical gap="large">
          {flows.kind === 'failed' && <Alert showIcon type="error" title={t('messages.flows.unread')} description={flows.message} />}
          <ProTable<Listed, Partial<Filters>>
            aria-label={t('messages.screen.title')}
            rowKey="id"
            size="small"
            columns={columns}
            dataSource={shown?.items ?? []}
            formRef={form}
            form={{
              'aria-label': t('messages.filters'),
              onValuesChange: (changed: Partial<Filters>) => {
                if (AT_ONCE.some((name) => name in changed)) form.current?.submit();
              },
            }}
            search={{ labelWidth: 'auto', defaultCollapsed: false, searchText: t('messages.search') }}
            onSubmit={(values) => readAgain(filtersOf(values))}
            onReset={() => readAgain(DEFAULTS)}
            tableViewRender={(_, table) => {
              if (result.kind === 'loading') return <ReadSpinner label={t('messages.reading')} />;
              if (result.kind === 'failed')
                return (
                  <Alert
                    showIcon
                    type="error"
                    title={t('messages.list.unread')}
                    description={result.message}
                    action={<Button onClick={() => readAgain(filters)}>{t('frame.tryAgain')}</Button>}
                  />
                );
              return typeof table === 'function' ? table() : table;
            }}
            components={{ table: MessagesTable }}
            options={false}
            pagination={false}
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: <Empty description={texts.rich('messages.none', { h2: (chunks) => <h2>{chunks}</h2> })} /> }}
            headerTitle={<h2>{t('messages.newestFirst')}</h2>}
            toolbar={{ multipleLine: true, filter: strip }}
            toolBarRender={() => [
              <Form
                key="open"
                aria-label={t('messages.open.title')}
                onFinish={() => {
                  if (id.trim()) onOpen(id.trim());
                }}
              >
                <Space.Compact>
                  <Input aria-label={t('messages.id')} placeholder={t('messages.id')} value={id} onChange={(e) => setId(e.target.value)} />
                  <Button htmlType="submit" disabled={!id.trim()}>
                    {t('messages.open')}
                  </Button>
                </Space.Compact>
              </Form>,
              <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={() => readAgain(filters)}>
                {t('messages.refresh')}
              </Button>,
            ]}
          />
          {shown !== null && shown.next !== null && (
            <Flex justify="center">
              <Button loading={shown.more} disabled={shown.more} onClick={() => loadMore(shown)}>
                {t('messages.more')}
              </Button>
            </Flex>
          )}
        </Flex>
      </OpenMessage.Provider>
    </Page>
  );
}

function MessagesTable(props: TableHTMLAttributes<HTMLTableElement>) {
  const { t } = useText();
  return <table {...props} aria-label={t('messages.screen.title')} />;
}
