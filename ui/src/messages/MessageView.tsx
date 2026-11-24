import { Alert, Button, Descriptions, Empty, Flex, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
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
  type Via,
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

const waitLabels: Record<WaitReason, string> = {
  worker: 'Saved, waiting for a worker',
  retry: 'Waiting to retry',
  between: 'Between steps',
};

const reason = (failed: unknown) => (failed instanceof Error ? failed.message : String(failed));

const outcomeLabels: Record<Outcome, { text: string; status?: 'success' | 'error' }> = {
  OK: { text: 'OK', status: 'success' },
  FILTERED: { text: 'Filtered' },
  FAILED: { text: 'Failed', status: 'error' },
};

function OutcomeLabel({ outcome }: { outcome: Outcome }) {
  const { text, status } = outcomeLabels[outcome];
  return <Tag color={status}>{text}</Tag>;
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
        <Typography.Title level={1}>Message {id}</Typography.Title>
        <Button onClick={refresh} disabled={load.kind === 'loading'}>
          Refresh
        </Button>
      </Flex>
      {load.kind === 'loading' && <ReadSpinner label="Loading the message" />}
      {load.kind === 'missing' && (
        <Empty
          description={
            <>
              <h2>No such message</h2>The node recorded no message with the id {id}.
            </>
          }
        />
      )}
      {load.kind === 'failed' && (
        <Alert showIcon type="error" title="The message could not be read" description={load.message} />
      )}
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
  const state = latestState(timeline.steps);
  const { attempts, otherNanos, totalNanos } = account(timeline.steps);
  return (
    <>
      <Descriptions
        size="small"
        items={[
          { key: 'flow', label: 'Flow', children: timeline.flow },
          { key: 'attempts', label: 'Attempts', children: attempts.length },
          { key: 'state', label: 'State', children: <OutcomeLabel outcome={state.outcome} /> },
        ]}
      />
      {state.failed && (
        <Alert
          showIcon
          type="error"
          title={`Step ${state.failed.step} (${state.failed.name}) of attempt ${state.attempt} failed`}
          description={state.failed.error ?? 'The step recorded no error message.'}
          action={
            <Allowed action="message.retry">
              <Button onClick={onRestart} disabled={restart.kind === 'running' || restart.kind === 'done'}>
                Restart
              </Button>
            </Allowed>
          }
        />
      )}
      {restart.kind === 'done' && (
        <Alert
          showIcon
          type="success"
          title={`Restarted as attempt ${restart.attempt}`}
          description="The message runs again from its arrival. Refresh to see the steps of the new attempt."
        />
      )}
      {restart.kind === 'failed' && (
        <Alert showIcon type="error" title="The restart was refused" description={restart.message} />
      )}
      {attempts.map(({ attempt, rows }) => (
        <section key={attempt}>
          <Typography.Title level={2}>Attempt {attempt}</Typography.Title>
          <div className="rail8-table-scroll">
            <table className="rail8-document-table" aria-label={`Steps of attempt ${attempt}`}>
              <thead>
                <tr>
                  {['Step', 'Name', 'Outcome', 'Started at', 'Wall time', 'CPU time', 'Payload', 'Error'].map(
                    (label) => (
                      <th scope="col" key={label}>
                        {label}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody>
                {rows.map((row, i) =>
                  row.kind === 'wait' ? (
                    <tr key={`wait-${i}`}>
                      <td />
                      <td colSpan={3}>{waitLabels[row.reason]}</td>
                      <td>{duration(row.nanos)}</td>
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
        <section aria-label="Duration">
          <Descriptions
            size="small"
            items={[
              ...(otherNanos > 0
                ? [{ key: 'other', label: 'Other time between steps', children: duration(otherNanos) }]
                : []),
              { key: 'total', label: 'Total', children: duration(totalNanos) },
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
  const timed = step.name !== INGRESS;
  return (
    <tr>
      <td>{step.step}</td>
      <td>{step.name}</td>
      <td>
        <OutcomeLabel outcome={step.outcome} />
      </td>
      <td>{step.startedAt}</td>
      <td>{timed ? duration(step.wallNanos) : ''}</td>
      <td>{!timed ? '' : step.cpuNanos === null ? 'unknown' : duration(step.cpuNanos)}</td>
      <td>
        {step.body.kept ? (
          <Allowed action="message.body">
            <Button
              type="link"
              onClick={() => onShow(step)}
              aria-label={`Show the payload after step ${step.step} of attempt ${attempt}`}
            >
              {step.body.size} B
            </Button>
          </Allowed>
        ) : (
          `${step.body.size} B, not kept`
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
      title={`Payload after step ${step.step} (${step.name}), attempt ${step.attempt}`}
    >
      {load.kind === 'loading' && <ReadSpinner label="Loading the payload" />}
      {load.kind === 'failed' && (
        <Alert showIcon type="error" title="The payload could not be read" description={load.message} />
      )}
      {load.kind === 'loaded' && (
        <Flex vertical gap="small">
          <Typography.Text type="secondary">
            {load.payload.contentType}, {step.body.size} B, {step.body.hash}
          </Typography.Text>
          {!load.payload.complete && (
            <Alert showIcon type="info" title={`Showing the first ${PAYLOAD_SHOWN} B of ${step.body.size} B`} />
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

const viaLabels: Record<Via, string> = {
  session: 'Session',
  token: 'Token',
  'emergency-key': 'Emergency key',
  'shared-token': 'Shared token',
  local: 'Local, no credential',
  declared: 'Name declared, before users',
};

/** What a row of the audit did, for people; an act this screen does not know shows its id. */
function auditAction(row: Audited): string {
  if (row.action === 'message.retry') return 'Retried';
  if (row.action === 'message.cancel') return 'Cancelled';
  if (row.action === 'message.body.view') {
    return typeof row.detail.step === 'number' ? `Viewed the payload after step ${row.detail.step}` : 'Viewed a payload';
  }
  return row.action;
}

/**
 * The message's audit, newest first: when, what was done, who did it and how they proved who they are, with the id of their
 * session or token when they had one. Older rows load a page at a time.
 */
function MessageAudit({ api, id }: { api: AdminApi; id: string }) {
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
    <section aria-label="Audit">
      <Typography.Title level={2}>Audit</Typography.Title>
      {load.kind === 'loading' && <ReadSpinner label="Reading the audit" />}
      {load.kind === 'failed' && (
        <Alert
          showIcon
          type="error"
          title="The audit could not be read"
          description={load.message}
          action={
            <Button
              onClick={() => {
                setLoad({ kind: 'loading' });
                setReads((n) => n + 1);
              }}
            >
              Try again
            </Button>
          }
        />
      )}
      {load.kind === 'loaded' && load.rows.length === 0 && (
        <Empty description="No one has retried, cancelled or viewed a payload of this message." />
      )}
      {load.kind === 'loaded' && load.rows.length > 0 && (
        <Flex vertical gap="small">
          <div className="rail8-table-scroll">
            <table className="rail8-document-table" aria-label="Audit of the message">
              <thead>
                <tr>
                  {['When', 'Action', 'Who', 'How'].map((label) => (
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
                    <td>{auditAction(row)}</td>
                    <td>{row.actor}</td>
                    <td>
                      {viaLabels[row.via] ?? row.via}
                      {row.credential !== null && (
                        <>
                          {' '}
                          <Typography.Text code>{row.credential}</Typography.Text>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {typeof load.older === 'object' && (
            <Alert showIcon type="error" title="The older rows could not be read" description={load.older.failed} />
          )}
          {load.next !== null && (
            <Flex justify="center">
              <Button onClick={() => loadOlder(load)} loading={load.older === 'loading'}>
                Show older
              </Button>
            </Flex>
          )}
        </Flex>
      )}
    </section>
  );
}
