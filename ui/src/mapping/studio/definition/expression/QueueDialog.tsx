import { Alert, Button, Flex, Switch, Table, Tag } from 'antd';
import { MountedModal } from '../../../../MountedModal';
import { ImportOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { useStore } from 'zustand';
import { useText } from '../../../../language';
import type { TraceEntry } from '../../../api/client';
import type { BrowserStateStore } from '../../browser-state/browser-state';
import { currentInstance, type TestInstancesStore } from '../../mock/test-instances';
import { parseDocument, type DocumentFormat } from '../../shared/documents';
import type { TestSession } from '../../shared/test-session';
import { displayEntry, type ShownValue } from './display-queue';
import { TARGET, type ExpressionGraph, type ExpressionNode } from './expression-graph';
import { readPanelPreference, savePanelPreference } from '../../shared/panel-preferences';
import { queueColumns, type QueueCell } from './queue-columns';

/** The most rows the dialog shows. */
const MAX_ROWS = 500;
const NUMBERS_KEY = 'definition.queue.numbers';
const isBoolean = (value: unknown): value is boolean => typeof value === 'boolean';
/** The id of the line of a slot by its status. */
const STATUS = { created: 'studio.queue.slot.created', suppressed: 'studio.queue.slot.suppressed', truncated: 'studio.queue.slot.truncated' } as const;

export interface QueueDialogProps {
  graph: ExpressionGraph;
  /** The node whose queue shows, a box or the target field, as the graph has it now. */
  node: ExpressionNode;
  /** The last test run's trace by authoring id; none before a run. */
  trace: Readonly<Record<string, TraceEntry>> | undefined;
  /** Whether the trace is stale, from before the last edit: it still shows, marked. */
  stale: boolean;
  /** The target field mapping's authoring id, under which the trace keeps the target field's occurrences. */
  mappingId: string | undefined;
  browserState: BrowserStateStore;
  /** What a dialog without a trace runs. */
  test: QueueTest;
  onClose: () => void;
}

/** The test run of a Display Queue without a trace: the open mapping's test session and what it runs on. */
export interface QueueTest {
  session: TestSession;
  /** The test instances: the latest of the source's format is the data of an empty source. */
  library: TestInstancesStore;
  /** The source message's format, which a test file must have. */
  format: DocumentFormat;
  /** The trace level the run shows in the log (a preview, never sent). */
  level: string;
  /** Whether the studio is in edit mode: only then is a test file offered. */
  editing: boolean;
}

/**
 * The body of a dialog without a trace: while a run is under way, that it runs; else, without a source document,
 * a test file to load in edit mode or, in read mode, where one is loaded; else nothing (`RunFailure` shows a failed run).
 */
function NoTrace({ test: { session, format, level, editing } }: { test: QueueTest }) {
  // an empty source with a test instance takes the latest when the dialog opens (useRunWithoutTrace)
  const empty = useStore(session, (s) => s.source === '');
  const running = useStore(session, (s) => s.running);
  const file = useRef<HTMLInputElement>(null);
  const { t } = useText();

  const load = async (event: ChangeEvent<HTMLInputElement>) => {
    // the input is cleared after each read, so a change always brings a file
    const chosen = event.target.files![0]!;
    event.target.value = '';
    let text: string;
    try {
      text = await chosen.text();
      parseDocument(text, format);
    } catch (error) {
      session.getState().failRun(level, t('studio.queue.badFile', { name: chosen.name, format, reason: (error as Error).message }));
      return;
    }
    session.getState().setSource(text);
    await session.getState().runTest(level);
  };

  if (running) return <p role="status">{t('studio.queue.running')}</p>;
  if (!empty) return null;
  if (!editing) return <p>{t('studio.queue.noData.view')}</p>;
  return (
    <Flex vertical gap="small">
      <div>
        <p>{t('studio.queue.noData.edit')}</p>
      </div>
      <div>
        <Button type="default" icon={<ImportOutlined aria-hidden />} onClick={() => file.current?.click()}>
          {t('studio.queue.load')}
        </Button>
        <input ref={file} type="file" hidden aria-label={t('studio.queue.file')} accept={`.${format.toLowerCase()}`} onChange={(e) => void load(e)} />
      </div>
    </Flex>
  );
}

/** The diagnostics of the last run when it failed, was refused or did not start: its lines between Start and End. */
function RunFailure({ session }: { session: TestSession }) {
  // a failed run always has its log section; a run under way is not failed yet
  const failure = useStore(session, (s) => (s.runFailed && !s.running ? s.runLog : null));
  const { t } = useText();
  if (failure === null) return null;
  return (
    <Alert
      type="error"
      showIcon
      title={<h3>{t('studio.queue.failed')}</h3>}
      description={
        <ul aria-label={t('studio.queue.diagnostics')}>
          {failure.lines
            .filter((line) => line.time === undefined)
            .map((line, i) => (
              <li key={i}>{line.message}</li>
            ))}
        </ul>
      }
    />
  );
}

/**
 * Runs the test once when the dialog opens without a trace and with test data: the source document, else the
 * latest test instance, loaded into the source first. In read mode too: a test runs there, as in SAP PO.
 */
function useRunWithoutTrace(traced: boolean, { session, library, format, level }: QueueTest) {
  useEffect(() => {
    if (traced) return;
    if (session.getState().source === '') {
      const latest = currentInstance(library.getState().value, format);
      if (latest === undefined) return;
      session.getState().loadInstance(latest);
    }
    void session.getState().runTest(level);
  }, [traced, session, library, format, level]);
}

function Value({ value }: { value: ShownValue }) {
  switch (value.kind) {
    case 'value':
      return <code>{value.text}</code>;
    case 'empty':
      return <code>""</code>;
    case 'suppress':
      return <Tag>SUPPRESS</Tag>; // untranslated: SAP PO's keyword for a suppressed value
  }
}

function Cell({ cell }: { cell: QueueCell }) {
  const { t } = useText();
  if (cell.kind === 'value') return <Value value={cell.value} />;
  return <small>{t(cell.kind === 'end' ? 'studio.queue.end' : 'studio.queue.boundary')}</small>;
}

/**
 * The Display Queue of a node: the queues the last test run traced
 * for its inputs and output, read by authoring id, a row per value and per context's end; the target field adds its
 * occurrences per parent slot. After an edit the trace is a stale trace: it still shows, marked, until the next run.
 * Without a trace it runs the test on the test data, or asks for a test file in edit mode (as SAP Integration
 * Suite does); a failed run shows its diagnostics here as in the processing log.
 */
export function QueueDialog({ graph, node, trace, stale, mappingId, browserState, test, onClose }: QueueDialogProps) {
  useRunWithoutTrace(trace !== undefined, test);
  const texts = useText();
  const { t } = texts;
  const [numbers, setNumbers] = useState(() => readPanelPreference(browserState, NUMBERS_KEY, false, isBoolean));
  const { name, columns } = queueColumns(graph, node, trace ?? {}, texts);
  const count = Math.max(0, ...columns.map((c) => c.cells?.length ?? 0));
  const occurrences = node.id === TARGET && mappingId !== undefined ? trace?.[mappingId] : undefined;
  const shownOccurrences = occurrences === undefined ? undefined : displayEntry(occurrences, texts);
  const changeNumbers = (checked: boolean) => {
    setNumbers(checked);
    savePanelPreference(browserState, NUMBERS_KEY, checked);
  };

  return (
    <MountedModal title={t('studio.canvas.queue')} onCancel={onClose} width={760} footer={null}>
      <p>{t(trace === undefined ? 'studio.queue.noRun' : 'studio.queue.traced')}</p>
      <Flex vertical gap="small">
        <div>
          <Flex justify="space-between" align="center">
            <div>
              <strong>ƒ {name}</strong>
            </div>
            <div>
              <Flex align="center" gap="small">
                <label htmlFor="queue-row-numbers">{t('studio.queue.numbers')}</label>
                <Switch id="queue-row-numbers" checked={numbers} onChange={changeNumbers} />
              </Flex>
            </div>
          </Flex>
        </div>
        <div>
          <RunFailure session={test.session} />
        </div>
        {trace === undefined ? (
          <div>
            <NoTrace test={test} />
          </div>
        ) : (
          <>
            {stale && (
              <div>
                <p>
                  <Tag color="warning">{t('studio.queue.stale')}</Tag> {t('studio.queue.stale.why')}
                </p>
              </div>
            )}
            <div style={{ maxHeight: '24rem', overflow: 'auto' }}>
              <Table<number>
                size="small"
                aria-label={t('studio.queue.table', { name })}
                pagination={false}
                rowKey={(index) => String(index)}
                className="queue-table"
                dataSource={Array.from({ length: Math.min(MAX_ROWS, Math.max(1, count)) }, (_, i) => i)}
                columns={[
                  ...(numbers ? [{ key: 'number', title: '#', render: (_: unknown, index: number) => index }] : []),
                  ...columns.map((col) => ({
                    key: col.label,
                    title: col.label,
                    onCell: (index: number) => ({
                      style: { background: col.cells?.[index] && col.cells[index]?.kind !== 'value' ? 'var(--ant-color-bg-layout)' : undefined },
                    }),
                    render: (_: unknown, index: number) => {
                      const cell = col.cells?.[index];
                      return cell ? <Cell cell={cell} /> : col.cells === undefined && index === 0 ? <em>{t('studio.queue.notEvaluated')}</em> : null;
                    },
                  })),
                ]}
              />
            </div>
            {columns
              .filter((c) => c.size !== undefined)
              .map((c) => (
                <div key={c.label}>
                  <small>
                    {c.label} · {c.size}
                    {c.capped && ` · ${t('studio.queue.capped', { what: c.capped })}`}
                  </small>
                </div>
              ))}
            {count > MAX_ROWS && (
              <div>
                <small>{t('studio.queue.rows', { max: MAX_ROWS, count })}</small>
              </div>
            )}
            {shownOccurrences?.kind === 'occurrences' && (
              <section aria-label={t('studio.pane.occurrences')}>
                <h4>
                  {shownOccurrences.max === undefined
                    ? t('studio.queue.occurrences.unbounded', { name, min: shownOccurrences.min })
                    : t('studio.queue.occurrences', { name, min: shownOccurrences.min, max: shownOccurrences.max })}
                </h4>
                {shownOccurrences.capped && <small>{t('studio.queue.capped', { what: shownOccurrences.capped })}</small>}
                <ol aria-label={t('studio.queue.parents')}>
                  {shownOccurrences.rows.map((row) =>
                    row.kind === 'left-out' ? (
                      <li key={`${row.from}-${row.to}`}>
                        <em>{t('studio.queue.leftOut', { from: row.from, to: row.to })}</em>
                      </li>
                    ) : (
                      <li key={row.parentSlot}>
                        {row.minOccurs
                          ? t('studio.queue.parent.min', { slot: row.parentSlot, created: row.created, min: shownOccurrences.min })
                          : t('studio.queue.parent', { slot: row.parentSlot, created: row.created })}
                        <ul>
                          {row.slots.map(({ slot, status }) => (
                            <li key={slot}>{t(STATUS[status], { slot })}</li>
                          ))}
                        </ul>
                      </li>
                    ),
                  )}
                </ol>
              </section>
            )}
          </>
        )}
      </Flex>
    </MountedModal>
  );
}
