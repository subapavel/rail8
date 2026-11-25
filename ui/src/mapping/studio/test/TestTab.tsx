import { Button, Card, Divider, Flex, Select } from 'antd';
import { MountedModal } from '../../../MountedModal';
import { CaretRightOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useStore } from 'zustand';
import { useText } from '../../../language';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { InstanceTools } from '../mock/InstanceTools';
import { PreviewBadge } from '../mock/PreviewBadge';
import { signatureMock, type PreviewParameter } from '../mock/signature-mock';
import { testInput, testInstances } from '../mock/test-instances';
import { HEADER_DEFAULTS, testPreview, TRACE_LEVEL_ISSUE, TRACE_LEVELS, type TraceLevel } from '../mock/test-preview';
import { CopyTextButton } from '../shared/CopyTextButton';
import { formatOf } from '../shared/documents';
import { PaneSizeTools } from '../shared/PaneSizeTools';
import { readPanelPreference, savePanelPreference } from '../shared/panel-preferences';
import { SideBySide, useSplitLayout, VerticalSplitLayout } from '../shared/SplitLayout';
import { logLines, logText, type LogLine, type TestSession } from '../shared/test-session';
import { selectTraceStale, type WorkingCopyStore } from '../store/working-copy';
import { DOCUMENT_MODES, TestDocument, type DocumentMode, type ParameterRow } from './TestDocument';

export interface TestTabProps {
  mappingId: string;
  store: WorkingCopyStore;
  /** The open mapping's test session: the source document and the processing log, which outlive the tab. */
  session: TestSession;
  browserState: BrowserStateStore;
}

/** The height of the panes and the processing log together. */
const SPLIT_HEIGHT = 'clamp(28rem, calc(100dvh - 19rem), 42rem)';

const viewKey = (pane: 'source' | 'result') => `test.${pane}.view`;
const isMode = (value: unknown): value is DocumentMode => DOCUMENT_MODES.includes(value as DocumentMode);

/** The preview parameters of the signature in one direction, as rows of a pane's parameter table. */
function parameterRows(parameters: readonly PreviewParameter[], direction: PreviewParameter['direction']): ParameterRow[] {
  return parameters.filter((p) => p.direction === direction && p.name.trim()).map((p) => ({ id: `param:${p.id}`, label: p.name, initial: '' }));
}

/** The id of the name of each trace level. */
const TRACE_NAMES: Record<TraceLevel, string> = {
  'No trace': 'studio.trace.none', // untranslated: a stored value, the key of its name
  Warning: 'studio.trace.warning',
  Info: 'studio.trace.info',
  Debug: 'studio.trace.debug',
  All: 'studio.trace.all',
};

const HEADER_ROWS: ParameterRow[] = Object.entries(HEADER_DEFAULTS).map(([key, value]) => ({ id: `header:${key}`, label: key, initial: value }));

/** The lines of the processing log in the monospace font: a section's time before its first line, its details indented. */
function LogBody({ lines }: { lines: readonly LogLine[] }) {
  return (
    <ol style={{ fontFamily: 'var(--ant-font-family-code, monospace)', fontSize: 'var(--ant-font-size-sm)' }}>
      {lines.map((line, i) => (
        <li key={i} style={line.detail ? { paddingInlineStart: 'var(--ant-padding-lg)' } : undefined}>
          {line.time && <time style={{ marginInlineEnd: 'var(--ant-padding-sm)', color: 'var(--ant-color-text-secondary)' }}>{line.time}</time>}
          {line.message}
        </li>
      ))}
    </ol>
  );
}

/**
 * The Test tab: the test instance tools, the source document and the result of the last run side by side, the
 * Run simulation bar, and the processing log with the last run and the latest compile. Run sends the working copy and
 * the source document to the service, in read mode too, as in SAP PO: there the source is a throw-away input, and only
 * edit mode loads a file or changes the test instances. The run's trace feeds the display queue. The trace level, the
 * parameters and the test instances are previews and never reach the service. Escape restores the split view.
 */
export function TestTab({ mappingId, store, session, browserState }: TestTabProps) {
  const { t } = useText();
  const editing = useStore(store, (s) => s.editing);
  const model = useStore(store, (s) => s.workingCopy);
  const lastRun = useStore(store, (s) => s.lastRun);
  const stale = useStore(store, selectTraceStale);
  const source = useStore(session, (s) => s.source);
  const running = useStore(session, (s) => s.running);
  const runFailed = useStore(session, (s) => s.runFailed);
  const runLog = useStore(session, (s) => s.runLog);
  const compileLog = useStore(session, (s) => s.compileLog);
  const preview = testPreview(store, browserState, mappingId);
  const library = testInstances(store, browserState, mappingId);
  // the signature's parameters as this edit session has them (a draft)
  const signatureParameters = useStore(signatureMock(store, browserState, mappingId), (s) => s.value.parameters);
  const trace = useStore(preview, (s) => s.traceLevel);
  const values = useStore(preview, (s) => s.values);
  const [views, setViews] = useState(() => ({
    source: readPanelPreference(browserState, viewKey('source'), 'Text', isMode),
    result: readPanelPreference(browserState, viewKey('result'), 'Text', isMode),
  }));
  const [expanded, setExpanded] = useState(false);
  const layout = useSplitLayout(browserState, 'test.layout', 0.25);
  // the studio shows this tab only once both messages are chosen
  const src = formatOf(model.source!);
  const dst = formatOf(model.target!);
  const lines = logLines(compileLog, runLog);
  const text = logText(lines);
  const output = !runFailed && lastRun?.kind === 'output' ? lastRun.output : null;

  const setView = (pane: 'source' | 'result', mode: DocumentMode) => {
    setViews((previous) => ({ ...previous, [pane]: mode }));
    savePanelPreference(browserState, viewKey(pane), mode);
  };
  const run = () => void session.getState().runTest(trace);
  /** A trace level's name: a level the tab offers in the user's language, another one as it is. */
  const traceName = (level: string) => (Object.hasOwn(TRACE_NAMES, level) ? t(TRACE_NAMES[level as TraceLevel]) : level);

  return (
    <Flex
      vertical
      component="section"
      aria-label={t('studio.tab.test')}
      onKeyDown={(event) => {
        if (event.key !== 'Escape' || event.defaultPrevented || layout.view === 'split' || expanded) return;
        event.preventDefault();
        layout.setView('split');
      }}
    >
      <Flex align="center" gap="small" wrap role="toolbar" aria-label={t('studio.test.instanceActions')}>
        <Flex align="center" gap="small" wrap>
          <InstanceTools
            library={library}
            format={src}
            resource={() => ({ text: source, values })}
            onLoad={(loaded) => testInput(store).getState().load(loaded)}
            editing={editing}
            disabled={running}
          />
        </Flex>
        <div style={{ marginInlineStart: 'auto' }}>
          <PaneSizeTools
            upper
            names={{ minimize: t('studio.test.panes.minimize'), maximize: t('studio.test.panes.maximize') }}
            label={t('studio.test.panes.layout')}
            view={layout.view} onChange={layout.setView} />
        </div>
      </Flex>

      <div style={{ display: 'flex', flexDirection: 'column', height: SPLIT_HEIGHT }}>
        <VerticalSplitLayout
          label={t('studio.test.resizeLog')}
          lowerId={`${mappingId}-processing-log`}
          initialShare={layout.share}
          onCommit={layout.setShare}
          view={layout.view}
          upper={
            <SideBySide label={t('studio.test.resizePanes')} leftId="test-input-pane" browserState={browserState} widthKey={`test.${src}.width`}>
              <TestDocument
                side="Input"
                format={src}
                text={source}
                mode={views.source}
                onMode={(mode) => setView('source', mode)}
                edit={(value) => session.getState().setSource(value)}
                readOnly={running}
                parameters={[
                  { title: t('studio.test.importParameters'), rows: parameterRows(signatureParameters, 'Import') },
                  { title: t('studio.test.headerParameters'), rows: HEADER_ROWS },
                ]}
                values={values}
                onParameter={(id, value) => preview.getState().setValue(id, value)}
                // the parameter values are kept in the browser: read mode leaves them as they are
                valuesReadOnly={!editing || running}
              />
              <TestDocument
                side="Output"
                format={dst}
                text={output}
                failed={runFailed}
                stale={output !== null && stale}
                mode={views.result}
                onMode={(mode) => setView('result', mode)}
                readOnly={false}
                parameters={[{ title: t('studio.test.exportParameters'), rows: parameterRows(signatureParameters, 'Export') }]}
                values={values}
              />
            </SideBySide>
          }
          toolbar={
            <Flex align="center" gap="small" wrap role="toolbar" aria-label={t('studio.test.controls')}>
              <Button
                size="small"
                icon={<CaretRightOutlined aria-hidden />}
                disabled={running}
                loading={running}
                title={t('studio.test.run.title')}
                onClick={run}
              >
                {t('studio.test.run')}
              </Button>
              <Divider vertical />
              <Flex align="center" gap="small" wrap>
                <small aria-hidden="true">{t('studio.test.trace')}</small>
                <Select
                  virtual={false}
                  aria-label={t('studio.test.trace')}
                  value={trace}
                  disabled={!editing || running}
                  onChange={(value) => preview.getState().setTraceLevel(value as TraceLevel)}
                  options={TRACE_LEVELS.map((level) => ({ value: level, label: t(TRACE_NAMES[level]) }))}
                />
                <PreviewBadge feature={t('studio.preview.trace')} issue={TRACE_LEVEL_ISSUE} />
              </Flex>
              <div style={{ marginInlineStart: 'auto' }}>
                <PaneSizeTools
                  names={{ minimize: t('studio.test.log.minimize'), maximize: t('studio.test.log.maximize') }}
                  label={t('studio.test.log.layout')}
                  view={layout.view} onChange={layout.setView} />
              </div>
            </Flex>
          }
        >
          <Card
            className="studio-log"
            size="small"
            role="region"
            aria-labelledby={`${mappingId}-processing-log-heading`}
            tabIndex={0}
            title={<h2 id={`${mappingId}-processing-log-heading`}>{t('studio.test.log')}</h2>}
            extra={
              lines.length > 0 && (
                <>
                  {runLog && <small>{t('studio.test.lastRun', { level: traceName(runLog.level) })}</small>}
                  <Button size="small" type="link" onClick={() => setExpanded(true)}>
                    {t('studio.test.expandLog')}
                  </Button>
                  <CopyTextButton label={t('studio.log.copy')} text={text} />
                </>
              )
            }
          >
            {lines.length > 0 ? <LogBody lines={lines} /> : <p role="status">{t(running ? 'studio.queue.running' : 'studio.test.ready')}</p>}
          </Card>
        </VerticalSplitLayout>
      </div>
      {expanded && (
        <MountedModal title={t('studio.log')} onCancel={() => setExpanded(false)} footer={<Button onClick={() => setExpanded(false)}>{t('frame.tab.close')}</Button>}>
          <p>{runLog ? t('studio.test.simulation', { level: traceName(runLog.level) }) : t('studio.log.compile')}</p>
          <CopyTextButton label={t('studio.log.copy')} text={text} />
          <LogBody lines={lines} />
        </MountedModal>
      )}
    </Flex>
  );
}
