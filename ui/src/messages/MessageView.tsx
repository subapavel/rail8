import { ReloadOutlined } from '@ant-design/icons';
import { ProCard } from '@ant-design/pro-components';
import { Alert, Badge, Button, Descriptions, Empty, Flex, Statistic, Table, theme, Typography, type TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import { auditAction, AuditHow } from '../audit';
import { msg, say, useText, type Message } from '../language';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import { Allowed, useMay } from '../SessionGate';
import { PAYLOAD_SHOWN, type AdminApi, type Audited, type Outcome, type Payload, type StepRecord, type Timeline } from './admin';
import { account, byAttempt, duration, INGRESS, latestState, type Row, type WaitReason } from './progress';

export interface MessageViewProps {
  api: AdminApi;
  /** The message's id. */
  id: string;
}

type Load = { kind: 'loading' } | { kind: 'missing' } | { kind: 'failed'; message: string } | { kind: 'loaded'; timeline: Timeline };

type Restart = { kind: 'idle' } | { kind: 'running' } | { kind: 'done'; attempt: number } | { kind: 'failed'; message: string };

const waitLabels: Record<WaitReason, Message> = {
  worker: msg('messages.wait.worker'),
  retry: msg('messages.wait.retry'),
  between: msg('messages.wait.between'),
};

const reason = (failed: unknown) => (failed instanceof Error ? failed.message : String(failed));

/** Each outcome with its word and its dot of the status vocabulary. */
const outcomeLabels: Record<Outcome, { text: Message; status: 'success' | 'error' | 'default' }> = {
  OK: { text: msg('messages.outcome.ok'), status: 'success' },
  FILTERED: { text: msg('messages.outcome.filtered'), status: 'default' },
  FAILED: { text: msg('messages.outcome.failed'), status: 'error' },
};

function OutcomeLabel({ outcome }: { outcome: Outcome }) {
  const texts = useText();
  const { text, status } = outcomeLabels[outcome];
  return <Badge status={status} text={say(texts, text)} />;
}

/** The key of a step's row in the Payloads card. */
const payloadKey = (step: StepRecord) => `${step.attempt}:${step.step}`;

/**
 * What happened to one message, as a detail page. Its header names the message and holds Refresh and, when the latest
 * attempt failed, Restart, which runs the message again from its arrival; the message's facts (flow, start, attempts, and
 * the time between steps that no row shows); and at its right the state of the latest attempt and the message's duration,
 * which is the one in the list. Below, the failed step's error, then one titled card per attempt with each recorded step:
 * its outcome, start, wall and CPU time, payload size and error, and the waits between steps that are not negligible. The
 * Payloads card lists the payload after every step; a kept one opens in its row, read when it opens, and the size in a
 * step's row opens it there too. The Audit card lists who retried the message, cancelled it or viewed its payloads, and
 * how they proved who they are. Refresh reads the steps and the audit again.
 */
export function MessageView({ api, id }: MessageViewProps) {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [reads, setReads] = useState(0);
  const [restart, setRestart] = useState<Restart>({ kind: 'idle' });
  // the rows open in the Payloads card
  const [opened, setOpened] = useState<readonly string[]>([]);
  const { token } = theme.useToken();
  const texts = useText();
  const { t, rich } = texts;

  useEffect(() => {
    let current = true;
    api.timeline(id).then(
      (timeline) => {
        if (current) setLoad(timeline === null ? { kind: 'missing' } : { kind: 'loaded', timeline });
      },
      (failed: unknown) => {
        if (current) setLoad({ kind: 'failed', message: reason(failed) });
      },
    );
    return () => {
      current = false;
    };
  }, [api, id, reads]);

  const refresh = () => {
    setLoad({ kind: 'loading' });
    setRestart({ kind: 'idle' });
    setReads((n) => n + 1);
  };

  const runAgain = () => {
    setRestart({ kind: 'running' });
    api.restart(id).then(
      (restarted) => setRestart({ kind: 'done', attempt: restarted.attempt }),
      (failed: unknown) => setRestart({ kind: 'failed', message: reason(failed) }),
    );
  };

  // opens the payload after `step` in the Payloads card and scrolls its row to the top, the payload under it
  const showPayload = (step: StepRecord) => {
    const key = payloadKey(step);
    setOpened((keys) => (keys.includes(key) ? keys : [...keys, key]));
    requestAnimationFrame(() => document.getElementById(`payload-${id}-${key}`)?.scrollIntoView({ block: 'start' }));
  };

  const timeline = load.kind === 'loaded' ? load.timeline : null;
  const state = timeline === null ? null : latestState(timeline.steps);
  const accounted = timeline === null ? null : account(timeline.steps);
  const statistic = { content: { fontSize: token.fontSizeHeading4 } };

  let body;
  if (load.kind === 'loading') body = <ReadSpinner label={t('messages.loading')} />;
  else if (load.kind === 'missing')
    body = (
      <ProCard>
        <Empty description={rich('messages.missing', { id, h2: (chunks) => <h2>{chunks}</h2> })} />
      </ProCard>
    );
  else if (load.kind === 'failed')
    body = <Alert showIcon type="error" title={t('messages.unread')} description={load.message} action={<Button onClick={refresh}>{t('frame.tryAgain')}</Button>} />;
  else if (timeline !== null && state !== null && accounted !== null)
    body = (
      <Flex vertical gap="large">
        {state.failed && (
          <Alert
            showIcon
            type="error"
            title={t('messages.step.failed', { step: state.failed.step, name: state.failed.name, attempt: state.attempt })}
            description={state.failed.error ?? t('messages.step.noError')}
          />
        )}
        {restart.kind === 'done' && (
          <Alert showIcon type="success" title={t('messages.restarted', { attempt: restart.attempt })} description={t('messages.restarted.description')} />
        )}
        {restart.kind === 'failed' && <Alert showIcon type="error" title={t('messages.restart.refused')} description={restart.message} />}
        {accounted.attempts.map(({ attempt, rows }) => (
          <ProCard key={attempt} title={<h2>{t('messages.attempt', { attempt })}</h2>} headerBordered>
            <StepsTable attempt={attempt} rows={rows} onShow={showPayload} />
          </ProCard>
        ))}
        {timeline.steps.length > 0 && (
          <ProCard title={<h2>{t('messages.payloads')}</h2>} headerBordered>
            <PayloadsTable api={api} id={id} steps={timeline.steps} opened={opened} onOpened={setOpened} />
          </ProCard>
        )}
        <MessageAudit api={api} id={id} />
      </Flex>
    );

  return (
    <Page
      title={t('messages.title', { id })}
      extra={
        <Flex gap="small" wrap justify="flex-end">
          <Button icon={<ReloadOutlined aria-hidden />} onClick={refresh} disabled={load.kind === 'loading'}>
            {t('messages.refresh')}
          </Button>
          {state?.failed && (
            <Allowed action="message.retry">
              <Button type="primary" onClick={runAgain} disabled={restart.kind === 'running' || restart.kind === 'done'}>
                {t('messages.restart')}
              </Button>
            </Allowed>
          )}
        </Flex>
      }
      content={
        timeline !== null &&
        accounted !== null && (
          <Descriptions
            size="small"
            column={2}
            items={[
              { key: 'flow', label: t('messages.flow'), children: timeline.flow },
              { key: 'started', label: t('messages.startedAt'), children: timeline.steps.length === 0 ? '-' : byStart(timeline.steps)[0]!.startedAt },
              { key: 'attempts', label: t('messages.attempts'), children: accounted.attempts.length },
              ...(accounted.otherNanos > 0 ? [{ key: 'other', label: t('messages.otherTime'), children: duration(texts, accounted.otherNanos) }] : []),
            ]}
          />
        )
      }
      extraContent={
        state !== null &&
        accounted !== null && (
          <Flex gap="large">
            <Statistic
              title={t('messages.state')}
              value={say(texts, outcomeLabels[state.outcome].text)}
              prefix={<Badge status={outcomeLabels[state.outcome].status} />}
              styles={statistic}
            />
            <Statistic title={t('messages.duration')} value={duration(texts, accounted.totalNanos)} styles={statistic} />
          </Flex>
        )
      }
    >
      {body}
    </Page>
  );
}

/** The steps by attempt, then by step. */
const byStart = (steps: readonly StepRecord[]) => byAttempt(steps).flatMap((a) => a.steps);

/**
 * The rows of one attempt: each recorded step, and each wait between two steps across the columns from the step's name to
 * its start, with its time under the wall time. `ingress` times nothing, so its wall and CPU time show `-`.
 */
function StepsTable({ attempt, rows, onShow }: { attempt: number; rows: Row[]; onShow: (step: StepRecord) => void }) {
  const texts = useText();
  const { t } = texts;
  const step = (row: Row) => (row.kind === 'step' ? row.step : null);
  // a step by its number, a wait by its place among the rows
  const keys = new Map(rows.map((row, i) => [row, row.kind === 'step' ? `step-${row.step.step}` : `wait-${i}`]));
  // a wait's label spans the name, the outcome and the start; its time is under the wall time, and nothing follows it
  const across = (span: number) => (row: Row) => (row.kind === 'wait' ? { colSpan: span } : {});
  const columns: TableColumnsType<Row> = [
    { title: t('messages.step'), key: 'step', width: 64, align: 'right', render: (_, row) => step(row)?.step ?? '' },
    { title: t('messages.step.title'), key: 'name', onCell: across(3), render: (_, row) => (row.kind === 'wait' ? say(texts, waitLabels[row.reason]) : row.step.name) },
    { title: t('messages.outcome'), key: 'outcome', width: 120, onCell: across(0), render: (_, row) => row.kind === 'step' && <OutcomeLabel outcome={row.step.outcome} /> },
    { title: t('messages.startedAt'), key: 'started', width: 240, onCell: across(0), render: (_, row) => step(row)?.startedAt },
    {
      title: t('messages.wallTime'),
      key: 'wall',
      width: 110,
      align: 'right',
      render: (_, row) => (row.kind === 'wait' ? duration(texts, row.nanos) : row.step.name === INGRESS ? '-' : duration(texts, row.step.wallNanos)),
    },
    {
      title: t('messages.cpuTime'),
      key: 'cpu',
      width: 110,
      align: 'right',
      onCell: across(3),
      render: (_, row) => {
        if (row.kind === 'wait') return '';
        if (row.step.name === INGRESS) return '-';
        return row.step.cpuNanos === null ? t('messages.cpuTime.unknown') : duration(texts, row.step.cpuNanos);
      },
    },
    {
      title: t('messages.payload'),
      key: 'payload',
      width: 140,
      align: 'right',
      onCell: across(0),
      render: (_, row) =>
        row.kind === 'step' &&
        (row.step.body.kept ? (
          <Allowed action="message.body">
            <Button type="link" className="rail8-link" onClick={() => onShow(row.step)} aria-label={t('messages.payload.show', { step: row.step.step, attempt })}>
              {t('messages.size', { size: row.step.body.size })}
            </Button>
          </Allowed>
        ) : (
          t('messages.size.notKept', { size: row.step.body.size })
        )),
    },
    { title: t('messages.error'), key: 'error', onCell: across(0), render: (_, row) => (row.kind === 'step' ? (row.step.error ?? '-') : '') },
  ];
  return (
    <Table<Row>
      aria-label={t('messages.attempt.steps', { attempt })}
      size="small"
      pagination={false}
      scroll={{ x: 'max-content' }}
      rowKey={(row) => keys.get(row) ?? ''}
      columns={columns}
      dataSource={rows}
    />
  );
}

interface PayloadsTableProps {
  api: AdminApi;
  id: string;
  steps: readonly StepRecord[];
  /** The keys of the rows open now. */
  opened: readonly string[];
  onOpened: (keys: readonly string[]) => void;
}

/** The payload after every step of every attempt, with its size; a kept one opens in its row for a caller who may read bodies. */
function PayloadsTable({ api, id, steps, opened, onOpened }: PayloadsTableProps) {
  const texts = useText();
  const { t } = texts;
  const may = useMay('message.body') === true;
  return (
    <Table<StepRecord>
      aria-label={t('messages.payloads')}
      size="small"
      pagination={false}
      rowKey={payloadKey}
      onRow={(step) => ({ id: `payload-${id}-${payloadKey(step)}` })}
      dataSource={byStart(steps)}
      expandable={{
        rowExpandable: (step) => may && step.body.kept,
        expandedRowKeys: opened as string[],
        onExpandedRowsChange: (keys) => onOpened(keys.map(String)),
        expandedRowRender: (step) => <PayloadView api={api} id={id} step={step} />,
      }}
      columns={[
        { title: t('messages.payloads.attempt'), dataIndex: 'attempt', width: 100, align: 'right' },
        { title: t('messages.step'), dataIndex: 'step', width: 64, align: 'right' },
        { title: t('messages.step.title'), dataIndex: 'name' },
        {
          title: t('messages.payload'),
          key: 'size',
          width: 160,
          align: 'right',
          render: (_, step) => (step.body.kept ? t('messages.size', { size: step.body.size }) : t('messages.size.notKept', { size: step.body.size })),
        },
      ]}
    />
  );
}

type PayloadLoad = { kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'loaded'; payload: Payload };

/** The payload after one step of one attempt, read when it shows: its content type, size and hash and its text, the first part only when it is large. */
function PayloadView({ api, id, step }: { api: AdminApi; id: string; step: StepRecord }) {
  const [load, setLoad] = useState<PayloadLoad>({ kind: 'loading' });
  const { t } = useText();

  useEffect(() => {
    let current = true;
    api.payload(id, step.step, step.attempt).then(
      (payload) => {
        if (current) setLoad({ kind: 'loaded', payload });
      },
      (failed: unknown) => {
        if (current) setLoad({ kind: 'failed', message: reason(failed) });
      },
    );
    return () => {
      current = false;
    };
  }, [api, id, step]);

  return (
    <section aria-label={t('messages.payload.title', { step: step.step, name: step.name, attempt: step.attempt })}>
      {load.kind === 'loading' && <ReadSpinner label={t('messages.payload.loading')} />}
      {load.kind === 'failed' && <Alert showIcon type="error" title={t('messages.payload.unread')} description={load.message} />}
      {load.kind === 'loaded' && (
        <Flex vertical gap="small">
          <Typography.Text type="secondary">{t('messages.payload.facts', { type: load.payload.contentType, size: step.body.size, hash: step.body.hash })}</Typography.Text>
          {!load.payload.complete && <Alert showIcon type="info" title={t('messages.payload.part', { shown: PAYLOAD_SHOWN, size: step.body.size })} />}
          <pre className="rail8-payload">{load.payload.text}</pre>
        </Flex>
      )}
    </section>
  );
}

type AuditLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; message: string }
  | { kind: 'loaded'; rows: Audited[]; next: string | null; older: 'idle' | 'loading' | { failed: string } };

/**
 * The message's audit in a titled card, newest first: when, what was done, who did it and how they proved who they are,
 * with the id of their session or token when they had one. Older rows load a page at a time with the button in the
 * card's corner.
 */
function MessageAudit({ api, id }: { api: AdminApi; id: string }) {
  const texts = useText();
  const { t } = texts;
  const [load, setLoad] = useState<AuditLoad>({ kind: 'loading' });
  const [reads, setReads] = useState(0);

  useEffect(() => {
    let current = true;
    api.audit(id).then(
      (page) => {
        if (current) setLoad({ kind: 'loaded', rows: page.items, next: page.next, older: 'idle' });
      },
      (failed: unknown) => {
        if (current) setLoad({ kind: 'failed', message: reason(failed) });
      },
    );
    return () => {
      current = false;
    };
  }, [api, id, reads]);

  const loadOlder = (shown: Extract<AuditLoad, { kind: 'loaded' }>) => {
    if (shown.next === null) return;
    setLoad({ ...shown, older: 'loading' });
    api.audit(id, shown.next).then(
      (page) => setLoad({ kind: 'loaded', rows: [...shown.rows, ...page.items], next: page.next, older: 'idle' }),
      (failed: unknown) => setLoad({ ...shown, older: { failed: reason(failed) } }),
    );
  };

  return (
    <ProCard
      title={<h2>{t('messages.audit')}</h2>}
      headerBordered
      extra={
        load.kind === 'loaded' &&
        load.next !== null && (
          <Button onClick={() => loadOlder(load)} loading={load.older === 'loading'}>
            {t('messages.audit.older')}
          </Button>
        )
      }
    >
      {load.kind === 'loading' && <ReadSpinner label={t('messages.audit.reading')} />}
      {load.kind === 'failed' && (
        <Alert
          showIcon
          type="error"
          title={t('audit.unread')}
          description={load.message}
          action={
            <Button
              onClick={() => {
                setLoad({ kind: 'loading' });
                setReads((n) => n + 1);
              }}
            >
              {t('frame.tryAgain')}
            </Button>
          }
        />
      )}
      {load.kind === 'loaded' && load.rows.length === 0 && <Empty description={t('messages.audit.none')} />}
      {load.kind === 'loaded' && load.rows.length > 0 && (
        <Flex vertical gap="small">
          <Table<Audited>
            aria-label={t('messages.audit.table')}
            size="small"
            pagination={false}
            rowKey="id"
            dataSource={load.rows}
            columns={[
              { title: t('messages.audit.when'), dataIndex: 'at', width: 240 },
              { title: t('audit.action'), key: 'action', render: (_, row) => auditAction(texts, row) },
              { title: t('audit.who'), dataIndex: 'actor' },
              { title: t('messages.audit.how'), key: 'how', render: (_, row) => <AuditHow row={row} /> },
            ]}
          />
          {typeof load.older === 'object' && <Alert showIcon type="error" title={t('audit.older.unread')} description={load.older.failed} />}
        </Flex>
      )}
    </ProCard>
  );
}
