import { ProTable } from '@ant-design/pro-components';
import { Alert, Button, Empty, Flex, Form, Input, Select, Tag, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState, type TableHTMLAttributes } from 'react';
import { msg, say, useText, type Message } from '../language';
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

const STATUSES: { value: Status; label: Message; color?: 'success' | 'error' | 'processing' }[] = [
  { value: 'completed', label: msg('messages.status.completed'), color: 'success' },
  { value: 'failed', label: msg('messages.status.failed'), color: 'error' },
  { value: 'running', label: msg('messages.status.running'), color: 'processing' },
  { value: 'cancelled', label: msg('messages.status.cancelled') },
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

/**
 * The recent messages on the node, newest first, a page at a time: each with its start, flow, status, duration and
 * business keys. It opens on the last hour of every flow; the time range, the flow and the status apply when chosen, the
 * business key and the step name with Search, which also reads the list again up to now. Above the list, a strip counts
 * the messages of the time range and flow in each status; choosing a count narrows the list to that status, and choosing
 * it again shows every status. Choosing a message's id opens it in a tab of its own, as does Open with an id typed in.
 */
export function MessageList({ api, onOpen, now = BROWSER_CLOCK }: MessageListProps) {
  const [filters, setFilters] = useState<Filters>(DEFAULTS);
  const [flows, setFlows] = useState<Flows>({ kind: 'loading' });
  const [result, setResult] = useState<Result>({ kind: 'loading' });
  const [counted, setCounted] = useState<Counted>({ kind: 'loading' });
  const [id, setId] = useState('');
  const texts = useText();
  const { t } = texts;
  // only the answers to the latest read of the list and of the counts are shown
  const latest = useRef(0);
  const latestCounts = useRef(0);

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
          if (asked === latest.current)
            setResult({ kind: 'shown', shown: { query, items: page.items, next: page.next, more: false } });
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
    setResult({ kind: 'loading' });
    setCounted({ kind: 'loading' });
    read(chosen);
  };

  const choose = (changed: Partial<Filters>) => {
    const next = { ...filters, ...changed };
    setFilters(next);
    readAgain(next);
  };

  const loadMore = (shown: Shown) => {
    if (shown.next === null) return;
    const asked = ++latest.current;
    setResult({ kind: 'shown', shown: { ...shown, more: true } });
    api.list(shown.query, shown.next).then(
      (page) => {
        if (asked === latest.current)
          setResult({
            kind: 'shown',
            shown: { ...shown, items: [...shown.items, ...page.items], next: page.next, more: false },
          });
      },
      (failed: unknown) => {
        if (asked === latest.current) setResult({ kind: 'failed', message: reason(failed) });
      },
    );
  };

  return (
    <Flex vertical gap="middle">
      <Typography.Title level={1}>{t('messages.screen.title')}</Typography.Title>
      {flows.kind === 'failed' && (
        <Alert showIcon type="error" title={t('messages.flows.unread')} description={flows.message} />
      )}
      <Form aria-label={t('messages.filters')} className="rail8-filters" onFinish={() => readAgain(filters)}>
        <Flex gap="small" wrap>
          <Select
            aria-label={t('messages.range')}
            value={filters.minutes}
            onChange={(minutes) => choose({ minutes })}
            options={RANGES.map(({ minutes, label }) => ({ value: minutes, label: say(texts, label) }))}
            virtual={false}
          />
          <Select
            aria-label={t('messages.flow')}
            value={filters.flow}
            disabled={flows.kind !== 'loaded'}
            onChange={(flow) => choose({ flow })}
            options={[
              { label: t('messages.flow.all'), value: '' },
              ...(flows.kind === 'loaded' ? flows.ids.map((value) => ({ label: value, value })) : []),
            ]}
            virtual={false}
          />
          <Select
            aria-label={t('messages.status')}
            value={filters.status}
            onChange={(status) => choose({ status })}
            options={[
              { label: t('messages.status.all'), value: '' },
              ...STATUSES.map(({ value, label }) => ({ value, label: say(texts, label) })),
            ]}
            virtual={false}
          />
          <Input
            aria-label={t('messages.key.name')}
            placeholder={t('messages.key.name')}
            value={filters.keyName}
            onChange={(e) => setFilters({ ...filters, keyName: e.target.value })}
          />
          <Input
            aria-label={t('messages.key.value')}
            placeholder={t('messages.key.value')}
            value={filters.keyValue}
            onChange={(e) => setFilters({ ...filters, keyValue: e.target.value })}
          />
          <Input
            aria-label={t('messages.step.name')}
            placeholder={t('messages.step.name')}
            value={filters.step}
            onChange={(e) => setFilters({ ...filters, step: e.target.value })}
          />
          <Button type="primary" htmlType="submit">
            {t('messages.search')}
          </Button>
        </Flex>
      </Form>
      <Form
        aria-label={t('messages.open.title')}
        onFinish={() => {
          if (id.trim()) onOpen(id.trim());
        }}
      >
        <Flex gap="small">
          <Input
            aria-label={t('messages.id')}
            placeholder={t('messages.id')}
            style={{ maxWidth: 320 }}
            value={id}
            onChange={(e) => setId(e.target.value)}
          />
          <Button htmlType="submit" disabled={!id.trim()}>
            {t('messages.open')}
          </Button>
        </Flex>
      </Form>
      {counted.kind === 'loading' && <ReadSpinner label={t('messages.counting')} />}
      {counted.kind === 'failed' && (
        <Alert showIcon type="error" title={t('messages.counts.unread')} description={counted.message} />
      )}
      {counted.kind === 'counted' && (
        <Flex role="group" aria-label={t('messages.byStatus')} gap="small" wrap>
          {STATUSES.map(({ value, label }) => (
            <Button
              key={value}
              aria-label={t('messages.byStatus.count', { status: say(texts, label), count: counted.counts[value] })}
              aria-pressed={filters.status === value}
              type={filters.status === value ? 'primary' : 'default'}
              onClick={() => choose({ status: filters.status === value ? '' : value })}
            >
              {say(texts, label)} <strong>{texts.intl.formatNumber(counted.counts[value])}</strong>
            </Button>
          ))}
        </Flex>
      )}
      {result.kind === 'loading' && <ReadSpinner label={t('messages.reading')} />}
      {result.kind === 'failed' && (
        <Alert showIcon type="error" title={t('messages.list.unread')} description={result.message} />
      )}
      {result.kind === 'shown' && <MessageResults shown={result.shown} onOpen={onOpen} onMore={loadMore} />}
    </Flex>
  );
}

function MessagesTable(props: TableHTMLAttributes<HTMLTableElement>) {
  const { t } = useText();
  return <table {...props} aria-label={t('messages.screen.title')} />;
}

function MessageResults({
  shown,
  onOpen,
  onMore,
}: {
  shown: Shown;
  onOpen: (id: string) => void;
  onMore: (shown: Shown) => void;
}) {
  const texts = useText();
  const { t, rich } = texts;
  if (!shown.items.length) return <Empty description={rich('messages.none', { h2: (chunks) => <h2>{chunks}</h2> })} />;
  return (
    <>
      <ProTable<Listed>
        rowKey="id"
        size="small"
        search={false}
        options={false}
        pagination={false}
        toolBarRender={false}
        dataSource={shown.items}
        scroll={{ x: 'max-content' }}
        cardBordered={false}
        components={{ table: MessagesTable }}
        columns={[
          { title: t('messages.startedAt'), dataIndex: 'startedAt' },
          { title: t('messages.flow'), dataIndex: 'flow' },
          {
            title: t('messages.status'),
            dataIndex: 'status',
            render: (_, row) => {
              const status = STATUSES.find(({ value }) => value === row.status);
              return <Tag color={status?.color}>{status === undefined ? row.status : say(texts, status.label)}</Tag>;
            },
          },
          { title: t('messages.duration'), render: (_, row) => duration(texts, row.durationNanos) },
          {
            title: t('messages.keys'),
            render: (_, row) =>
              Object.entries(row.keys)
                .map(([name, value]) => `${name}=${value}`)
                .join(', '),
          },
          {
            title: t('messages.id'),
            render: (_, row) => (
              <Button type="link" onClick={() => onOpen(row.id)}>
                {row.id}
              </Button>
            ),
          },
        ]}
      />
      {shown.next !== null && (
        <div>
          <Button loading={shown.more} disabled={shown.more} onClick={() => onMore(shown)}>
            {t('messages.more')}
          </Button>
        </div>
      )}
    </>
  );
}
