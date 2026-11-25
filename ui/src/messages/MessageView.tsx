import { Alert, Button, Descriptions, Empty, Flex, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { auditAction, AuditHow } from '../audit';
import { msg, say, useText, type Message } from '../language';
import { MountedModal } from '../MountedModal';
import { ReadSpinner } from '../ReadSpinner';
import { Allowed } from '../SessionGate';
import {
  PAYLOAD_SHOWN,
  type AdminApi,
  type Audited,
  type Outcome,
  type Payload,
  type StepRecord,
  type Timeline,
} from './admin';
import { account, duration, INGRESS, latestState, type WaitReason } from './progress';

export interface MessageViewProps {
  api: AdminApi;
  /** The message's id. */
  id: string;
}

type Load =
  | { kind: 'loading' }
  | { kind: 'missing' }
  | { kind: 'failed'; message: string }
  | { kind: 'loaded'; timeline: Timeline };

type Restart =
  { kind: 'idle' } | { kind: 'running' } | { kind: 'done'; attempt: number } | { kind: 'failed'; message: string };

const waitLabels: Record<WaitReason, Message> = {
  worker: msg('messages.wait.worker'),
  retry: msg('messages.wait.retry'),
  between: msg('messages.wait.between'),
};

const reason = (failed: unknown) => (failed instanceof Error ? failed.message : String(failed));

const outcomeLabels: Record<Outcome, { text: Message; status?: 'success' | 'error' }> = {
  OK: { text: msg('messages.outcome.ok'), status: 'success' },
  FILTERED: { text: msg('messages.outcome.filtered') },
  FAILED: { text: msg('messages.outcome.failed'), status: 'error' },
};

function OutcomeLabel({ outcome }: { outcome: Outcome }) {
  const texts = useText();
  const { text, status } = outcomeLabels[outcome];
  return <Tag color={status}>{say(texts, text)}</Tag>;
}

/**
 * What happened to one message: its flow, the state of its latest attempt and, for every attempt, each recorded step
 * with its outcome, start, wall and CPU time, payload size and error, and the waits between steps that are not
 * negligible. Under the last attempt, the time between steps that no wait row shows and the total, which is the
 * message's duration in the list. The payload after a step opens in a dialog when its bytes were kept. When the latest attempt failed, its error is shown with a Restart action, which runs the message
 * again from its arrival. Below the steps, the message's audit: who retried it, cancelled it or viewed its payloads, and
 * how they proved who they are. Refresh reads the steps and the audit again.
 */
export function MessageView({ api, id }: MessageViewProps) {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [reads, setReads] = useState(0);
  const [restart, setRestart] = useState<Restart>({ kind: 'idle' });
  const [shown, setShown] = useState<StepRecord | null>(null);
  const { t, rich } = useText();

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

  return (
    <Flex vertical gap="middle">
      <Flex justify="space-between" align="center" gap="small" wrap>
        <Typography.Title level={1}>{t('messages.title', { id })}</Typography.Title>
        <Button onClick={refresh} disabled={load.kind === 'loading'}>
          {t('messages.refresh')}
        </Button>
      </Flex>
      {load.kind === 'loading' && <ReadSpinner label={t('messages.loading')} />}
      {load.kind === 'missing' && <Empty description={rich('messages.missing', { id, h2: (chunks) => <h2>{chunks}</h2> })} />}
      {load.kind === 'failed' && <Alert showIcon type="error" title={t('messages.unread')} description={load.message} />}
      {load.kind === 'loaded' && (
        <>
          <Progress timeline={load.timeline} restart={restart} onRestart={runAgain} onShow={setShown} />
          <MessageAudit api={api} id={id} />
        </>
      )}
      {shown !== null && <PayloadDialog api={api} id={id} step={shown} onClose={() => setShown(null)} />}
    </Flex>
  );
}

interface ProgressProps {
  timeline: Timeline;
  restart: Restart;
  onRestart: () => void;
  onShow: (step: StepRecord) => void;
}

function Progress({ timeline, restart, onRestart, onShow }: ProgressProps) {
  const texts = useText();
  const { t } = texts;
  const state = latestState(timeline.steps);
  const { attempts, otherNanos, totalNanos } = account(timeline.steps);
  const columns = [
    t('messages.step'),
    t('messages.step.title'),
    t('messages.outcome'),
    t('messages.startedAt'),
    t('messages.wallTime'),
    t('messages.cpuTime'),
    t('messages.payload'),
    t('messages.error'),
  ];
  return (
    <>
      <Descriptions
        size="small"
        items={[
          { key: 'flow', label: t('messages.flow'), children: timeline.flow },
          { key: 'attempts', label: t('messages.attempts'), children: attempts.length },
          { key: 'state', label: t('messages.state'), children: <OutcomeLabel outcome={state.outcome} /> },
        ]}
      />
      {state.failed && (
        <Alert
          showIcon
          type="error"
          title={t('messages.step.failed', { step: state.failed.step, name: state.failed.name, attempt: state.attempt })}
          description={state.failed.error ?? t('messages.step.noError')}
          action={
            <Allowed action="message.retry">
              <Button onClick={onRestart} disabled={restart.kind === 'running' || restart.kind === 'done'}>
                {t('messages.restart')}
              </Button>
            </Allowed>
          }
        />
      )}
      {restart.kind === 'done' && (
        <Alert
          showIcon
          type="success"
          title={t('messages.restarted', { attempt: restart.attempt })}
          description={t('messages.restarted.description')}
        />
      )}
      {restart.kind === 'failed' && (
        <Alert showIcon type="error" title={t('messages.restart.refused')} description={restart.message} />
      )}
      {attempts.map(({ attempt, rows }) => (
        <section key={attempt}>
          <Typography.Title level={2}>{t('messages.attempt', { attempt })}</Typography.Title>
          <div className="rail8-table-scroll">
            <table className="rail8-document-table" aria-label={t('messages.attempt.steps', { attempt })}>
              <thead>
                <tr>
                  {columns.map((label) => (
                    <th scope="col" key={label}>
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row, i) =>
                  row.kind === 'wait' ? (
                    <tr key={`wait-${i}`}>
                      <td />
                      <td colSpan={3}>{say(texts, waitLabels[row.reason])}</td>
                      <td>{duration(texts, row.nanos)}</td>
                      <td colSpan={3} />
                    </tr>
                  ) : (
                    <StepRow key={row.step.step} step={row.step} attempt={attempt} onShow={onShow} />
                  ),
                )}
              </tbody>
            </table>
          </div>
        </section>
      ))}
      {attempts.length > 0 && (
        <section aria-label={t('messages.duration')}>
          <Descriptions
            size="small"
            items={[
              ...(otherNanos > 0
                ? [{ key: 'other', label: t('messages.otherTime'), children: duration(texts, otherNanos) }]
                : []),
              { key: 'total', label: t('messages.total'), children: duration(texts, totalNanos) },
            ]}
          />
        </section>
      )}
    </>
  );
}

interface StepRowProps {
  step: StepRecord;
  attempt: number;
  onShow: (step: StepRecord) => void;
}

/** One recorded step; `ingress` times nothing, so its wall and CPU time are left empty. */
function StepRow({ step, attempt, onShow }: StepRowProps) {
  const texts = useText();
  const { t } = texts;
  const timed = step.name !== INGRESS;
  return (
    <tr>
      <td>{step.step}</td>
      <td>{step.name}</td>
      <td>
        <OutcomeLabel outcome={step.outcome} />
      </td>
      <td>{step.startedAt}</td>
      <td>{timed ? duration(texts, step.wallNanos) : ''}</td>
      <td>{!timed ? '' : step.cpuNanos === null ? t('messages.cpuTime.unknown') : duration(texts, step.cpuNanos)}</td>
      <td>
        {step.body.kept ? (
          <Allowed action="message.body">
            <Button
              type="link"
              onClick={() => onShow(step)}
              aria-label={t('messages.payload.show', { step: step.step, attempt })}
            >
              {t('messages.size', { size: step.body.size })}
            </Button>
          </Allowed>
        ) : (
          t('messages.size.notKept', { size: step.body.size })
        )}
      </td>
      <td>{step.error ?? ''}</td>
    </tr>
  );
}

type PayloadLoad = { kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'loaded'; payload: Payload };

interface PayloadDialogProps {
  api: AdminApi;
  id: string;
  step: StepRecord;
  onClose: () => void;
}

/** The payload after one step of one attempt, in a dialog: its content type and its text, the first part only when it is large. */
function PayloadDialog({ api, id, step, onClose }: PayloadDialogProps) {
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
    <MountedModal
      width={800}
      footer={null}
      onCancel={onClose}
      title={t('messages.payload.title', { step: step.step, name: step.name, attempt: step.attempt })}
    >
      {load.kind === 'loading' && <ReadSpinner label={t('messages.payload.loading')} />}
      {load.kind === 'failed' && (
        <Alert showIcon type="error" title={t('messages.payload.unread')} description={load.message} />
      )}
      {load.kind === 'loaded' && (
        <Flex vertical gap="small">
          <Typography.Text type="secondary">
            {t('messages.payload.facts', { type: load.payload.contentType, size: step.body.size, hash: step.body.hash })}
          </Typography.Text>
          {!load.payload.complete && (
            <Alert showIcon type="info" title={t('messages.payload.part', { shown: PAYLOAD_SHOWN, size: step.body.size })} />
          )}
          <pre className="rail8-payload">{load.payload.text}</pre>
        </Flex>
      )}
    </MountedModal>
  );
}

type AuditLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; message: string }
  | { kind: 'loaded'; rows: Audited[]; next: string | null; older: 'idle' | 'loading' | { failed: string } };

/**
 * The message's audit, newest first: when, what was done, who did it and how they proved who they are, with the id of their
 * session or token when they had one. Older rows load a page at a time.
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
    <section aria-label={t('messages.audit')}>
      <Typography.Title level={2}>{t('messages.audit')}</Typography.Title>
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
          <div className="rail8-table-scroll">
            <table className="rail8-document-table" aria-label={t('messages.audit.table')}>
              <thead>
                <tr>
                  {[t('messages.audit.when'), t('audit.action'), t('audit.who'), t('messages.audit.how')].map((label) => (
                    <th scope="col" key={label}>
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {load.rows.map((row) => (
                  <tr key={row.id}>
                    <td>{row.at}</td>
                    <td>{auditAction(texts, row)}</td>
                    <td>{row.actor}</td>
                    <td>
                      <AuditHow row={row} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {typeof load.older === 'object' && (
            <Alert showIcon type="error" title={t('audit.older.unread')} description={load.older.failed} />
          )}
          {load.next !== null && (
            <Flex justify="center">
              <Button onClick={() => loadOlder(load)} loading={load.older === 'loading'}>
                {t('messages.audit.older')}
              </Button>
            </Flex>
          )}
        </Flex>
      )}
    </section>
  );
}
